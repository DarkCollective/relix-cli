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
import com.darkcollective.relix.cli.io.RowSink;
import com.darkcollective.relix.cli.report.ProvenanceOutput;
import com.darkcollective.relix.embed.Relation;
import com.darkcollective.relix.processor.provenance.AnnotatedRelation;
import com.darkcollective.relix.provenance.Semiring;
import com.darkcollective.relix.provenance.Semirings;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Locale;

/**
 * {@code relix provenance}: each query's rows, each annotated with where it came from in a
 * semiring of the user's choice (design §3).
 *
 * <p>The annotation is never a data column. On a terminal it is a trailing {@code [prov]}
 * column under a footer naming the semiring; in a pipe, or with {@code -o json}, it is a
 * JSON document in which each tuple carries its {@code row} and its {@code provenance}
 * under separate keys.
 */
@Command(name = "provenance",
        mixinStandardHelpOptions = true,
        sortOptions = false,
        usageHelpAutoWidth = true,
        description = {
            "Runs each query and prints its rows, each annotated with where it came from",
            "in a provenance semiring."})
final class ProvenanceCommand extends ScriptCommand {

    @Option(names = "--semiring", paramLabel = "NAME", defaultValue = "counting",
            description = {
                "The semiring: boolean, counting, tropical, security, lineage, cheapest-route,",
                "or one an installed library offers (default: ${DEFAULT-VALUE})."})
    String semiring;

    @Option(names = "--weight", paramLabel = "COLUMN",
            description = {
                "The column a weighted closure takes each edge's weight from, such as",
                "--semiring tropical --weight cost for shortest paths."})
    String weight;

    @Option(names = {"-o", "--output"}, paramLabel = "FORMAT",
            description = "table or json (default: table on a terminal, json in a pipe).")
    String format;

    @Option(names = "--query", paramLabel = "NAME",
            description = "Annotate only the query NAME.")
    String query;

    @Override
    ScriptRunner.View view(Invocation invocation) {
        Semiring<?> chosen = Semirings.byName(semiring).orElseThrow(() -> new CommandFailure(ExitCode.USAGE,
                "--semiring: '" + semiring + "' is not one of " + String.join(", ", Semirings.names())));
        boolean json = json(invocation);
        RowSink out = stdout(invocation);
        return script -> {
            StringWriter text = new StringWriter();
            PrintWriter writer = new PrintWriter(text);
            for (Relation relation : script.queries(query)) {
                write(writer, json, relation, chosen);
            }
            writer.flush();
            out.text(text.toString());
            return ExitCode.SUCCESS;
        };
    }

    private boolean json(Invocation invocation) {
        if (format == null) {
            return !invocation.host().stdoutIsTerminal();
        }
        return switch (format.strip().toLowerCase(Locale.ROOT)) {
            case "table" -> false;
            case "json" -> true;
            default -> throw new CommandFailure(ExitCode.USAGE, "-o: '" + format + "' is not one of table, json");
        };
    }

    /** Captures the semiring's annotation type. */
    private <K> void write(PrintWriter out, boolean json, Relation relation, Semiring<K> semiring) {
        String label = relation.label().orElse("<expression>");
        AnnotatedRelation<K> annotated = weight == null
                ? relation.provenance(semiring)
                : relation.provenance(semiring, weight);
        if (json) {
            ProvenanceOutput.writeJson(out, label, annotated, this.semiring);
        } else {
            ProvenanceOutput.writeHuman(out, label, annotated, this.semiring);
        }
    }
}
