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
import com.darkcollective.relix.cli.io.RowSink;
import com.darkcollective.relix.embed.Relation;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.ObjectWriteContext;
import tools.jackson.core.json.JsonFactory;

import java.io.StringWriter;
import java.util.List;

/**
 * {@code relix explain}: each query's physical plan, the tree the engine would run
 * (design §3).
 *
 * <p>As text, a script with several queries prints each plan under a {@code -- query NAME}
 * line. With {@code --json}, each query is one line of its own, {@code {"script", "query",
 * "plan"}}, so a stream of plans reads as NDJSON.
 */
@Command(name = "explain",
        mixinStandardHelpOptions = true,
        sortOptions = false,
        usageHelpAutoWidth = true,
        description = "Prints the physical plan of each query: how the engine would run it.")
final class ExplainCommand extends ScriptCommand {

    private static final JsonFactory JSON = new JsonFactory();

    @Option(names = "--json",
            description = "Write one JSON object per query, {\"script\", \"query\", \"plan\"}, one per line.")
    boolean json;

    @Option(names = "--query", paramLabel = "NAME",
            description = "Explain only the query NAME.")
    String query;

    @Override
    ScriptRunner.View view(Invocation invocation) {
        RowSink out = stdout(invocation);
        return script -> {
            List<Relation> queries = script.queries(query);
            StringBuilder text = new StringBuilder();
            for (Relation relation : queries) {
                if (json) {
                    text.append(line(script.source().name(), relation)).append('\n');
                } else {
                    if (queries.size() > 1) {
                        text.append("-- query ").append(relation.label().orElse("")).append('\n');
                    }
                    text.append(relation.explain().stripTrailing()).append('\n');
                    if (queries.size() > 1 && relation != queries.getLast()) {
                        text.append('\n');
                    }
                }
            }
            out.text(text.toString());
            return ExitCode.SUCCESS;
        };
    }

    private static String line(String script, Relation relation) {
        StringWriter out = new StringWriter();
        try (JsonGenerator w = JSON.createGenerator(ObjectWriteContext.empty(), out)) {
            w.writeStartObject();
            w.writeName("script").writeString(script);
            w.writeName("query");
            relation.label().ifPresentOrElse(w::writeString, w::writeNull);
            w.writeName("plan").writeRawValue(relation.explainJson());
            w.writeEndObject();
        }
        return out.toString();
    }
}
