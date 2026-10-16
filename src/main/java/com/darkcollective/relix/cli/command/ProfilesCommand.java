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
import com.darkcollective.relix.cli.config.Profiles;
import com.darkcollective.relix.cli.config.RelixDirectories;
import com.darkcollective.relix.symbol.ScalarType;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

import java.io.Console;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * {@code relix profiles}: the named profiles of {@code ${VAR}} values (design §5.5), and
 * setting them, so that a credential never has to be typed into a {@code profiles.json}.
 *
 * <p>A value never comes from an argument, where it would land in shell history and in
 * {@code ps}: {@code set} prompts for it on a terminal, without echoing it, and reads it
 * from standard input otherwise. {@code ls} never prints a value; {@code get} prints one,
 * because a pipeline may need it.
 *
 * <p>{@code set} and {@code rm} change the nearest project's {@code profiles.json}, or with
 * {@code --user} the user's own, following the trust rule of {@link ProjectWrite}. The file
 * is written only its owner can read, and one readable by others is refused, as reading
 * it is.
 */
@Command(name = "profiles",
        mixinStandardHelpOptions = true,
        sortOptions = false,
        usageHelpAutoWidth = true,
        description = {
            "Lists, sets and removes the variables of the profiles $${VAR} placeholders",
            "resolve from (-P). Without a subcommand, lists them, as ls does. A value is",
            "never taken from an argument, and never listed."},
        subcommands = {
            ProfilesCommand.Ls.class, ProfilesCommand.Set.class, ProfilesCommand.Get.class,
            ProfilesCommand.Rm.class})
final class ProfilesCommand implements Callable<Integer> {

    private static final Pattern VARIABLE = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    @ParentCommand
    RelixCommand root;

    @Mixin
    FormatOptions formatting;

    @Override
    public Integer call() {
        return list(root.writing(), formatting, false);
    }

    /** The options that choose the user's file over the project's. */
    static final class Scope {
        @Option(names = "--user",
                description = "Use ~/.relix/profiles.json rather than the nearest project's.")
        boolean user;
    }

    private static int list(Invocation invocation, FormatOptions formatting, boolean user) {
        Path own = invocation.host().home().resolve(RelixDirectories.NAME);
        List<Path> levels = user ? List.of(own) : invocation.levels();
        Listing listing = new Listing("profile", ScalarType.STRING, "variable", ScalarType.STRING,
                "file", ScalarType.STRING);
        for (Path level : levels) {
            Path file = level.resolve(Profiles.FILE_NAME);
            Profiles.load(file).forEach((profile, variables) -> {
                if (variables.isEmpty()) {
                    listing.add(profile, null, file.toString());
                }
                variables.keySet().forEach(variable -> listing.add(profile, variable, file.toString()));
            });
        }
        listing.print(invocation, invocation.format(formatting), "profiles");
        return ExitCode.SUCCESS.status();
    }

    private static void requireVariable(String variable) {
        if (!VARIABLE.matcher(variable).matches()) {
            throw new CommandFailure(ExitCode.USAGE, "'" + variable
                    + "' is not a variable name: letters, digits and _, not starting with a digit");
        }
    }

    /**
     * Changes the profiles of the file a command writes, keeping its project's trust, and
     * warns when git tracks it.
     */
    private static void change(Invocation invocation, boolean user, String what,
                               Consumer<Map<String, Map<String, String>>> edit) {
        Path level = ProjectWrite.level(invocation, user);
        Path file = level.resolve(Profiles.FILE_NAME);
        Map<String, Map<String, String>> profiles = Profiles.load(file);
        edit.accept(profiles);
        ProjectWrite.keepingTrust(invocation, level, () -> Profiles.save(file, profiles));
        invocation.reporter().info(what + " in " + file);
        if (tracked(invocation, file)) {
            invocation.reporter().warning(file + " is tracked by git, so what it holds may be pushed"
                    + " with the repository; stop tracking it with: git rm --cached "
                    + Invocation.shellWord(file.toString()));
        }
    }

    /** Whether git tracks a file, when git is on the {@code PATH}. */
    private static boolean tracked(Invocation invocation, Path file) {
        Path git = invocation.host().variable("PATH").stream()
                .flatMap(path -> Arrays.stream(path.split(File.pathSeparator)))
                .filter(dir -> !dir.isBlank())
                .flatMap(dir -> Stream.of(Path.of(dir, "git"), Path.of(dir, "git.exe")))
                .filter(Files::isExecutable)
                .findFirst().orElse(null);
        if (git == null) {
            return false;
        }
        try {
            Process process = new ProcessBuilder(git.toString(), "ls-files", "--error-unmatch",
                    file.getFileName().toString())
                    .directory(file.getParent().toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            process.getOutputStream().close();
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** {@code relix profiles ls}. */
    @Command(name = "ls",
            mixinStandardHelpOptions = true,
            sortOptions = false,
            usageHelpAutoWidth = true,
            description = {
                "Lists each profile's variables and the file that sets each, outermost first:",
                "profile, variable and file, never the value. A profile with no variable is",
                "listed once, with an empty variable."})
    static final class Ls implements Callable<Integer> {

        @ParentCommand
        ProfilesCommand profiles;

        @Mixin
        Scope scope;

        @Mixin
        FormatOptions formatting;

        @Override
        public Integer call() {
            return list(profiles.root.writing(), formatting, scope.user);
        }
    }

    /** {@code relix profiles set PROFILE VARIABLE}. */
    @Command(name = "set",
            mixinStandardHelpOptions = true,
            sortOptions = false,
            usageHelpAutoWidth = true,
            description = {
                "Sets VARIABLE in PROFILE, creating either. The value is read without echoing it",
                "when standard input is a terminal, and from standard input otherwise, less one",
                "line break at its end; it is never taken from an argument, which would leave it",
                "in shell history and ps. Writes the nearest project's profiles.json, or with",
                "--user ~/.relix/profiles.json, making it readable by you alone."},
            exitCodeListHeading = "%nExit status:%n",
            exitCodeList = {
                " 0:success",
                " 2:no project here (give --user), a bad name, or no value",
                " 5:the file is readable by others, or cannot be written"})
    static final class Set implements Callable<Integer> {

        @ParentCommand
        ProfilesCommand profiles;

        @Parameters(index = "0", paramLabel = "PROFILE", description = "The profile.")
        String profile;

        @Parameters(index = "1", paramLabel = "VARIABLE", description = "The variable, as $${VARIABLE} names it.")
        String variable;

        @Mixin
        Scope scope;

        @Override
        public Integer call() {
            Invocation invocation = profiles.root.writing();
            requireVariable(variable);
            if (profile.isBlank()) {
                throw new CommandFailure(ExitCode.USAGE, "a profile needs a name");
            }
            // Where the value goes is checked before it is asked for.
            ProjectWrite.level(invocation, scope.user);
            String value = value(invocation);
            change(invocation, scope.user, "set " + variable + " in profile " + profile,
                    all -> all.computeIfAbsent(profile, p -> new LinkedHashMap<>()).put(variable, value));
            return ExitCode.SUCCESS.status();
        }

        private String value(Invocation invocation) {
            if (invocation.host().stdinIsTerminal()) {
                Console console = System.console();
                if (console == null) {
                    throw new CommandFailure(ExitCode.USAGE, "no terminal to ask for the value on;"
                            + " give it on standard input");
                }
                char[] typed = console.readPassword("%s for profile %s: ", variable, profile);
                if (typed == null || typed.length == 0) {
                    throw new CommandFailure(ExitCode.USAGE, "no value given");
                }
                return new String(typed);
            }
            String text;
            try {
                text = new String(invocation.host().in().readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new CommandFailure(ExitCode.ENVIRONMENT, "cannot read standard input: " + e.getMessage());
            }
            if (text.endsWith("\r\n")) {
                text = text.substring(0, text.length() - 2);
            } else if (text.endsWith("\n")) {
                text = text.substring(0, text.length() - 1);
            }
            if (text.isEmpty()) {
                throw new CommandFailure(ExitCode.USAGE, "no value on standard input");
            }
            return text;
        }
    }

    /** {@code relix profiles get PROFILE VARIABLE}. */
    @Command(name = "get",
            mixinStandardHelpOptions = true,
            sortOptions = false,
            usageHelpAutoWidth = true,
            description = {
                "Prints the value VARIABLE has in PROFILE, as a run under -P PROFILE would",
                "resolve it from the profiles.json files (the nearest wins), or with --user from",
                "~/.relix/profiles.json alone. The environment and -D are not consulted."},
            exitCodeListHeading = "%nExit status:%n",
            exitCodeList = {
                " 0:success",
                " 2:no such profile, or it does not set VARIABLE",
                " 5:a profiles file is readable by others"})
    static final class Get implements Callable<Integer> {

        @ParentCommand
        ProfilesCommand profiles;

        @Parameters(index = "0", paramLabel = "PROFILE", description = "The profile.")
        String profile;

        @Parameters(index = "1", paramLabel = "VARIABLE", description = "The variable.")
        String variable;

        @Mixin
        Scope scope;

        @Override
        public Integer call() {
            Invocation invocation = profiles.root.writing();
            Path own = invocation.host().home().resolve(RelixDirectories.NAME);
            List<Path> levels = scope.user ? List.of(own)
                    : invocation.levels().stream().filter(invocation.trust()::trusts).toList();
            String value = Profiles.select(profile, levels).get(variable);
            if (value == null) {
                throw new CommandFailure(ExitCode.USAGE, "profile '" + profile + "' does not set " + variable);
            }
            try {
                OutputStream out = invocation.host().out();
                out.write((value + "\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
            } catch (IOException e) {
                throw new CommandFailure(ExitCode.ENVIRONMENT, "cannot write standard output: " + e.getMessage());
            }
            return ExitCode.SUCCESS.status();
        }
    }

    /** {@code relix profiles rm PROFILE [VARIABLE]}. */
    @Command(name = "rm",
            mixinStandardHelpOptions = true,
            sortOptions = false,
            usageHelpAutoWidth = true,
            description = {
                "Removes VARIABLE from PROFILE, or without VARIABLE the whole profile, in the",
                "nearest project's profiles.json, or with --user in ~/.relix/profiles.json."},
            exitCodeListHeading = "%nExit status:%n",
            exitCodeList = {
                " 0:success",
                " 2:no project here, or the file does not set it",
                " 5:the file is readable by others, or cannot be written"})
    static final class Rm implements Callable<Integer> {

        @ParentCommand
        ProfilesCommand profiles;

        @Parameters(index = "0", paramLabel = "PROFILE", description = "The profile.")
        String profile;

        @Parameters(index = "1", arity = "0..1", paramLabel = "VARIABLE", description = "The variable.")
        String variable;

        @Mixin
        Scope scope;

        @Override
        public Integer call() {
            Invocation invocation = profiles.root.writing();
            String what = variable == null ? "removed profile " + profile
                    : "removed " + variable + " from profile " + profile;
            change(invocation, scope.user, what, all -> {
                Map<String, String> variables = all.get(profile);
                Path file = ProjectWrite.level(invocation, scope.user).resolve(Profiles.FILE_NAME);
                if (variables == null) {
                    throw new CommandFailure(ExitCode.USAGE, file + " has no profile '" + profile + "'");
                }
                if (variable == null) {
                    all.remove(profile);
                } else if (variables.remove(variable) == null) {
                    throw new CommandFailure(ExitCode.USAGE, "profile '" + profile + "' in " + file
                            + " does not set " + variable);
                }
            });
            return ExitCode.SUCCESS.status();
        }
    }
}
