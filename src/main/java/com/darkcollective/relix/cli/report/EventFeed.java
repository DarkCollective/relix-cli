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
package com.darkcollective.relix.cli.report;

import com.darkcollective.relix.cli.CommandFailure;
import com.darkcollective.relix.cli.ExitCode;
import com.darkcollective.relix.events.EventMetrics;
import com.darkcollective.relix.events.QueryEvent;

import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;

/**
 * The event feed: what the engine reports while it rewrites, plans and runs a query, one
 * line per event, written as each event happens.
 *
 * <p>A line reads {@code [STAGE   ]  target  CODE  description}, with the time the event
 * measured after it when it measured one. An event that names no target names the query
 * it belongs to. A query's feed ends with a {@code ROWS} event of the engine's own shape,
 * saying how many rows it delivered and how long that took.
 *
 * <p>{@code relix trace} writes the feed to standard output, and {@code relix run --trace}
 * to standard error or a file, beside the rows.
 */
public final class EventFeed implements AutoCloseable {

    private final PrintWriter out;
    private final boolean stdout;
    private final boolean owned;

    private EventFeed(OutputStream out, boolean stdout, boolean owned) {
        this.out = new PrintWriter(Objects.requireNonNull(out, "out"), false, StandardCharsets.UTF_8);
        this.stdout = stdout;
        this.owned = owned;
    }

    /**
     * A feed on standard output, whose reader going away ends the run with
     * {@link ExitCode#PIPE_CLOSED}.
     *
     * @param out standard output
     * @return the feed
     */
    public static EventFeed stdout(OutputStream out) {
        return new EventFeed(out, true, false);
    }

    /**
     * A feed on standard error, which closing the feed leaves open.
     *
     * @param err standard error
     * @return the feed
     */
    public static EventFeed stderr(OutputStream err) {
        return new EventFeed(err, false, false);
    }

    /**
     * A feed written to a file, which closing the feed closes.
     *
     * @param file the file, open
     * @return the feed
     */
    public static EventFeed file(OutputStream file) {
        return new EventFeed(file, false, true);
    }

    /**
     * Writes one event.
     *
     * @param event the event
     * @param query the label of the query it belongs to, for an event that names no target
     */
    public void event(QueryEvent event, String query) {
        write(line(event.target().isPresent() ? event : event.withTarget(query)));
    }

    /**
     * Writes the event that ends a query's feed.
     *
     * @param query     the query's label
     * @param delivered how many rows it delivered
     * @param elapsed   how long it took
     */
    public void delivered(String query, long delivered, Duration elapsed) {
        event(QueryEvent.of(QueryEvent.Stage.EXECUTE, "ROWS",
                        "query delivered " + delivered + " row" + (delivered == 1 ? "" : "s"), query)
                .withMetrics(EventMetrics.of(delivered, elapsed)), query);
    }

    /**
     * One event as a line of the feed, without its line break.
     *
     * @param event the event
     * @return the line
     */
    public static String line(QueryEvent event) {
        String target = event.target().map(t -> "  " + t).orElse("");
        return String.format(Locale.ROOT, "[%-8s]%s  %s  %s%s",
                event.stage(), target, event.code(), event.description(), elapsed(event));
    }

    /**
     * The time an event measured, as a trailing {@code (12.3 ms)}, or nothing for an
     * event that measured none. It is rendered from the event's metrics rather than its
     * description, which stays free of wall-clock readings a test could not assert on.
     */
    private static String elapsed(QueryEvent event) {
        return event.metrics().duration()
                .map(d -> String.format(Locale.ROOT, "  (%.1f ms)", d.toNanos() / 1_000_000.0))
                .orElse("");
    }

    /** Flushes the feed, and closes it when it is a file's. */
    @Override
    public void close() {
        out.flush();
        if (owned) {
            out.close();
        }
    }

    private void write(String line) {
        out.print(line + "\n");
        out.flush();
        if (stdout && out.checkError()) {
            throw new CommandFailure(ExitCode.PIPE_CLOSED, null);
        }
    }
}
