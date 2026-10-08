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

import com.darkcollective.relix.ast.Spelling;
import com.darkcollective.relix.cli.CommandFailure;
import com.darkcollective.relix.cli.ExitCode;
import com.darkcollective.relix.cli.io.Reporter;
import com.darkcollective.relix.cli.io.RowSink;
import com.darkcollective.relix.cli.io.ScriptSource;
import com.darkcollective.relix.cli.render.ScriptFormatter;
import com.darkcollective.relix.lang.ast.ScriptParseException;
import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * {@code relix fmt}: prints scripts canonically (design §3), as {@code gofmt} does Go.
 *
 * <p>Each script is printed by the engine's script printer, with its comments kept
 * (see {@link ScriptFormatter}). With no option the formatted scripts go to standard
 * output; {@code -w} rewrites each file that changes in place, and {@code --check} changes
 * nothing, names each file that would change, and exits 1 if any would, for CI.
 *
 * <p>A script that does not parse is reported as {@code FILE:LINE:COL: error: message},
 * left alone, and makes the run exit 3. Nothing is analysed or run: a script naming a
 * relation nothing declares is formatted all the same.
 */
@Command(name = "fmt",
        mixinStandardHelpOptions = true,
        sortOptions = false,
        usageHelpAutoWidth = true,
        description = {
            "Prints scripts canonically, keeping their comments. With no SCRIPT, formats",
            "standard input."},
        exitCodeListHeading = "%nExit status:%n",
        exitCodeList = {
            " 0:success; with --check, no file would change",
            " 1:with --check, a file would change",
            " 3:a script does not parse"})
final class FmtCommand implements Callable<Integer> {

    @ParentCommand
    RelixCommand root;

    @Option(names = "-w", description = "Rewrite each file that changes in place, instead of printing it.")
    boolean write;

    @Option(names = "--check",
            description = "Change nothing; print the name of each file that would change, and exit 1 if any would.")
    boolean check;

    @ArgGroup(exclusive = true)
    SpellingChoice spelling = new SpellingChoice();

    @Parameters(paramLabel = "SCRIPT", arity = "0..*",
            description = "Script files; - is standard input, which is also read when none is named.")
    List<String> files = new ArrayList<>();

    /** {@code --glyphs} or {@code --keywords}. */
    static final class SpellingChoice {

        @Option(names = "--glyphs", description = "Write operators as glyphs: σ, ⋈, ∧ (the default).")
        boolean glyphs;

        @Option(names = "--keywords", description = "Write operators as ASCII keywords: SELECT, JOIN, AND.")
        boolean keywords;
    }

    @Override
    public Integer call() {
        if (write && check) {
            throw new CommandFailure(ExitCode.USAGE, "-w and --check cannot be given together");
        }
        Invocation invocation = root.invocation(false);
        Spelling chosen = spelling.keywords ? Spelling.KEYWORDS : Spelling.GLYPHS;
        List<ScriptSource> sources = sources(invocation);
        if (write && sources.stream().anyMatch(s -> s.name().equals(ScriptSource.STDIN))) {
            throw new CommandFailure(ExitCode.USAGE, "-w rewrites files; standard input has none to rewrite");
        }
        Reporter reporter = invocation.reporter();
        RowSink out = new RowSink(invocation.host().out(), false);
        ExitCode result = ExitCode.SUCCESS;
        boolean changes = false;
        for (ScriptSource source : sources) {
            String formatted;
            try {
                formatted = ScriptFormatter.format(source.text(), source.name(), chosen);
            } catch (ScriptParseException e) {
                reporter.error(new Reporter.Place(source.name(), e.line(), e.column()) + ": error: "
                        + e.description());
                result = ExitCode.ANALYSIS;
                continue;
            }
            boolean changed = !formatted.equals(source.text());
            changes |= changed;
            if (check) {
                if (changed) {
                    out.text(source.name() + "\n");
                }
            } else if (write) {
                if (changed) {
                    rewrite(invocation.directory().resolve(source.name()), formatted);
                }
            } else {
                out.text(formatted);
            }
        }
        if (result == ExitCode.SUCCESS && check && changes) {
            result = ExitCode.ASSERTION;
        }
        return result.status();
    }

    /** The scripts named, or standard input. */
    private List<ScriptSource> sources(Invocation invocation) {
        if (files.isEmpty() && invocation.host().stdinIsTerminal()) {
            throw new CommandFailure(ExitCode.USAGE, null);
        }
        return ScriptSource.resolve(List.of(), files.isEmpty() ? List.of("-") : files,
                invocation.host(), invocation.directory(), false);
    }

    private static void rewrite(Path file, String text) {
        try {
            Files.writeString(file, text, StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            throw new CommandFailure(ExitCode.USAGE, file + ": no such file");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
