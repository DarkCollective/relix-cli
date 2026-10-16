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
import com.darkcollective.relix.cli.config.Profiles;
import com.darkcollective.relix.cli.config.RelixDirectories;
import com.darkcollective.relix.cli.config.Relixrc;
import com.darkcollective.relix.cli.render.JsonText;
import com.darkcollective.relix.cli.render.OutputFormat;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * {@code relix init}: makes a project's {@code .relix/} directory, so that its first
 * query needs no file written by hand (design §5.1).
 *
 * <p>It writes the catalog directory, a {@code profiles.json} only its owner can read,
 * empty but for the profile {@code -P} names, a {@code .gitignore} beside it that keeps the profiles out of version control,
 * and, when asked, {@code relixrc} and the {@code root} marker. Then it trusts what it
 * wrote, as {@code relix catalog trust} would, so the next run loads it without a warning.
 * A directory that already has a {@code .relix/} is refused: running it again is never
 * how a project is repaired.
 */
@Command(name = "init",
        mixinStandardHelpOptions = true,
        sortOptions = false,
        usageHelpAutoWidth = true,
        description = {
            "Creates the .relix/ directory of DIR (default: the working directory): an empty",
            "catalog/, a profiles.json only you can read, a .gitignore that keeps",
            "profiles.json out of version control, and relixrc and root when asked for:",
            "-o and -P (the global option) write a relixrc with those defaults, and the",
            "profile -P names is defined, empty, in profiles.json. Then trusts the new",
            "directory, so the next run there loads it. Writes nothing to standard output."},
        exitCodeListHeading = "%nExit status:%n",
        exitCodeList = {
            " 0:success",
            " 2:DIR already has a .relix/ directory, or an option is not valid",
            " 5:a file cannot be written"})
final class InitCommand implements Callable<Integer> {

    /** What the {@code .gitignore} inside a new {@code .relix/} holds. */
    static final String GITIGNORE = Profiles.FILE_NAME + "\n";

    @ParentCommand
    RelixCommand root;

    @Parameters(paramLabel = "DIR", arity = "0..1",
            description = "The project's directory, made if it does not exist.")
    Path directory;

    @Option(names = "--root",
            description = "Write the root marker: discovery stops at this project, and the directories above it are not read.")
    boolean rootMarker;

    @Option(names = {"-o", "--output"}, paramLabel = "FORMAT",
            description = "Write a relixrc whose default output format is FORMAT.")
    String output;

    @Override
    public Integer call() {
        Invocation invocation = root.writing();
        String profile = root.profileOption();
        if (output != null) {
            try {
                OutputFormat.fromString(output.strip());
            } catch (IllegalArgumentException e) {
                throw new CommandFailure(ExitCode.USAGE, "-o: " + e.getMessage());
            }
        }
        if (profile != null && profile.isBlank()) {
            throw new CommandFailure(ExitCode.USAGE, "--profile: a profile needs a name");
        }
        Path project = directory == null ? invocation.directory()
                : invocation.directory().resolve(directory).normalize();
        Path level = project.resolve(RelixDirectories.NAME);
        if (Files.exists(level)) {
            throw new CommandFailure(ExitCode.USAGE, level + " already exists;"
                    + " relix init makes a new project and never changes one");
        }
        Path home = invocation.host().home();
        try {
            Files.createDirectories(level.resolve(Catalog.DIRECTORY));
            // The profile relixrc names is defined, empty, so that the next run finds it
            // before relix profiles set has given it a value.
            Profiles.writePrivate(level.resolve(Profiles.FILE_NAME), profile == null ? "{}\n"
                    : "{\n  " + JsonText.quote(profile.strip()) + ": {}\n}\n");
            write(level.resolve(".gitignore"), GITIGNORE);
            if (output != null || profile != null) {
                StringBuilder relixrc = new StringBuilder();
                if (output != null) {
                    relixrc.append(Relixrc.OUTPUT).append(" = ").append(output.strip()).append('\n');
                }
                if (profile != null) {
                    relixrc.append(Relixrc.PROFILE).append(" = ").append(profile.strip()).append('\n');
                }
                write(level.resolve(Relixrc.FILE_NAME), relixrc.toString());
            }
            if (rootMarker) {
                write(level.resolve(RelixDirectories.ROOT_MARKER),
                        "# The walk up for .relix/ directories stops here.\n");
            }
        } catch (IOException e) {
            throw new CommandFailure(ExitCode.ENVIRONMENT, level + ": cannot write: " + e.getMessage());
        }
        invocation.reporter().info("created " + level);
        if (!level.equals(home.resolve(RelixDirectories.NAME))) {
            invocation.trust().trust(level);
            invocation.reporter().info("trusting " + level);
        }
        if (!project.startsWith(home)) {
            invocation.reporter().warning(project + " is outside " + home + ": there, a run reads only"
                    + " the working directory's own .relix/, so it is used only from " + project + " itself");
        }
        return ExitCode.SUCCESS.status();
    }

    private static void write(Path file, String text) throws IOException {
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }
}
