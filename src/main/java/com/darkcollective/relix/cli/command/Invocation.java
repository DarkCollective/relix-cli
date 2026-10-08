/*
 * Copyright 2026 Darkcollective, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.darkcollective.relix.cli.command;

import com.darkcollective.relix.cli.CommandFailure;
import com.darkcollective.relix.cli.ExitCode;
import com.darkcollective.relix.cli.catalog.Catalog;
import com.darkcollective.relix.cli.catalog.Trust;
import com.darkcollective.relix.cli.config.Placeholders;
import com.darkcollective.relix.cli.config.Profiles;
import com.darkcollective.relix.cli.config.RelixDirectories;
import com.darkcollective.relix.cli.config.Relixrc;
import com.darkcollective.relix.cli.drivers.DownloadProgress;
import com.darkcollective.relix.cli.io.FileSystemScriptLoader;
import com.darkcollective.relix.cli.io.Host;
import com.darkcollective.relix.cli.io.Reporter;
import com.darkcollective.relix.connectors.std.DriverProvisioner;
import com.darkcollective.relix.embed.RelixException;
import com.darkcollective.relix.embed.Relix;
import com.darkcollective.relix.embed.Sandbox;
import com.darkcollective.relix.processor.connector.ConnectorProvisioner;

import java.io.File;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * One run of the command, settled from its global options and its host: where it starts,
 * how much it says, where {@code ${VAR}}s come from, and how each engine session is built.
 *
 * <p>Everything that can be wrong with the options is found here, before any script is
 * read, so a bad {@code --now} or an unknown profile fails the run with a usage error
 * rather than part-way through. The catalog is read when a command first asks for it.
 */
final class Invocation {

    static final String PROFILE_VARIABLE = "RELIX_PROFILE";
    static final String NOW_VARIABLE = "RELIX_NOW";
    static final String CATALOG_PATH_VARIABLE = "RELIX_CATALOG_PATH";

    private final Host host;
    private final GlobalOptions options;
    private final Reporter reporter;
    private final Path directory;
    private final List<Path> levels;
    private final Trust trust;
    private final Relixrc relixrc;
    private final List<Path> catalogFiles;
    private Catalog catalog;
    private final Function<String, Optional<String>> placeholders;
    private final Clock clock;
    private final Sandbox sandbox;

    Invocation(Host host, GlobalOptions options) {
        this(host, options, true);
    }

    /**
     * A run, settled.
     *
     * @param host     the process
     * @param options  its global options
     * @param announce whether to say which untrusted directories are skipped; not when
     *                 the command is the one that trusts them
     */
    Invocation(Host host, GlobalOptions options, boolean announce) {
        this.host = host;
        this.options = options;
        this.reporter = new Reporter(host.err(), verbosity(options));
        this.directory = directory(host, options);
        this.clock = clock(options.now != null ? options.now : host.variable(NOW_VARIABLE).orElse(null));
        requirePositive("--max-fixpoint-rounds", options.maxFixpointRounds);
        requirePositive("--max-materialized-rows", options.maxMaterializedRows);
        requirePositive("--max-processed-rows", options.maxProcessedRows);
        this.sandbox = options.sandbox == null ? null : sandbox(directory.resolve(options.sandbox));
        this.levels = RelixDirectories.discover(directory, host.home());
        this.trust = Trust.load(host.home(), trustAll(host));
        List<Path> trusted = levels.stream().filter(trust::trusts).toList();
        this.relixrc = options.noCatalog ? Relixrc.none() : Relixrc.read(trusted, reporter::warning);
        this.catalogFiles = catalogFiles(host, options, directory);
        String profile = options.profile != null ? options.profile
                : host.variable(PROFILE_VARIABLE).or(() -> relixrc.get(Relixrc.PROFILE)).orElse(null);
        if (announce) {
            for (Path level : levels) {
                if (!trust.trusts(level) && wouldRead(level, options.noCatalog, profile != null)) {
                    Path project = level.getParent();
                    reporter.warning("skipping " + level + ", which is not trusted; to load it: "
                            + "relix catalog trust " + shellWord(project.toString()));
                }
            }
        }
        Map<String, String> values = profile == null
                ? Map.of()
                : Profiles.select(profile, trusted);
        this.placeholders = Placeholders.resolver(options.defines, values, host.environment());
    }

    Host host() {
        return host;
    }

    Reporter reporter() {
        return reporter;
    }

    /** The defaults the {@code relixrc} files set; none with {@code -N}. */
    Relixrc relixrc() {
        return relixrc;
    }

    /**
     * The catalog: the {@code .relix/} directories' files, unless {@code -N}, and then the
     * {@code $RELIX_CATALOG_PATH} and {@code --catalog} files. Read on the first call.
     *
     * @return the catalog
     */
    Catalog catalog() {
        if (catalog == null) {
            List<Path> discovered = options.noCatalog ? List.of() : levels;
            catalog = discovered.isEmpty() && catalogFiles.isEmpty()
                    ? Catalog.empty()
                    : Catalog.load(discovered, catalogFiles, trust);
            for (var file : catalog.files()) {
                reporter.notice("catalog " + file.path());
            }
        }
        return catalog;
    }

    /**
     * The format a command's rows are written in: its options, else {@code $RELIX_OUTPUT},
     * else {@code relixrc}'s, else the terminal's.
     *
     * @param formatting the command's format options
     * @return the format, settled
     */
    FormatOptions.Format format(FormatOptions formatting) {
        return formatting.settle(host, relixrc.get(Relixrc.OUTPUT));
    }

    /** What the user trusts. */
    Trust trust() {
        return trust;
    }

    /** The {@code .relix/} directories found from where the run starts, outermost first. */
    List<Path> levels() {
        return levels;
    }

    /** Whether {@code --remote} permits http(s) locations. */
    boolean remote() {
        return options.remote;
    }

    /** The directory relative paths start from: the working directory, or {@code -C}. */
    Path directory() {
        return directory;
    }

    /**
     * Loads the JDBC drivers installed under the user's driver directory, saying which
     * with {@code -v}.
     */
    void loadInstalledDrivers() {
        for (String driver : DriverProvisioner.loadInstalled()) {
            reporter.notice("loaded JDBC driver " + driver);
        }
    }

    /**
     * A session builder for a script whose relative paths resolve from {@code base}.
     *
     * @param base the script's directory
     * @return a builder carrying every option of the run
     */
    Relix.Builder session(Path base) {
        PrintWriter progress = new PrintWriter(host.err(), true, StandardCharsets.UTF_8);
        Relix.Builder builder = Relix.builder()
                .baseDirectory(base)
                .scriptLoader(new FileSystemScriptLoader(base))
                .provisioners(
                        DriverProvisioner.create(options.allowDownload, DownloadProgress.reporting(progress)),
                        ConnectorProvisioner.create(options.allowDownload, DownloadProgress.reporting(progress)))
                // A name a database cannot answer for is reported and the rest of the
                // script still runs: a command line over someone else's database must.
                .allowUnresolved()
                .placeholders(placeholders)
                // Text, which the engine reads as the type each parameter was analysed to.
                .parameters(options.arguments)
                .remoteFiles(options.remote);
        if (clock != null) {
            builder.clock(clock);
        }
        if (options.maxFixpointRounds != null) {
            builder.maxFixpointRounds(options.maxFixpointRounds);
        }
        if (options.maxMaterializedRows != null) {
            builder.maxMaterializedRows(options.maxMaterializedRows);
        }
        if (options.maxProcessedRows != null) {
            builder.maxProcessedRows(options.maxProcessedRows);
        }
        if (options.timeout != null) {
            builder.timeout(options.timeout);
        }
        if (sandbox != null) {
            builder.sandbox(sandbox);
        }
        return builder;
    }

    private static Reporter.Verbosity verbosity(GlobalOptions options) {
        if (options.quiet && options.verbose.length > 0) {
            throw new CommandFailure(ExitCode.USAGE, "-q and -v cannot be given together");
        }
        if (options.quiet) {
            return Reporter.Verbosity.QUIET;
        }
        return switch (options.verbose.length) {
            case 0 -> Reporter.Verbosity.NORMAL;
            case 1 -> Reporter.Verbosity.VERBOSE;
            default -> Reporter.Verbosity.TIMINGS;
        };
    }

    private static Path directory(Host host, GlobalOptions options) {
        if (options.directory == null) {
            return host.workingDirectory();
        }
        Path dir = host.workingDirectory().resolve(options.directory).normalize();
        if (!Files.isDirectory(dir)) {
            throw new CommandFailure(ExitCode.USAGE, "-C " + options.directory + ": not a directory");
        }
        return dir;
    }

    private static boolean trustAll(Host host) {
        return host.variable(Trust.TRUST_ALL_VARIABLE)
                .map(v -> v.equals("1") || v.equalsIgnoreCase("true"))
                .orElse(false);
    }

    /** Whether a run would read anything from a {@code .relix/} directory, were it trusted. */
    private static boolean wouldRead(Path level, boolean noCatalog, boolean profile) {
        if (profile && Files.isRegularFile(level.resolve(Profiles.FILE_NAME))) {
            return true;
        }
        return !noCatalog && (!Catalog.filesOf(level).isEmpty()
                || Files.isRegularFile(level.resolve(Relixrc.FILE_NAME)));
    }

    /**
     * A path as a shell word: quoted when it holds anything a shell would split or expand.
     * On Windows a backslash is the separator, not an escape, so it needs no quoting there.
     */
    static String shellWord(String path) {
        String plain = File.separatorChar == '\\' ? "[A-Za-z0-9_@%+=:,./~\\\\-]+" : "[A-Za-z0-9_@%+=:,./~-]+";
        if (path.matches(plain)) {
            return path;
        }
        return "'" + path.replace("'", "'\\''") + "'";
    }

    /**
     * The catalog files named outside the directory tree: {@code $RELIX_CATALOG_PATH}'s,
     * then {@code --catalog}'s, the nearer last.
     */
    private static List<Path> catalogFiles(Host host, GlobalOptions options, Path directory) {
        List<Path> files = new ArrayList<>();
        host.variable(CATALOG_PATH_VARIABLE).ifPresent(path -> {
            for (String entry : path.split(File.pathSeparator)) {
                if (!entry.isBlank()) {
                    files.add(catalogFile("$" + CATALOG_PATH_VARIABLE, directory, Path.of(entry.strip())));
                }
            }
        });
        for (Path file : options.catalogs) {
            files.add(catalogFile("--catalog", directory, file));
        }
        return List.copyOf(files);
    }

    private static Path catalogFile(String from, Path directory, Path file) {
        Path resolved = directory.resolve(file).normalize();
        if (!Files.isRegularFile(resolved)) {
            throw new CommandFailure(ExitCode.USAGE, from + ": " + file + ": no such file");
        }
        return resolved;
    }

    private static Clock clock(String instant) {
        if (instant == null || instant.isBlank()) {
            return null;
        }
        try {
            return Clock.fixed(Instant.parse(instant.strip()), ZoneOffset.UTC);
        } catch (DateTimeParseException e) {
            throw new CommandFailure(ExitCode.USAGE, "'" + instant
                    + "' is not an ISO-8601 instant (such as 2026-07-22T12:00:00Z)");
        }
    }

    private static void requirePositive(String option, Number value) {
        if (value != null && value.longValue() <= 0) {
            throw new CommandFailure(ExitCode.USAGE, option + " must be a positive number");
        }
    }

    private static Sandbox sandbox(Path file) {
        try {
            return Sandbox.load(file);
        } catch (RelixException e) {
            throw new CommandFailure(ExitCode.USAGE, "--sandbox " + file + ": " + e.getMessage());
        }
    }
}
