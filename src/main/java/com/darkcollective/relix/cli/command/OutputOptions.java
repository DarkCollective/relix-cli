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
import com.darkcollective.relix.cli.render.OutputFormat;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import java.util.Optional;

/**
 * Where a run's rows go and in what shape (design §3.3, §3.4, §3.6): the format, its
 * modifiers, which queries a machine format prints, and the assertions on what they
 * return.
 */
final class OutputOptions {

    @Mixin
    FormatOptions formatting = new FormatOptions();

    @Option(names = "--line-buffered",
            description = "Flush each row as it is written, for a reader that wants it at once.")
    boolean lineBuffered;

    @Option(names = "--query", paramLabel = "NAME",
            description = {
                "Print only the query NAME. A machine format (tsv, csv, ndjson, json) prints",
                "one query: the script's last, unless this names another."})
    String query;

    @Option(names = "--all",
            description = "With ndjson, print every query, each row tagged \"_query\": NAME.")
    boolean all;

    @Option(names = "--fail-empty",
            description = "Exit 1 when the queries printed return no rows.")
    boolean failEmpty;

    @Option(names = "--fail-rows",
            description = "Exit 1 when the queries printed return any row.")
    boolean failRows;

    /**
     * The output these options and the host settle on.
     *
     * @param host     the process, for its environment and whether standard output is a terminal
     * @param fallback the format a {@code relixrc} names, when one does
     * @return the settled output
     * @throws CommandFailure with {@link ExitCode#USAGE} for an unknown format or colour,
     *                        or options that cannot go together
     */
    Output settle(Host host, Optional<String> fallback) {
        FormatOptions.Format settled = formatting.settle(host, fallback);
        OutputFormat chosen = settled.format();
        if (all && query != null) {
            throw new CommandFailure(ExitCode.USAGE, "--all and --query cannot be given together");
        }
        if (all && chosen.isMachineFormat() && chosen != OutputFormat.NDJSON) {
            throw new CommandFailure(ExitCode.USAGE, "--all prints several queries, which "
                    + chosen.optionName() + " cannot hold; use -o ndjson, or --query=NAME for one");
        }
        if (failEmpty && failRows) {
            throw new CommandFailure(ExitCode.USAGE, "--fail-empty and --fail-rows cannot be given together");
        }
        Assertion assertion = failEmpty ? Assertion.FAIL_EMPTY : failRows ? Assertion.FAIL_ROWS : Assertion.NONE;
        OutputFormat.Options options = settled.options();
        return new Output(chosen,
                new OutputFormat.Options(options.header(), options.nullText(), options.color(),
                        all && chosen == OutputFormat.NDJSON),
                lineBuffered, query, all || !chosen.isMachineFormat(), assertion);
    }

    /** What the rows printed must be for the run to succeed. */
    enum Assertion {
        /** Nothing is asserted. */
        NONE,
        /** {@code --fail-empty}: at least one row. */
        FAIL_EMPTY,
        /** {@code --fail-rows}: no row at all. */
        FAIL_ROWS;

        /**
         * Whether {@code rows} rows printed fail this assertion.
         *
         * @param rows how many rows were printed in all
         * @return {@code true} when the run should exit 1
         */
        boolean failedBy(long rows) {
            return switch (this) {
                case NONE -> false;
                case FAIL_EMPTY -> rows == 0;
                case FAIL_ROWS -> rows > 0;
            };
        }
    }

    /**
     * A run's output, settled.
     *
     * @param format       the format
     * @param options      its modifiers
     * @param lineBuffered whether each row is flushed as it is written
     * @param query        the one query to print, or {@code null}
     * @param every        whether every query is printed, rather than the last
     * @param assertion    what the printed rows must be
     */
    record Output(OutputFormat format, OutputFormat.Options options, boolean lineBuffered,
                  String query, boolean every, Assertion assertion) {
    }
}
