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
import com.darkcollective.relix.cli.report.EventFeed;
import com.darkcollective.relix.embed.Relation;
import com.darkcollective.relix.embed.Tuple;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.time.Duration;
import java.util.stream.Stream;

/**
 * {@code relix trace}: runs each query for its event feed, what the engine rewrote,
 * planned and ran, and discards its rows (design §3). {@code relix run --trace} writes the
 * same feed beside the rows.
 *
 * <p>The rows are pulled and dropped one at a time, never collected, so tracing a large
 * result costs no more memory than running it.
 */
@Command(name = "trace",
        mixinStandardHelpOptions = true,
        sortOptions = false,
        usageHelpAutoWidth = true,
        description = {
            "Runs each query and prints its event feed, what the engine rewrote, planned",
            "and ran, discarding the rows. relix run --trace keeps the rows too."})
final class TraceCommand extends ScriptCommand {

    @Option(names = "--query", paramLabel = "NAME",
            description = "Trace only the query NAME.")
    String query;

    @Override
    ScriptRunner.View view(Invocation invocation) {
        EventFeed feed = EventFeed.stdout(invocation.host().out());
        Interruption interruption = root.interruption();
        return script -> {
            for (Relation relation : script.queries(query)) {
                String label = relation.label().orElse("query");
                long start = System.nanoTime();
                long delivered = 0;
                try (Stream<Tuple> rows = relation.stream(event -> feed.event(event, label))) {
                    interruption.stream(rows);
                    for (var it = rows.iterator(); it.hasNext(); it.next()) {
                        delivered++;
                    }
                } finally {
                    interruption.stream(null);
                }
                feed.delivered(label, delivered, Duration.ofNanos(System.nanoTime() - start));
            }
            return ExitCode.SUCCESS;
        };
    }
}
