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
import com.darkcollective.relix.cli.io.Interruption;
import com.darkcollective.relix.cli.io.Reporter;
import com.darkcollective.relix.cli.io.RowSink;
import com.darkcollective.relix.cli.io.ScriptSource;
import com.darkcollective.relix.embed.Diagnostic;
import com.darkcollective.relix.embed.Relation;
import com.darkcollective.relix.embed.Relix;
import com.darkcollective.relix.embed.RelixException;
import com.darkcollective.relix.embed.Rows;
import com.darkcollective.relix.embed.Tuple;
import com.darkcollective.relix.events.QueryEvent;

import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;

/**
 * Runs scripts, each in a session of its own, and writes their rows to standard output.
 *
 * <p>A script is checked before it runs, and every diagnostic is reported, so a script
 * with three mistakes reports three; one with an error does not run. Each script is
 * independent: a failure in one does not stop the next, and the run exits with the first
 * failure's code.
 *
 * <p>A table or Markdown prints every query, each under its label. A machine format
 * prints one result set, because a CSV with two headers is not a CSV: the script's last
 * query, or the one {@code --query} names; {@code --all} with ndjson prints every one,
 * tagged. A query not printed is not run.
 *
 * <p>Two things end the whole run at once, whatever script it is in: standard output
 * closing (exit 141) and an interrupt (exit 130). Neither says anything.
 */
final class ScriptRunner {

    private static final String TRUNCATED = "TRUNCATED";

    private final Invocation invocation;
    private final OutputOptions.Output output;
    private final Interruption interruption;
    private final RowSink sink;
    private long printed;

    ScriptRunner(Invocation invocation, OutputOptions.Output output, Interruption interruption) {
        this.invocation = invocation;
        this.output = output;
        this.interruption = interruption;
        this.sink = new RowSink(invocation.host().out(), output.lineBuffered());
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
        if (result == ExitCode.SUCCESS && output.assertion().failedBy(printed)) {
            return ExitCode.ASSERTION;
        }
        return result;
    }

    private ExitCode run(ScriptSource source) {
        Reporter reporter = invocation.reporter();
        try (Relix session = invocation.session(source.directory()).build()) {
            interruption.session(session);
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
            List<Relation> queries = chosen(session.script(source.text()));
            if (queries == null) {
                reporter.error(source.name() + ": no query named '" + output.query() + "'");
                return ExitCode.USAGE;
            }
            for (Relation relation : queries) {
                print(source, relation);
            }
            return ExitCode.SUCCESS;
        } catch (RuntimeException e) {
            // Closing the session under a running query fails it in whatever way it fails;
            // once interrupted, every such failure is the interrupt's.
            if (interruption.interrupted()) {
                throw new CommandFailure(ExitCode.INTERRUPTED, null);
            }
            if (e instanceof RelixException failure) {
                reporter.error(source.name() + ": " + failure.getMessage());
                return ExitCode.of(failure);
            }
            throw e;
        } finally {
            interruption.session(null);
        }
    }

    /** The queries to print, in order, or {@code null} when the one named is not there. */
    private List<Relation> chosen(List<Relation> queries) {
        if (output.query() != null) {
            List<Relation> named = queries.stream()
                    .filter(q -> q.label().filter(output.query()::equals).isPresent())
                    .toList();
            return named.isEmpty() ? null : named;
        }
        if (output.every() || queries.isEmpty()) {
            return queries;
        }
        return List.of(queries.getLast());
    }

    private void print(ScriptSource source, Relation relation) {
        Reporter reporter = invocation.reporter();
        long start = System.nanoTime();
        String label = relation.label().orElse("");
        var encoder = output.format().encoder(label, relation.schema(), output.options());
        boolean truncated;
        long count;
        if (output.format().streams()) {
            boolean[] cut = {false};
            try (Stream<Tuple> rows = relation.stream(event -> cut[0] |= isTruncation(event))) {
                interruption.stream(rows);
                count = sink.write(rows, encoder);
            } finally {
                interruption.stream(null);
            }
            truncated = cut[0];
        } else {
            // A table aligns its columns over every row, so it collects them first, and a
            // result that never ends is refused rather than collected forever.
            Rows rows = relation.run();
            count = sink.write(rows.rows().stream(), encoder);
            truncated = rows.truncated();
        }
        printed += count;
        if (truncated) {
            reporter.error(source.name() + ": " + (label.isEmpty() ? "a query" : label)
                    + ": the sandbox cut the result at " + count + " rows");
        }
        reporter.timing(source.name() + ": " + (label.isEmpty() ? "query" : label),
                Duration.ofNanos(System.nanoTime() - start));
    }

    private static boolean isTruncation(QueryEvent event) {
        return event.stage() == QueryEvent.Stage.EXECUTE && TRUNCATED.equals(event.code());
    }
}
