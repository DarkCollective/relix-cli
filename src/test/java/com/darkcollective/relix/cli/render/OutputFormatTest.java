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
package com.darkcollective.relix.cli.render;

import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.value.ArrayValue;
import com.darkcollective.relix.value.BooleanValue;
import com.darkcollective.relix.value.NullValue;
import com.darkcollective.relix.value.NumberValue;
import com.darkcollective.relix.value.StringValue;
import com.darkcollective.relix.value.StructValue;
import com.darkcollective.relix.value.Value;
import com.darkcollective.relix.symbol.ColumnDefinition;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.Schema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("OutputFormat — streaming CSV / JSON / Markdown writers")
final class OutputFormatTest {

    private static final Schema SCHEMA = new Schema(List.of(
            new ColumnDefinition("id", ScalarType.NUMBER),
            new ColumnDefinition("name", ScalarType.STRING)));

    private static String render(OutputFormat format, Schema schema, List<Row> rows) {
        StringWriter sw = new StringWriter();
        try (PrintWriter pw = new PrintWriter(sw)) {
            format.write(pw, "R", schema, rows.stream());
        }
        return sw.toString();
    }

    private static Row row(Schema schema, Value... values) {
        return Row.of(schema, values);
    }

    // ── fromString ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("fromString")
    class FromString {

        @Test
        @DisplayName("resolves names case-insensitively")
        void caseInsensitive() {
            assertThat(OutputFormat.fromString("csv")).isEqualTo(OutputFormat.CSV);
            assertThat(OutputFormat.fromString("JSON")).isEqualTo(OutputFormat.JSON);
            assertThat(OutputFormat.fromString("Markdown")).isEqualTo(OutputFormat.MARKDOWN);
            assertThat(OutputFormat.fromString("table")).isEqualTo(OutputFormat.TABLE);
        }

        @Test
        @DisplayName("rejects an unknown format")
        void unknownThrows() {
            assertThatThrownBy(() -> OutputFormat.fromString("xml"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("xml")
                    .hasMessageContaining("csv");
        }
    }

    // ── CSV ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("CSV")
    class Csv {

        @Test
        @DisplayName("writes a header row then one quoted-as-needed row per record")
        void headerAndRows() {
            String out = render(OutputFormat.CSV, SCHEMA, List.of(
                    row(SCHEMA, NumberValue.of("1"), new StringValue("Alice")),
                    row(SCHEMA, NumberValue.of("2"), new StringValue("Bob, Jr."))));

            assertThat(out).isEqualTo("""
                    id,name
                    1,Alice
                    2,"Bob, Jr."
                    """);
        }

        @Test
        @DisplayName("escapes embedded quotes and renders NULL as an empty field")
        void quotesAndNulls() {
            String out = render(OutputFormat.CSV, SCHEMA, List.of(
                    row(SCHEMA, NumberValue.of("1"), new StringValue("a\"b")),
                    row(SCHEMA, NumberValue.of("2"), NullValue.INSTANCE)));

            assertThat(out).contains("1,\"a\"\"b\"");
            assertThat(out).contains("2,\n");   // NULL → empty trailing field
        }
    }

    // ── JSON ────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("JSON")
    class Json {

        @Test
        @DisplayName("emits an array of objects with bare numbers and quoted strings")
        void arrayOfObjects() {
            String out = render(OutputFormat.JSON, SCHEMA, List.of(
                    row(SCHEMA, NumberValue.of("1"), new StringValue("Alice")),
                    row(SCHEMA, NumberValue.of("2"), new StringValue("Bob"))));

            assertThat(out).isEqualTo("""
                    [
                      {"id":1,"name":"Alice"},
                      {"id":2,"name":"Bob"}
                    ]
                    """);
        }

        @Test
        @DisplayName("renders booleans and null as bare JSON literals")
        void booleansAndNull() {
            Schema schema = new Schema(List.of(
                    new ColumnDefinition("flag", ScalarType.ANY),
                    new ColumnDefinition("note", ScalarType.STRING)));
            String out = render(OutputFormat.JSON, schema, List.of(
                    row(schema, BooleanValue.of(true), NullValue.INSTANCE)));

            assertThat(out).contains("{\"flag\":true,\"note\":null}");
        }

        @Test
        @DisplayName("renders nested struct and array values as native JSON")
        void nestedValues() {
            Schema schema = new Schema(List.of(new ColumnDefinition("doc", ScalarType.ANY)));
            java.util.LinkedHashMap<String, Value> fields = new java.util.LinkedHashMap<>();
            fields.put("name", new StringValue("Alice"));
            fields.put("tags", new ArrayValue(List.of(new StringValue("x"), NumberValue.of("1"))));

            String out = render(OutputFormat.JSON, schema, List.of(row(schema, new StructValue(fields))));

            assertThat(out).contains("{\"doc\":{\"name\":\"Alice\",\"tags\":[\"x\",1]}}");
        }

        @Test
        @DisplayName("escapes control characters and quotes in strings")
        void escaping() {
            String out = render(OutputFormat.JSON, SCHEMA, List.of(
                    row(SCHEMA, NumberValue.of("1"), new StringValue("a\"b\n\tc"))));
            assertThat(out).contains("\"name\":\"a\\\"b\\n\\tc\"");
        }

        @Test
        @DisplayName("escapes the full set of JSON control characters")
        void escapesAllControlChars() {
            String out = render(OutputFormat.JSON, SCHEMA, List.of(
                    row(SCHEMA, NumberValue.of("1"),
                            new StringValue("\\\r\b\f\u0001"))));
            assertThat(out).contains("\"name\":\"\\\\\\r\\b\\f\\u0001\"");
        }

        @Test
        @DisplayName("empty result is an empty array")
        void emptyArray() {
            assertThat(render(OutputFormat.JSON, SCHEMA, List.of())).isEqualTo("[]\n");
        }
    }

    // ── Markdown ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Markdown")
    class Markdown {

        @Test
        @DisplayName("emits a heading and a GitHub-flavoured table")
        void headingAndTable() {
            String out = render(OutputFormat.MARKDOWN, SCHEMA, List.of(
                    row(SCHEMA, NumberValue.of("1"), new StringValue("Alice"))));

            assertThat(out).isEqualTo("""
                    ### R

                    | id | name |
                    | --- | --- |
                    | 1 | Alice |

                    """);
        }

        @Test
        @DisplayName("escapes pipes in cell values")
        void escapesPipes() {
            String out = render(OutputFormat.MARKDOWN, SCHEMA, List.of(
                    row(SCHEMA, NumberValue.of("1"), new StringValue("a|b"))));
            assertThat(out).contains("| 1 | a\\|b |");
        }
    }
}
