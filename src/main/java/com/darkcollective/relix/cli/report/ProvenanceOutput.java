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

import com.darkcollective.relix.cli.render.JsonText;
import com.darkcollective.relix.cli.render.OutputFormat;
import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.processor.provenance.Annotated;
import com.darkcollective.relix.processor.provenance.AnnotatedRelation;
import com.darkcollective.relix.value.Value;
import com.darkcollective.relix.provenance.Monomial;
import com.darkcollective.relix.provenance.Polynomial;
import com.darkcollective.relix.provenance.ProvenanceVariable;
import com.darkcollective.relix.provenance.SourceRef;
import com.darkcollective.relix.symbol.ColumnDefinition;

import java.io.PrintWriter;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Renders an annotated {@link AnnotatedRelation K-relation} — how a provenance-annotated
 * result is shown to a person or handed to a tool.
 *
 * <p>Two renderings are offered, selected by the CLI's {@code --format}:
 * <ul>
 *   <li>{@link #writeHuman human} — an aligned ASCII table with a clearly-marked
 *       trailing {@code [prov]} column and a footer naming the semiring;</li>
 *   <li>{@link #writeJson machine} — a JSON object whose {@code tuples} pair each
 *       {@code row} with its {@code provenance} under a distinct key.</li>
 * </ul>
 *
 * <p>In both forms the annotation is a <strong>side-channel</strong>, never an ordinary
 * data column: it describes where a row came from rather than what is in it, and a
 * consumer that read it as data would be reading a column the relation does not have.
 * The {@code [prov]} header and the separate {@code provenance} JSON key are what keep it
 * un-confusable with the schema.
 *
 * <p>For the cheap semirings (a count, a boolean, a cost, or a trust level) the
 * annotation is rendered with {@link String#valueOf}. The lineage semiring's
 * {@link Polynomial} is instead emitted <strong>structurally</strong> in JSON — a
 * {@code monomials} array, each with its {@code coefficient} and {@code variables},
 * and each variable carrying its {@link SourceRef structured source} (relation,
 * ordinal, and the captured base-tuple {@code columns}). This is what lets a tool
 * extract enough lineage to locate the exact source rows behind a result tuple.
 */
public final class ProvenanceOutput {

    private static final String PROV_HEADER = "[prov]";

    private ProvenanceOutput() {
    }

    /** Writes the human-readable aligned table, with a {@code [prov]} column and semiring footer. */
    public static <K> void writeHuman(PrintWriter out, String label, AnnotatedRelation<K> relation,
                               String semiringName) {
        List<ColumnDefinition> cols = relation.schema().columns();
        int dataCols = cols.size();

        // Header cells: the schema column names, then the provenance column.
        List<String> header = new ArrayList<>(dataCols + 1);
        for (ColumnDefinition col : cols) {
            header.add(col.name());
        }
        header.add(PROV_HEADER);

        // Body cells, row by row.
        List<List<String>> body = new ArrayList<>(relation.size());
        for (Annotated<K> tuple : (Iterable<Annotated<K>>) relation.stream()::iterator) {
            Row row = tuple.row();
            List<String> cells = new ArrayList<>(dataCols + 1);
            for (int i = 0; i < dataCols; i++) {
                Value v = row.get(i);
                cells.add(v.isNull() ? "" : v.asDisplayString());
            }
            cells.add(String.valueOf(tuple.annotation()));
            body.add(cells);
        }

        int[] widths = new int[header.size()];
        for (int i = 0; i < header.size(); i++) {
            widths[i] = header.get(i).length();
        }
        for (List<String> cells : body) {
            for (int i = 0; i < cells.size(); i++) {
                widths[i] = Math.max(widths[i], cells.get(i).length());
            }
        }

        out.println(label);
        out.println(rule(widths));
        out.println(rowLine(header, widths));
        out.println(rule(widths));
        for (List<String> cells : body) {
            out.println(rowLine(cells, widths));
        }
        out.println(rule(widths));
        out.printf("(%d tuple%s; provenance semiring: %s)%n",
                relation.size(), relation.size() == 1 ? "" : "s", semiringName);
    }

    /** Writes the machine-readable JSON object: schema, then {@code {row, provenance}} tuples. */
    public static <K> void writeJson(PrintWriter out, String label, AnnotatedRelation<K> relation,
                              String semiringName) {
        List<ColumnDefinition> cols = relation.schema().columns();
        out.print("{\n  \"query\": " + JsonText.quote(label));
        out.print(",\n  \"semiring\": " + JsonText.quote(semiringName));

        StringBuilder schema = new StringBuilder();
        for (int i = 0; i < cols.size(); i++) {
            if (i > 0) schema.append(", ");
            schema.append(JsonText.quote(cols.get(i).name()));
        }
        out.print(",\n  \"schema\": [" + schema + "]");

        out.print(",\n  \"tuples\": [");
        boolean first = true;
        for (Annotated<K> tuple : (Iterable<Annotated<K>>) relation.stream()::iterator) {
            out.print(first ? "\n    " : ",\n    ");
            first = false;
            Row row = tuple.row();
            StringBuilder obj = new StringBuilder("{\"row\": {");
            for (int i = 0; i < cols.size(); i++) {
                if (i > 0) obj.append(", ");
                obj.append(JsonText.quote(cols.get(i).name()))
                        .append(": ").append(OutputFormat.jsonValue(row.get(i)));
            }
            // The annotation rides alongside the row, under its own key — never inside "row".
            obj.append("}, \"provenance\": ")
                    .append(jsonProvenance(tuple.annotation()))
                    .append('}');
            out.print(obj);
        }
        out.print(first ? "]\n}\n" : "\n  ]\n}\n");
    }

    /**
     * Renders one tuple's annotation as a JSON value: a {@link Polynomial} as a
     * structured lineage object (see {@link #jsonPolynomial}); any other semiring's
     * annotation as a plain JSON string.
     */
    private static <K> String jsonProvenance(K annotation) {
        return annotation instanceof Polynomial p
                ? jsonPolynomial(p)
                : JsonText.quote(String.valueOf(annotation));
    }

    /**
     * Renders a lineage {@link Polynomial} as a structured JSON object: its compact
     * {@code text} rendering, the {@code truncated} flag, and a {@code monomials}
     * array pairing each derivation's {@code coefficient} with its {@code variables}.
     */
    private static String jsonPolynomial(Polynomial poly) {
        StringBuilder sb = new StringBuilder("{\"text\": ")
                .append(JsonText.quote(poly.toString()))
                .append(", \"truncated\": ").append(poly.truncated())
                .append(", \"monomials\": [");
        boolean firstTerm = true;
        for (Map.Entry<Monomial, BigInteger> term : poly.terms().entrySet()) {
            if (!firstTerm) sb.append(", ");
            firstTerm = false;
            sb.append("{\"coefficient\": ").append(term.getValue())
              .append(", \"variables\": [");
            boolean firstVar = true;
            for (Map.Entry<ProvenanceVariable, Integer> ve : term.getKey().exponents().entrySet()) {
                if (!firstVar) sb.append(", ");
                firstVar = false;
                sb.append(jsonVariable(ve.getKey(), ve.getValue()));
            }
            sb.append("]}");
        }
        return sb.append("]}").toString();
    }

    /**
     * Renders one provenance variable as a JSON object: its {@code name}, its
     * {@code exponent} within the monomial, and — when present — the structured
     * {@code source} (relation, ordinal, and captured base-tuple columns).
     */
    private static String jsonVariable(ProvenanceVariable variable, int exponent) {
        StringBuilder sb = new StringBuilder("{\"name\": ")
                .append(JsonText.quote(variable.name()))
                .append(", \"exponent\": ").append(exponent);
        variable.source().ifPresent(ref -> sb.append(", \"source\": ").append(jsonSource(ref)));
        return sb.append('}').toString();
    }

    /** Renders a {@link SourceRef} as a JSON object: relation, ordinal, and captured columns. */
    private static String jsonSource(SourceRef ref) {
        StringBuilder sb = new StringBuilder("{\"relation\": ")
                .append(JsonText.quote(ref.source()))
                .append(", \"ordinal\": ").append(ref.ordinal())
                .append(", \"columns\": {");
        boolean first = true;
        for (Map.Entry<String, String> col : ref.columns().entrySet()) {
            if (!first) sb.append(", ");
            first = false;
            sb.append(JsonText.quote(col.getKey())).append(": ")
              .append(col.getValue() == null ? "null" : JsonText.quote(col.getValue()));
        }
        return sb.append("}}").toString();
    }

    private static String rowLine(List<String> cells, int[] widths) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) sb.append("  ");
            sb.append(pad(cells.get(i), widths[i]));
        }
        return sb.toString().stripTrailing();
    }

    private static String rule(int[] widths) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < widths.length; i++) {
            if (i > 0) sb.append("  ");
            sb.append("-".repeat(widths[i]));
        }
        return sb.toString();
    }

    private static String pad(String s, int width) {
        return s.length() >= width ? s : s + " ".repeat(width - s.length());
    }
}
