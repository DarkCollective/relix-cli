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
package com.darkcollective.relix.cli.packaging;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The release archive's contents, run as a user runs them: through the launcher, in a
 * process of its own (design §7.3). What a packaging mistake breaks — a provider jlink
 * left out, a driver DriverManager cannot see, a CDS archive the JVM will not map — the
 * unit tests cannot see, because they run on the build's class path.
 *
 * <p>{@code relix.release} names the unpacked archive; {@code ./gradlew
 * packageTest} builds and unpacks it first.
 */
class ReleaseArchiveTest {

    private static final boolean WINDOWS = System.getProperty("os.name").startsWith("Windows");

    private final Path release = Path.of(System.getProperty("relix.release"));

    @TempDir
    Path tree;

    private Path home;

    @BeforeEach
    void home() throws IOException {
        home = Files.createDirectories(tree.resolve("home"));
    }

    @Test
    @DisplayName("runs in a pipe: a script on stdin, a catalog from the directory tree, tsv out")
    void pipeline() throws Exception {
        Path project = Files.createDirectories(home.resolve("project"));
        Path catalog = Files.createDirectories(project.resolve(".relix/catalog"));
        // The connection is to an H2 database, so the bundled driver has to be found.
        // Its names are folded to lower case, as the engine writes them unquoted.
        String url = "jdbc:h2:file:" + project.resolve("shop").toAbsolutePath().toString().replace('\\', '/')
                + ";DATABASE_TO_LOWER=TRUE";
        try (Connection connection = DriverManager.getConnection(url);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE cities (city VARCHAR(20), country VARCHAR(20))");
            statement.execute("INSERT INTO cities VALUES ('london', 'uk'), ('paris', 'fr')");
        }
        Files.writeString(catalog.resolve("people.csv"), "id,name,city\n1,ada,london\n2,bob,paris\n");
        Files.writeString(catalog.resolve("10-shop.relix"), """
                connection shop from database { url: "%s" };
                source People from csv("people.csv") {
                    header: true,
                    schema: { id: NUMBER, name: STRING, city: STRING }
                };
                """.formatted(url));

        Run trust = relix(project, "", "catalog", "trust");
        assertThat(trust.status()).as(trust.toString()).isZero();

        Run run = relix(project, "query { τ name (π name, country (People ⋈ shop.cities)) };\n");

        assertThat(run.status()).as(run.toString()).isZero();
        assertThat(run.out()).isEqualTo("name\tcountry\nada\tuk\nbob\tfr\n");
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    @DisplayName("runs through a relative symbolic link, as Homebrew installs it")
    void symbolicLink() throws Exception {
        Path bin = Files.createDirectories(tree.resolve("prefix/bin")).toRealPath();
        Path link = Files.createSymbolicLink(bin.resolve("relix"),
                bin.relativize(release.resolve("bin/relix").toRealPath()));

        Run run = run(home, "", List.of(), link, "version");

        assertThat(run.status()).as(run.toString()).isZero();
        assertThat(run.out()).startsWith("relix ");
    }

    @Test
    @DisplayName("finds the solver: OPTIMIZE plans")
    void solver() throws Exception {
        Run run = relix(home, """
                Items := [
                | item | value | weight |
                |------|-------|--------|
                | keep | 6     | 2      |
                | drop | 5     | 3      |
                ];
                query { OPTIMIZE MAXIMIZE SUM(value) SUBJECT TO SUM(weight) <= 4 (Items) };
                """);

        assertThat(run.status()).as(run.toString()).isZero();
        assertThat(run.out()).isEqualTo("item\tvalue\tweight\nkeep\t6\t2\n");
    }

    @Test
    @DisplayName("finds the reference: doc shows a page")
    void reference() throws Exception {
        Run run = relix(home, "", "doc", "select");

        assertThat(run.status()).as(run.toString()).isZero();
        assertThat(run.out()).contains("Selection (σ / SELECT)");
    }

    @Test
    @DisplayName("maps its CDS archive: -Xshare:on, which fails without one, starts it from the archive")
    void cdsArchive() throws Exception {
        // Relative to the run's directory: -Xlog splits on ':', which a Windows path holds.
        Run run = relix(home, "", List.of("-Xshare:on", "-Xlog:class+load=info:file=class-load.log"), "version");
        Path log = home.resolve("class-load.log");

        assertThat(run.status()).as(run.toString()).isZero();
        assertThat(run.out()).startsWith("relix ");
        assertThat(Files.readString(log))
                .as("the command's own classes come from the application's archive")
                .containsPattern("com\\.darkcollective\\.relix\\.cli\\.Main source: shared objects file \\(top\\)");
    }

    @Test
    @DisplayName("-Xshare:on refuses to start without its archive, so the test above can fail")
    void cdsArchiveRequired() throws Exception {
        Path java = release.resolve("bin").resolve(WINDOWS ? "java.exe" : "java");
        Process process = new ProcessBuilder(java.toString(), "-Xshare:on",
                "-XX:SharedArchiveFile=" + tree.resolve("missing.jsa"), "-version")
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).as(output).isNotZero();
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    @DisplayName("ships a man page per command, each of which man renders")
    void manPages() throws Exception {
        List<Path> pages;
        try (Stream<Path> files = Files.list(release.resolve("man/man1"))) {
            pages = files.sorted().toList();
        }
        assertThat(pages).extracting(page -> page.getFileName().toString())
                .contains("relix.1", "relix-run.1", "relix-catalog-trust.1", "relix-completion.1");

        for (Path page : pages) {
            // man-db's man takes a file with -l; macOS's (mandoc's) takes one as a path.
            List<String> command = OS.LINUX.isCurrentOs()
                    ? List.of("man", "-l", page.toString())
                    : List.of("man", page.toAbsolutePath().toString());
            ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
            builder.environment().put("MANPAGER", "cat");
            builder.environment().put("MANWIDTH", "80");
            Process process = builder.start();
            process.getOutputStream().close();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8)
                    .replaceAll(".\b", "");

            assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue();
            assertThat(process.exitValue()).as(page + "\n" + output).isZero();
            assertThat(output).as(page.toString()).contains("NAME", "SYNOPSIS", "relix");
        }
    }

    @Test
    @DisplayName("ships a completion script per shell, zsh's loadable from $fpath")
    void completions() throws IOException {
        Path completions = release.resolve("completions");

        for (String file : List.of("relix.bash", "_relix", "relix.fish", "relix.ps1")) {
            assertThat(completions.resolve(file)).isNotEmptyFile();
        }
        assertThat(Files.readAllLines(completions.resolve("_relix")).getFirst()).isEqualTo("#compdef relix");
    }

    // -------------------------------------------------------------------------

    private record Run(int status, String out, String err) {
    }

    private Run relix(Path directory, String stdin, String... args) throws Exception {
        return relix(directory, stdin, List.of(), args);
    }

    /**
     * Runs the archive's launcher in {@code directory}, with {@code stdin} on standard
     * input, the temporary home as its home, and {@code jvmOptions} for its JVM.
     */
    private Run relix(Path directory, String stdin, List<String> jvmOptions, String... args) throws Exception {
        return run(directory, stdin, jvmOptions, release.resolve("bin").resolve(WINDOWS ? "relix.bat" : "relix"), args);
    }

    private Run run(Path directory, String stdin, List<String> jvmOptions, Path launcher, String... args)
            throws Exception {
        List<String> command = new ArrayList<>(List.of(launcher.toString()));
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile());
        Map<String, String> environment = builder.environment();
        Map<String, String> kept = new HashMap<>();
        for (String name : List.of("PATH", "SystemRoot", "TEMP", "TMP", "COMSPEC", "PATHEXT")) {
            if (environment.containsKey(name)) {
                kept.put(name, environment.get(name));
            }
        }
        environment.clear();
        environment.putAll(kept);
        environment.put("HOME", home.toString());
        List<String> options = new ArrayList<>(List.of("-Duser.home=" + home));
        options.addAll(jvmOptions);
        environment.put("JDK_JAVA_OPTIONS", String.join(" ", options));
        Process process = builder.start();
        CompletableFuture<byte[]> out = CompletableFuture.supplyAsync(() -> read(process, true));
        CompletableFuture<byte[]> err = CompletableFuture.supplyAsync(() -> read(process, false));
        try (OutputStream in = process.getOutputStream()) {
            in.write(stdin.getBytes(StandardCharsets.UTF_8));
        }
        assertThat(process.waitFor(120, TimeUnit.SECONDS)).as("relix finished").isTrue();
        return new Run(process.exitValue(), new String(out.get(), StandardCharsets.UTF_8),
                new String(err.get(), StandardCharsets.UTF_8));
    }

    private static byte[] read(Process process, boolean out) {
        try {
            return (out ? process.getInputStream() : process.getErrorStream()).readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
