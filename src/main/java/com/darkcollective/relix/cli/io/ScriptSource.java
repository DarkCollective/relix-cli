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
package com.darkcollective.relix.cli.io;

import com.darkcollective.relix.ast.SourceLocation;
import com.darkcollective.relix.cli.CommandFailure;
import com.darkcollective.relix.cli.ExitCode;
import com.darkcollective.relix.embed.Relix;
import com.darkcollective.relix.lang.ast.ScriptParseException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One script to run, in a session of its own: its text, the name diagnostics give it, and
 * the directory its relative paths resolve against.
 *
 * <p>{@link #resolve} decides where the scripts come from (design §3.1), in this order:
 * <ol>
 *   <li>{@code -e TEXT}, repeatable, all of them one script. A fragment that is a bare
 *       relational expression rather than statements runs as {@code query { … };}.</li>
 *   <li>{@code SCRIPT...} files, each its own script; {@code -} names standard input.</li>
 *   <li>Standard input, when nothing is named and it is not a terminal.</li>
 *   <li>Otherwise there is nothing to run: a usage error.</li>
 * </ol>
 *
 * @param name      what a diagnostic calls the script: its path, {@code -e} or
 *                  {@code <stdin>}
 * @param text      the script
 * @param directory where its relative paths and imports resolve from, absolute
 * @param fragments for {@code -e} scripts, where each fragment sits in {@code text};
 *                  empty otherwise
 */
public record ScriptSource(String name, String text, Path directory, List<Fragment> fragments) {

    /** The name standard input goes by in diagnostics. */
    public static final String STDIN = "<stdin>";

    /**
     * A script source, checked.
     */
    public ScriptSource {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(text, "text");
        directory = directory.toAbsolutePath().normalize();
        fragments = List.copyOf(fragments);
    }

    /**
     * One {@code -e} fragment's place in the joined script.
     *
     * @param name      its name in diagnostics: {@code -e}, or {@code -e#2} when there are several
     * @param firstLine the joined script's line that is the fragment's first line
     * @param endLine   the joined script's last line that belongs to the fragment, its
     *                  query wrapper included
     */
    public record Fragment(String name, int firstLine, int endLine) {
    }

    /**
     * Where a diagnostic's location falls, in the terms the user wrote: the fragment and
     * line of an {@code -e}, or the line of a file.
     *
     * @param location the location the engine reported, if any
     * @return the place to print
     */
    public Reporter.Place place(Optional<SourceLocation> location) {
        if (location.isEmpty() || location.get().line() <= 0) {
            return new Reporter.Place(name, 0, 0);
        }
        SourceLocation at = location.get();
        // A location in another file, such as an imported one, names that file by its
        // absolute path; the script itself goes by whatever the session calls it.
        String file = at.filePath();
        if (isAbsolutePath(file)) {
            return new Reporter.Place(file, at.line(), at.column());
        }
        for (Fragment fragment : fragments) {
            if (at.line() <= fragment.endLine()) {
                int line = Math.max(1, at.line() - fragment.firstLine() + 1);
                return new Reporter.Place(fragment.name(), line, at.column());
            }
        }
        return new Reporter.Place(name, at.line(), at.column());
    }

    /**
     * The scripts this invocation runs.
     *
     * @param expressions the {@code -e} texts, in order
     * @param files       the {@code SCRIPT} arguments, in order; {@code -} is standard input
     * @param host        the process, for standard input and whether it is a terminal
     * @param directory   the directory relative paths start from ({@code -C}), absolute
     * @return one source per script, in order
     * @throws CommandFailure with {@link ExitCode#USAGE} when there is nothing to run, when
     *                        both {@code -e} and files are given, when standard input is
     *                        named twice, or when a file cannot be read
     */
    public static List<ScriptSource> resolve(List<String> expressions, List<String> files,
                                             Host host, Path directory) {
        if (!expressions.isEmpty() && !files.isEmpty()) {
            throw new CommandFailure(ExitCode.USAGE,
                    "give the script with -e or as files, not both");
        }
        if (!expressions.isEmpty()) {
            return List.of(fromExpressions(expressions, directory));
        }
        if (!files.isEmpty()) {
            if (files.stream().filter("-"::equals).count() > 1) {
                throw new CommandFailure(ExitCode.USAGE, "standard input (-) can be read only once");
            }
            List<ScriptSource> sources = new ArrayList<>();
            for (String file : files) {
                sources.add(file.equals("-") ? fromStdin(host, directory) : fromFile(file, directory));
            }
            return sources;
        }
        if (!host.stdinIsTerminal()) {
            return List.of(fromStdin(host, directory));
        }
        throw new CommandFailure(ExitCode.USAGE, null);
    }

    private static ScriptSource fromExpressions(List<String> expressions, Path directory) {
        StringBuilder text = new StringBuilder();
        List<Fragment> fragments = new ArrayList<>();
        int line = 1;
        for (int i = 0; i < expressions.size(); i++) {
            String fragment = expressions.get(i);
            String name = expressions.size() == 1 ? "-e" : "-e#" + (i + 1);
            int lines = lineCount(fragment);
            if (isBareExpression(fragment)) {
                // The wrapper takes lines of its own, so the fragment's lines and columns
                // are unchanged and a diagnostic maps back by its line alone.
                text.append("query {\n").append(fragment).append("\n};\n");
                fragments.add(new Fragment(name, line + 1, line + lines + 1));
                line += lines + 2;
            } else {
                text.append(fragment).append('\n');
                fragments.add(new Fragment(name, line, line + lines - 1));
                line += lines;
            }
        }
        return new ScriptSource("-e", text.toString(), directory, fragments);
    }

    /**
     * Whether a fragment is a relational expression rather than statements: it does not
     * parse as a script, and does as the body of a query. A fragment that parses as
     * neither stays as written, so the error reported is about what the user wrote.
     */
    private static boolean isBareExpression(String fragment) {
        try {
            Relix.parse(fragment);
            return false;
        } catch (ScriptParseException notAScript) {
            try {
                Relix.parse("query {\n" + fragment + "\n};");
                return true;
            } catch (ScriptParseException notAnExpression) {
                return false;
            }
        }
    }

    private static ScriptSource fromFile(String file, Path directory) {
        Path path = directory.resolve(file).normalize();
        String text;
        try {
            text = Files.readString(path, StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            throw new CommandFailure(ExitCode.USAGE, file + ": no such file");
        } catch (IOException e) {
            throw new CommandFailure(ExitCode.USAGE, file + ": " + e.getMessage());
        }
        Path parent = path.getParent();
        return new ScriptSource(file, text, parent != null ? parent : directory, List.of());
    }

    private static ScriptSource fromStdin(Host host, Path directory) {
        try {
            String text = new String(host.in().readAllBytes(), StandardCharsets.UTF_8);
            return new ScriptSource(STDIN, text, directory, List.of());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static boolean isAbsolutePath(String file) {
        if (file == null || file.isBlank()) {
            return false;
        }
        try {
            return Path.of(file).isAbsolute();
        } catch (InvalidPathException e) {
            return false;
        }
    }

    private static int lineCount(String text) {
        int lines = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                lines++;
            }
        }
        return lines;
    }
}
