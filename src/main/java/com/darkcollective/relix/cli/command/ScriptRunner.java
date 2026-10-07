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

import com.darkcollective.relix.cli.ExitCode;
import com.darkcollective.relix.cli.io.Reporter;
import com.darkcollective.relix.cli.io.ScriptSource;
import com.darkcollective.relix.cli.render.QueryResultFormatter;
import com.darkcollective.relix.embed.Diagnostic;
import com.darkcollective.relix.embed.Relation;
import com.darkcollective.relix.embed.Relix;
import com.darkcollective.relix.embed.RelixException;
import com.darkcollective.relix.embed.Rows;
import com.darkcollective.relix.processor.Row;

import java.io.PrintStream;
import java.time.Duration;
import java.util.List;

/**
 * Runs scripts, each in a session of its own, and prints every query's rows.
 *
 * <p>A script is checked before it runs, and every diagnostic is reported, so a script
 * with three mistakes reports three; one with an error does not run. Each script is
 * independent: a failure in one does not stop the next, and the run exits with the first
 * failure's code.
 *
 * <p>Output here is the aligned table for every query. Choosing the format, the default in
 * a pipe and which queries a machine format prints are the output slice's (S2).
 */
final class ScriptRunner {

    private final Invocation invocation;

    ScriptRunner(Invocation invocation) {
        this.invocation = invocation;
    }

    ExitCode run(List<ScriptSource> sources) {
        invocation.loadInstalledDrivers();
        ExitCode result = ExitCode.SUCCESS;
        for (ScriptSource source : sources) {
            ExitCode code = run(source);
            if (result == ExitCode.SUCCESS) {
                result = code;
            }
        }
        return result;
    }

    private ExitCode run(ScriptSource source) {
        Reporter reporter = invocation.reporter();
        PrintStream out = invocation.host().out();
        try (Relix session = invocation.session(source.directory()).build()) {
            long start = System.nanoTime();
            List<Diagnostic> diagnostics = session.validate(source.text());
            reporter.timing(source.name() + ": analysed", Duration.ofNanos(System.nanoTime() - start));
            boolean errors = false;
            for (Diagnostic diagnostic : diagnostics) {
                reporter.diagnostic(source.place(diagnostic.location()), diagnostic);
                errors |= diagnostic.isError();
            }
            if (errors) {
                return ExitCode.ANALYSIS;
            }
            for (Relation relation : session.script(source.text())) {
                long queryStart = System.nanoTime();
                Rows rows = relation.run();
                String label = relation.label().orElse("");
                out.print(QueryResultFormatter.format(label, rows.schema(), rows.rows().stream().map(Row.class::cast)));
                out.flush();
                if (rows.truncated()) {
                    reporter.error(source.name() + ": " + (label.isEmpty() ? "a query" : label)
                            + ": the sandbox cut the result at " + rows.size() + " rows");
                }
                reporter.timing(source.name() + ": " + (label.isEmpty() ? "query" : label),
                        Duration.ofNanos(System.nanoTime() - queryStart));
            }
            return ExitCode.SUCCESS;
        } catch (RelixException e) {
            reporter.error(source.name() + ": " + e.getMessage());
            return ExitCode.of(e);
        }
    }
}
