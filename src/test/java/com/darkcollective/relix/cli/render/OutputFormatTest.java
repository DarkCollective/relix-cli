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
import com.darkcollective.relix.value.DateValue;
import com.darkcollective.relix.value.DurationValue;
import com.darkcollective.relix.value.NullValue;
import com.darkcollective.relix.value.NumberValue;
import com.darkcollective.relix.value.StringValue;
import com.darkcollective.relix.value.StructValue;
import com.darkcollective.relix.value.TimeValue;
import com.darkcollective.relix.value.TimestampValue;
import com.darkcollective.relix.value.Value;
import com.darkcollective.relix.symbol.ColumnDefinition;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.Schema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("OutputFormat — streaming TSV / CSV / NDJSON / JSON / Markdown writers")
final class OutputFormatTest {

    private static final Schema SCHEMA = new Schema(List.of(
            new ColumnDefinition("id", ScalarType.NUMBER),
            new ColumnDefinition("name", ScalarType.STRING)));

    /** A heading with one column of every type, and a row holding one value of each. */
    private static final Schema EVERY_TYPE = new Schema(List.of(
            new ColumnDefinition("n", ScalarType.NUMBER),
            new ColumnDefinition("s", ScalarType.STRING),
            new ColumnDefinition("b", ScalarType.BOOLEAN),
            new ColumnDefinition("d", ScalarType.DATE),
            new ColumnDefinition("t", ScalarType.TIME),
            new ColumnDefinition("ts", ScalarType.TIMESTAMP),
            new ColumnDefinition("dur", ScalarType.DURATION),
            new ColumnDefinition("doc", ScalarType.ANY),
            new ColumnDefinition("arr", ScalarType.ANY),
            new ColumnDefinition("none", ScalarType.STRING)));

    private static Row everyType() {
        LinkedHashMap<String, Value> fields = new LinkedHashMap<>();
        fields.put("name", new StringValue("Alice"));
        fields.put("tags", new ArrayValue(List.of(new StringValue("x"), NumberValue.of("1"))));
        return row(EVERY_TYPE,
                new NumberValue(new BigDecimal("-12.50")),
                new StringValue("plain"),
                BooleanValue.of(true),
                new DateValue(LocalDate.of(2026, 10, 8)),
                new TimeValue(LocalTime.of(9, 5, 30)),
                new TimestampValue(Instant.parse("2026-10-08T09:05:30Z")),
                new DurationValue(Duration.ofMinutes(90)),
                new StructValue(fields),
                new ArrayValue(List.of(NumberValue.of("1"), NullValue.INSTANCE)),
                NullValue.INSTANCE);
    }

    private static String render(OutputFormat format, Schema schema, List<Row> rows) {
        return render(format, schema, rows, OutputFormat.Options.DEFAULTS);
    }

    private static String render(OutputFormat format, Schema schema, List<Row> rows,
                                 OutputFormat.Options options) {
        StringWriter sw = new StringWriter();
        try (PrintWriter pw = new PrintWriter(sw)) {
            format.write(pw, "R", schema, rows.stream(), options);
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
            assertThat(OutputFormat.fromString("TSV")).isEqualTo(OutputFormat.TSV);
            assertThat(OutputFormat.fromString("ndjson")).isEqualTo(OutputFormat.NDJSON);
        }

        @Test
        @DisplayName("rejects an unknown format")
        void unknownThrows() {
            assertThatThrownBy(() -> OutputFormat.fromString("xml"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("xml")
                    .hasMessageContaining("table, tsv, csv, ndjson, json, markdown");
        }
    }

    // ── TSV ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("TSV")
    class Tsv {

        @Test
        @DisplayName("writes a header row then one tab-separated line per row")
        void headerAndRows() {
            String out = render(OutputFormat.TSV, SCHEMA, List.of(
                    row(SCHEMA, NumberValue.of("1"), new StringValue("Alice")),
                    row(SCHEMA, NumberValue.of("2"), new StringValue("Bob, Jr. \"B\""))));

            assertThat(out).isEqualTo("id\tname\n1\tAlice\n2\tBob, Jr. \"B\"\n");
        }

        @Test
        @DisplayName("writes every value type as its display text")
        void everyValueType() {
            assertThat(render(OutputFormat.TSV, EVERY_TYPE, List.of(everyType()))).isEqualTo(
                    "n\ts\tb\td\tt\tts\tdur\tdoc\tarr\tnone\n"
                    + "-12.5\tplain\ttrue\t2026-10-08\t09:05:30\t2026-10-08T09:05:30Z\tPT1H30M"
                    + "\t{name: Alice, tags: [x, 1]}\t[1, NULL]\t\n");
        }

        @Test
        @DisplayName("escapes a tab, a line feed, a carriage return and a backslash, so a row is one line")
        void escaping() {
            String out = render(OutputFormat.TSV, SCHEMA, List.of(
                    row(SCHEMA, NumberValue.of("1"), new StringValue("a\tb\nc\rd\\e"))));

            assertThat(out).isEqualTo("id\tname\n1\ta\\tb\\nc\\rd\\\\e\n");
            assertThat(out.lines()).hasSize(2);
        }

        @Test
        @DisplayName("escapes the header's names as it escapes values")
        void escapesHeader() {
            Schema schema = new Schema(List.of(new ColumnDefinition("a\tb", ScalarType.STRING)));
            assertThat(render(OutputFormat.TSV, schema, List.of())).isEqualTo("a\\tb\n");
        }

        @Test
        @DisplayName("writes NULL as an empty field, or as the spelling given, unescaped")
        void nulls() {
            List<Row> rows = List.of(row(SCHEMA, NumberValue.of("1"), NullValue.INSTANCE));

            assertThat(render(OutputFormat.TSV, SCHEMA, rows)).isEqualTo("id\tname\n1\t\n");
            assertThat(render(OutputFormat.TSV, SCHEMA, rows, new OutputFormat.Options(true, "\\N", false, false)))
                    .isEqualTo("id\tname\n1\t\\N\n");
        }

        @Test
        @DisplayName("leaves the header row out when asked")
        void noHeader() {
            String out = render(OutputFormat.TSV, SCHEMA,
                    List.of(row(SCHEMA, NumberValue.of("1"), new StringValue("Alice"))),
                    new OutputFormat.Options(false, null, false, false));

            assertThat(out).isEqualTo("1\tAlice\n");
        }

        @Test
        @DisplayName("an empty result is its header row alone")
        void empty() {
            assertThat(render(OutputFormat.TSV, SCHEMA, List.of())).isEqualTo("id\tname\n");
        }
    }

    // ── NDJSON ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("NDJSON")
    class Ndjson {

        @Test
        @DisplayName("writes one object per line, with no header")
        void objectPerLine() {
            String out = render(OutputFormat.NDJSON, SCHEMA, List.of(
                    row(SCHEMA, NumberValue.of("1"), new StringValue("Alice")),
                    row(SCHEMA, NumberValue.of("2"), new StringValue("Bob"))));

            assertThat(out).isEqualTo("""
                    {"id":1,"name":"Alice"}
                    {"id":2,"name":"Bob"}
                    """);
        }

        @Test
        @DisplayName("encodes every value type as the json format does, nested values natively")
        void everyValueType() {
            String out = render(OutputFormat.NDJSON, EVERY_TYPE, List.of(everyType()));

            assertThat(out).isEqualTo("{\"n\":-12.5,\"s\":\"plain\",\"b\":true,\"d\":\"2026-10-08\","
                    + "\"t\":\"09:05:30\",\"ts\":\"2026-10-08T09:05:30Z\",\"dur\":\"PT1H30M\","
                    + "\"doc\":{\"name\":\"Alice\",\"tags\":[\"x\",1]},\"arr\":[1,null],\"none\":null}\n");
            assertThat(render(OutputFormat.JSON, EVERY_TYPE, List.of(everyType())))
                    .isEqualTo("[\n  " + out.strip() + "\n]\n");
        }

        @Test
        @DisplayName("escapes a line break inside a string, so an object is one line")
        void escaping() {
            String out = render(OutputFormat.NDJSON, SCHEMA, List.of(
                    row(SCHEMA, NumberValue.of("1"), new StringValue("a\"b\n\tc\\"))));

            assertThat(out).isEqualTo("{\"id\":1,\"name\":\"a\\\"b\\n\\tc\\\\\"}\n");
            assertThat(out.lines()).hasSize(1);
        }

        @Test
        @DisplayName("writes NULL as JSON null, whatever spelling the delimited formats were given")
        void nulls() {
            List<Row> rows = List.of(row(SCHEMA, NumberValue.of("1"), NullValue.INSTANCE));

            assertThat(render(OutputFormat.NDJSON, SCHEMA, rows, new OutputFormat.Options(false, "\\N", false, false)))
                    .isEqualTo("{\"id\":1,\"name\":null}\n");
        }

        @Test
        @DisplayName("an empty result writes nothing")
        void empty() {
            assertThat(render(OutputFormat.NDJSON, SCHEMA, List.of())).isEmpty();
        }

        @Test
        @DisplayName("tags each object with its result set's label when asked")
        void tagged() {
            String out = render(OutputFormat.NDJSON, SCHEMA,
                    List.of(row(SCHEMA, NumberValue.of("1"), new StringValue("Alice"))),
                    new OutputFormat.Options(true, null, false, true));

            assertThat(out).isEqualTo("{\"_query\":\"R\",\"id\":1,\"name\":\"Alice\"}\n");
        }
    }

    // ── Streaming ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Streaming")
    class Streaming {

        @Test
        @DisplayName("tsv and ndjson write the first row before the input ends")
        void firstRowBeforeTheEnd() {
            for (OutputFormat format : List.of(OutputFormat.TSV, OutputFormat.NDJSON)) {
                StringWriter sw = new StringWriter();
                PrintWriter pw = new PrintWriter(sw);
                String[] seenAtSecondPull = {null};
                Stream<Row> rows = Stream.iterate(1, i -> i <= 2, i -> i + 1).map(i -> {
                    if (i == 2) {
                        // Pulled only after the first row was handed over: what has been
                        // written by now is what a reader of the pipe could already see.
                        pw.flush();
                        seenAtSecondPull[0] = sw.toString();
                    }
                    return row(SCHEMA, NumberValue.of(Integer.toString(i)), new StringValue("r" + i));
                });

                format.write(pw, "R", SCHEMA, rows);

                assertThat(seenAtSecondPull[0]).as(format.name()).contains("r1").doesNotContain("r2");
            }
        }

        @Test
        @DisplayName("only the table holds its rows back")
        void onlyTheTableBuffers() {
            for (OutputFormat format : OutputFormat.values()) {
                assertThat(format.streams()).as(format.name()).isEqualTo(format != OutputFormat.TABLE);
            }
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
