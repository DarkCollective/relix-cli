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

import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.processor.provenance.Annotated;
import com.darkcollective.relix.processor.provenance.AnnotatedRelation;
import com.darkcollective.relix.value.NullValue;
import com.darkcollective.relix.value.StringValue;
import com.darkcollective.relix.provenance.BooleanSemiring;
import com.darkcollective.relix.provenance.Polynomial;
import com.darkcollective.relix.provenance.PolynomialSemiring;
import com.darkcollective.relix.provenance.SourceRef;
import com.darkcollective.relix.symbol.ColumnDefinition;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.Schema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ProvenanceOutput")
final class ProvenanceOutputTest {

    private static final Schema SCHEMA = new Schema(List.of(
            new ColumnDefinition("region", ScalarType.STRING)));

    private static String json(AnnotatedRelation<?> rel, String semiring) {
        StringWriter sw = new StringWriter();
        try (PrintWriter pw = new PrintWriter(sw)) {
            ProvenanceOutput.writeJson(pw, "Q", rel, semiring);
        }
        return sw.toString();
    }

    private static Row row(String region) {
        return Row.of(SCHEMA, region == null ? NullValue.INSTANCE : new StringValue(region));
    }

    @Test
    @DisplayName("a lineage Polynomial is emitted as a structured monomials/variables object")
    void structuredLineage() {
        TreeMap<String, String> cols = new TreeMap<>();
        cols.put("id", "42");
        Polynomial p = Polynomial.variable(new SourceRef("Orders", 1, cols));
        var rel = AnnotatedRelation.normalise(SCHEMA, PolynomialSemiring.INSTANCE,
                Stream.of(new Annotated<>(row("west"), p)));

        String out = json(rel, "lineage");
        assertThat(out)
                .contains("\"provenance\": {\"text\": \"Orders#1\"")
                .contains("\"truncated\": false")
                .contains("\"monomials\": [")
                .contains("\"coefficient\": 1")
                .contains("\"name\": \"Orders#1\"")
                .contains("\"exponent\": 1")
                .contains("\"relation\": \"Orders\"")
                .contains("\"ordinal\": 1")
                .contains("\"columns\": {\"id\": \"42\"}");
    }

    @Test
    @DisplayName("a SQL-null captured column renders as JSON null")
    void nullColumnIsJsonNull() {
        TreeMap<String, String> cols = new TreeMap<>();
        cols.put("note", null);
        Polynomial p = Polynomial.variable(new SourceRef("Orders", 1, cols));
        var rel = AnnotatedRelation.normalise(SCHEMA, PolynomialSemiring.INSTANCE,
                Stream.of(new Annotated<>(row("west"), p)));

        assertThat(json(rel, "lineage")).contains("\"columns\": {\"note\": null}");
    }

    @Test
    @DisplayName("a cheap semiring's annotation stays a plain JSON string")
    void cheapSemiringIsPlainString() {
        var rel = AnnotatedRelation.normalise(SCHEMA, BooleanSemiring.INSTANCE,
                Stream.of(new Annotated<>(row("west"), Boolean.TRUE)));

        assertThat(json(rel, "boolean")).contains("\"provenance\": \"true\"");
    }
}
