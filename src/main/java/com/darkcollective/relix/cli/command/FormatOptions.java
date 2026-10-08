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
 * The shape rows are written in (design §3.3): the format and its modifiers. Every command
 * that prints rows takes these, the catalog's listings as well as a run's results.
 */
final class FormatOptions {

    static final String OUTPUT_VARIABLE = "RELIX_OUTPUT";
    static final String NO_COLOR_VARIABLE = "NO_COLOR";

    @Option(names = {"-o", "--output"}, paramLabel = "FORMAT",
            description = {
                "The rows' format: table, tsv, csv, ndjson, json or markdown",
                "(default: $RELIX_OUTPUT, else relixrc's, else table on a terminal, tsv in a pipe)."})
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

    /**
     * The format these options and the host settle on.
     *
     * @param host     the process, for its environment and whether standard output is a terminal
     * @param fallback the format a {@code relixrc} names, when one does
     * @return the format and its modifiers
     * @throws CommandFailure with {@link ExitCode#USAGE} for an unknown format or colour
     */
    Format settle(Host host, Optional<String> fallback) {
        OutputFormat chosen = format(host, fallback);
        boolean colored = switch (color.toLowerCase(Locale.ROOT)) {
            case "always" -> true;
            case "never" -> false;
            case "auto" -> host.stdoutIsTerminal() && host.variable(NO_COLOR_VARIABLE).isEmpty();
            default -> throw new CommandFailure(ExitCode.USAGE,
                    "--color: '" + color + "' is not one of auto, always, never");
        };
        return new Format(chosen, new OutputFormat.Options(!noHeader, nullText, colored, false));
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

    /**
     * Rows' format, settled.
     *
     * @param format  the format
     * @param options its modifiers
     */
    record Format(OutputFormat format, OutputFormat.Options options) {
    }
}
