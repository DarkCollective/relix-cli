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
import com.darkcollective.relix.cli.io.Host;
import com.darkcollective.relix.cli.report.EventFeed;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * {@code run --trace[=FILE]}: the event feed, written beside the rows (design §4).
 *
 * <p>The file is attached with {@code =} only, so that {@code relix --trace q.relix} traces
 * {@code q.relix} rather than writing its trace over it; {@code Main} makes a bare
 * {@code --trace} an empty value before the line is parsed.
 */
final class TraceOption {

    /** The option's name. */
    static final String NAME = "--trace";

    @Option(names = NAME, paramLabel = "FILE",
            description = {
                "Write the event feed (what was rewritten, planned and run) to standard",
                "error, or to FILE with --trace=FILE, while the rows go to standard output."})
    String trace;

    /**
     * Where the feed goes, if anywhere.
     *
     * @param host      the process, for standard error
     * @param directory where a relative FILE is
     * @return the feed, or {@code null} without {@code --trace}; a file is opened here, and
     *         closing the feed closes it
     */
    EventFeed open(Host host, Path directory) {
        if (trace == null) {
            return null;
        }
        if (trace.isEmpty()) {
            return EventFeed.stderr(host.err());
        }
        Path file = directory.resolve(trace);
        try {
            return EventFeed.file(Files.newOutputStream(file));
        } catch (IOException e) {
            throw new CommandFailure(ExitCode.USAGE, NAME + "=" + trace + ": " + e.getMessage());
        }
    }
}
