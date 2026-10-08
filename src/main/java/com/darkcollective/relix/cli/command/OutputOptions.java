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
import com.darkcollective.relix.cli.config.Relixrc;
import com.darkcollective.relix.cli.io.Host;
import com.darkcollective.relix.cli.render.OutputFormat;
import picocli.CommandLine.Option;

import java.util.Locale;
import java.util.Optional;

/**
 * Where a run's rows go and in what shape (design §3.3, §3.4, §3.6): the format, its
 * modifiers, which queries a machine format prints, and the assertions on what they
 * return.
 */
final class OutputOptions {

    static final String OUTPUT_VARIABLE = "RELIX_OUTPUT";
    static final String NO_COLOR_VARIABLE = "NO_COLOR";

    @Option(names = {"-o", "--output"}, paramLabel = "FORMAT",
            description = {
                "The rows' format: table, tsv, csv, ndjson, json or markdown (default:",
                "$RELIX_OUTPUT, else relixrc's output, else table on a terminal and tsv in a pipe)."})
    String format;

    @Option(names = "--no-header",
            description = "Leave out the header row of csv and tsv.")
    boolean noHeader;

    @Option(names = "--null", paramLabel = "STRING",
            description = "How NULL is written (default: empty in csv, tsv and markdown; NULL in a table).")
    String nullText;

    @Option(names = "--color", paramLabel = "WHEN", defaultValue = "auto",
            description = {
                "Colour a table: auto, always or never (default: ${DEFAULT-VALUE}).",
                "auto colours on a terminal unless $NO_COLOR is set."})
    String color;

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
        OutputFormat chosen = format(host, fallback);
        boolean colored = switch (color.toLowerCase(Locale.ROOT)) {
            case "always" -> true;
            case "never" -> false;
            case "auto" -> host.stdoutIsTerminal() && host.variable(NO_COLOR_VARIABLE).isEmpty();
            default -> throw new CommandFailure(ExitCode.USAGE,
                    "--color: '" + color + "' is not one of auto, always, never");
        };
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
        return new Output(chosen,
                new OutputFormat.Options(!noHeader, nullText, colored, all && chosen == OutputFormat.NDJSON),
                lineBuffered, query, all || !chosen.isMachineFormat(), assertion);
    }

    private OutputFormat format(Host host, Optional<String> fallback) {
        if (format != null) {
            return format("-o", format);
        }
        return host.variable(OUTPUT_VARIABLE)
                .map(name -> format("$" + OUTPUT_VARIABLE, name))
                .or(() -> fallback.map(name -> format(Relixrc.FILE_NAME + " " + Relixrc.OUTPUT, name)))
                .orElse(host.stdoutIsTerminal() ? OutputFormat.TABLE : OutputFormat.TSV);
    }

    private static OutputFormat format(String from, String name) {
        try {
            return OutputFormat.fromString(name.strip());
        } catch (IllegalArgumentException e) {
            throw new CommandFailure(ExitCode.USAGE, from + ": " + e.getMessage());
        }
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
