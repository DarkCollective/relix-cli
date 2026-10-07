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

import com.darkcollective.relix.events.QueryEvent;
import com.darkcollective.relix.optimizer.TransformationRecord;
import com.darkcollective.relix.semantic.SemanticError;
import com.darkcollective.relix.semantic.SemanticModel;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.ObjectWriteContext;
import tools.jackson.core.json.JsonFactory;

import java.io.StringWriter;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Assembles the machine-readable <em>query bundle</em> — the single JSON document
 * the playground (and any other tooling) consumes for one analysis run.
 *
 * <p>This is the convergence point for the per-module JSON serializers: it ties
 * together the logical plan ({@code Relation.renderJson()}), the physical plan
 * ({@code Relation.explainJson()}), the optimizer's rule firings, the planner/optimizer
 * event feed, and the analysis diagnostics into one structure — machine-readable
 * and schema-bearing.
 *
 * <h2>Shape</h2>
 * <pre>{@code
 * {
 *   "namespace": "default",
 *   "diagnostics": [ { "severity", "message", "file", "line", "column" }, … ],
 *   "queries": [
 *     {
 *       "label": "Big",
 *       "logicalPlan":  { … } | null,     // LogicalPlanJson node tree
 *       "optimizations": [ { "code", "target", "detail" }, … ],
 *       "physicalPlan": { … } | null,     // PhysicalPlanJson node tree
 *       "events": [ { "stage", "code", "description", "target" }, … ]
 *     }, …
 *   ]
 * }
 * }</pre>
 *
 * <p>This class is a pure function of its inputs — it performs no analysis,
 * planning, or execution — so it is trivially testable and reusable beyond the
 * CLI.  The {@code result} (row data) section of the playground contract is not
 * assembled here; it is produced separately by the execution path.
 */
public final class QueryBundleJson {

    private static final JsonFactory JSON = new JsonFactory();

    private QueryBundleJson() {}

    /**
     * One query's contribution to the bundle.
     *
     * @param label         the query's display label
     * @param logical       the written tree as JSON ({@code Relation.renderJson()}),
     *                      or null if unavailable
     * @param optimizations the optimizer rule firings for this query; never null
     * @param physical      the physical plan as JSON ({@code Relation.explainJson()}),
     *                      or null if planning was skipped or failed
     * @param events        the optimizer/planner events for this query; never null
     */
    public record Query(
            String label,
            String logical,
            List<TransformationRecord> optimizations,
            String physical,
            List<QueryEvent> events
    ) {
        public Query {
            Objects.requireNonNull(label, "label");
            optimizations = List.copyOf(optimizations);
            events = List.copyOf(events);
        }
    }

    /**
     * Builds the bundle JSON for one analysis run.
     *
     * @param model       the semantic model (supplies namespace, symbol table, and
     *                    per-node schema annotations); must not be null
     * @param diagnostics the analysis diagnostics; never null (may be empty)
     * @param queries     the per-query contributions; never null (may be empty)
     * @return the bundle JSON document
     */
    public static String generate(SemanticModel model,
                                  List<SemanticError> diagnostics,
                                  List<Query> queries) {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(diagnostics, "diagnostics");
        Objects.requireNonNull(queries, "queries");

        StringWriter out = new StringWriter();
        try (JsonGenerator w = JSON.createGenerator(ObjectWriteContext.empty(), out)) {
            write(w, model, diagnostics, queries);
        }
        return out.toString();
    }

    private static void write(JsonGenerator w, SemanticModel model,
                              List<SemanticError> diagnostics, List<Query> queries) {
        w.writeStartObject();
        w.writeName("namespace").writeString(model.namespace());

        w.writeName("diagnostics").writeStartArray();
        for (SemanticError diagnostic : diagnostics) {
            writeDiagnostic(w, diagnostic);
        }
        w.writeEndArray();

        w.writeName("queries").writeStartArray();
        for (Query query : queries) {
            writeQuery(w, model, query);
        }
        w.writeEndArray();

        w.writeEndObject();
    }

    private static void writeQuery(JsonGenerator w, SemanticModel model, Query query) {
        w.writeStartObject();
        w.writeName("label").writeString(query.label());

        w.writeName("logicalPlan");
        if (query.logical() != null) {
            w.writeRawValue(query.logical());
        } else {
            w.writeNull();
        }

        w.writeName("optimizations").writeStartArray();
        for (TransformationRecord record : query.optimizations()) {
            w.writeStartObject()
                    .writeName("code").writeString(record.code().code())
                    .writeName("target").writeString(record.relationName())
                    .writeName("detail").writeString(record.detail())
                    .writeEndObject();
        }
        w.writeEndArray();

        w.writeName("physicalPlan");
        if (query.physical() != null) {
            w.writeRawValue(query.physical());
        } else {
            w.writeNull();
        }

        w.writeName("events").writeStartArray();
        for (QueryEvent event : query.events()) {
            w.writeStartObject()
                    .writeName("stage").writeString(event.stage().name())
                    .writeName("code").writeString(event.code())
                    .writeName("description").writeString(event.description());
            w.writeName("target");
            event.target().ifPresentOrElse(w::writeString, w::writeNull);
            w.writeEndObject();
        }
        w.writeEndArray();

        w.writeEndObject();
    }

    private static void writeDiagnostic(JsonGenerator w, SemanticError diagnostic) {
        w.writeStartObject()
                .writeName("severity").writeString(diagnostic.severity().name().toLowerCase(Locale.ROOT))
                .writeName("message").writeString(diagnostic.message())
                .writeName("file").writeString(diagnostic.filePath())
                .writeName("line").writeNumber(diagnostic.line())
                .writeName("column").writeNumber(diagnostic.column())
                .writeEndObject();
    }
}
