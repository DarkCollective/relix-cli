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
import com.darkcollective.relix.cli.io.Interruption;
import com.darkcollective.relix.cli.io.Reporter;
import com.darkcollective.relix.cli.io.RowSink;
import com.darkcollective.relix.cli.report.EventFeed;
import com.darkcollective.relix.embed.Relation;
import com.darkcollective.relix.embed.Rows;
import com.darkcollective.relix.embed.Tuple;
import com.darkcollective.relix.events.QueryEvent;

import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;

/**
 * What {@code relix run} shows of a script: its queries' rows, on standard output.
 *
 * <p>A table or Markdown prints every query, each under its label. A machine format
 * prints one result set, because a CSV with two headers is not a CSV: the script's last
 * query, or the one {@code --query} names; {@code --all} with ndjson prints every one,
 * tagged. A query not printed is not run.
 *
 * <p>With {@code --trace}, each query's event feed is written beside its rows, to standard
 * error or a file, as the query runs.
 */
final class ResultRows implements ScriptRunner.View {

    private static final String TRUNCATED = "TRUNCATED";

    private final OutputOptions.Output output;
    private final Interruption interruption;
    private final Reporter reporter;
    private final RowSink sink;
    private final EventFeed trace;
    private long printed;

    /**
     * The rows view.
     *
     * @param invocation   the run
     * @param output       where the rows go and in what shape
     * @param interruption what an interrupt stops
     * @param trace        where the event feed goes, or {@code null} for nowhere
     */
    ResultRows(Invocation invocation, OutputOptions.Output output, Interruption interruption, EventFeed trace) {
        this.output = output;
        this.interruption = interruption;
        this.reporter = invocation.reporter();
        this.sink = new RowSink(invocation.host().out(), output.lineBuffered());
        this.trace = trace;
    }

    @Override
    public ExitCode show(ScriptRunner.Script script) {
        for (Relation relation : chosen(script)) {
            print(script, relation);
        }
        return ExitCode.SUCCESS;
    }

    @Override
    public ExitCode finish(ExitCode result) {
        if (result == ExitCode.SUCCESS && output.assertion().failedBy(printed)) {
            return ExitCode.ASSERTION;
        }
        return result;
    }

    /** The queries to print, in order. */
    private List<Relation> chosen(ScriptRunner.Script script) {
        List<Relation> queries = script.queries(output.query());
        if (output.query() != null || output.every() || queries.isEmpty()) {
            return queries;
        }
        return List.of(queries.getLast());
    }

    private void print(ScriptRunner.Script script, Relation relation) {
        String name = script.source().name();
        long start = System.nanoTime();
        String label = relation.label().orElse("");
        String query = label.isEmpty() ? "query" : label;
        var encoder = output.format().encoder(label, relation.schema(), output.options());
        boolean truncated;
        long count;
        if (output.format().streams() || trace != null) {
            // A traced table is collected from the stream, which is what reports events.
            boolean[] cut = {false};
            try (Stream<Tuple> rows = relation.stream(event -> {
                cut[0] |= isTruncation(event);
                if (trace != null) {
                    trace.event(event, query);
                }
            })) {
                interruption.stream(rows);
                Stream<Tuple> written = output.format().streams() ? rows : rows.toList().stream();
                count = sink.write(written, encoder);
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
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
        if (trace != null) {
            trace.delivered(query, count, elapsed);
        }
        if (truncated) {
            reporter.error(name + ": " + (label.isEmpty() ? "a query" : label)
                    + ": the sandbox cut the result at " + count + " rows");
        }
        reporter.timing(name + ": " + query, elapsed);
    }

    private static boolean isTruncation(QueryEvent event) {
        return event.stage() == QueryEvent.Stage.EXECUTE && TRUNCATED.equals(event.code());
    }
}
