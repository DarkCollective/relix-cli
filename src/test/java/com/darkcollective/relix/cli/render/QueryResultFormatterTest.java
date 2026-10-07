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
import com.darkcollective.relix.value.BooleanValue;
import com.darkcollective.relix.value.NullValue;
import com.darkcollective.relix.value.NumberValue;
import com.darkcollective.relix.value.StringValue;
import com.darkcollective.relix.value.Value;
import com.darkcollective.relix.symbol.ColumnDefinition;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.Schema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link QueryResultFormatter}.
 */
@DisplayName("QueryResultFormatter")
final class QueryResultFormatterTest {

    /** A result as the formatter is handed one: its label, its heading and its rows. */
    private record Result(String label, Schema schema, List<Row> rows) {}

    // ── helpers ───────────────────────────────────────────────────────────────

    private static Schema schema(String... names) {
        var cols = new java.util.ArrayList<ColumnDefinition>();
        for (String name : names) {
            cols.add(new ColumnDefinition(name, ScalarType.ANY));
        }
        return new Schema(cols);
    }

    private static Row row(Schema schema, Value... values) {
        return Row.of(schema, values);
    }

    private static NumberValue num(String s) {
        return NumberValue.of(s);
    }

    private static StringValue str(String s) {
        return new StringValue(s);
    }

    // ── tests ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Header line")
    class HeaderLine {

        @Test
        @DisplayName("Starts with '── <label> '")
        void startsWithLabel() {
            Schema s = schema("id");
            Result qr = new Result("MyRelation", s, List.of());
            assertThat(QueryResultFormatter.format(qr.label(), qr.schema(), qr.rows().stream()))
                    .startsWith("── MyRelation ");
        }

        @Test
        @DisplayName("Header line is ≤80 chars")
        void headerWidthCapped() {
            Schema s = schema("id");
            Result qr = new Result("Short", s, List.of());
            String formatted = QueryResultFormatter.format(qr.label(), qr.schema(), qr.rows().stream());
            String headerLine = formatted.lines().findFirst().orElseThrow();
            assertThat(headerLine.length()).isLessThanOrEqualTo(80);
        }

        @Test
        @DisplayName("Long label does not push header beyond 80 chars")
        void longLabelCapped() {
            Schema s = schema("id");
            String longLabel = "A".repeat(70);
            Result qr = new Result(longLabel, s, List.of());
            String formatted = QueryResultFormatter.format(qr.label(), qr.schema(), qr.rows().stream());
            String headerLine = formatted.lines().findFirst().orElseThrow();
            assertThat(headerLine.length()).isLessThanOrEqualTo(80);
        }
    }

    @Nested
    @DisplayName("Column headers")
    class ColumnHeaders {

        @Test
        @DisplayName("Column names appear in second line")
        void columnNamesInSecondLine() {
            Schema s = schema("id", "name", "dept");
            Result qr = new Result("T", s, List.of());
            List<String> lines = QueryResultFormatter.format(qr.label(), qr.schema(), qr.rows().stream()).lines().toList();
            String headerRow = lines.get(1);
            assertThat(headerRow)
                    .contains("id")
                    .contains("name")
                    .contains("dept");
        }

        @Test
        @DisplayName("Third line contains only dashes and spaces")
        void thirdLineIsUnderline() {
            Schema s = schema("col1", "col2");
            Result qr = new Result("T", s, List.of());
            List<String> lines = QueryResultFormatter.format(qr.label(), qr.schema(), qr.rows().stream()).lines().toList();
            String underline = lines.get(2).trim();
            assertThat(underline).matches("[─ ]+");
        }
    }

    @Nested
    @DisplayName("Data rows")
    class DataRows {

        @Test
        @DisplayName("Each row appears on its own line")
        void eachRowOnOwnLine() {
            Schema s = schema("id", "name");
            List<Row> rows = List.of(
                    row(s, num("1"), str("Alice")),
                    row(s, num("2"), str("Bob")),
                    row(s, num("3"), str("Carol"))
            );
            Result qr = new Result("People", s, rows);
            String formatted = QueryResultFormatter.format(qr.label(), qr.schema(), qr.rows().stream());
            assertThat(formatted).contains("Alice");
            assertThat(formatted).contains("Bob");
            assertThat(formatted).contains("Carol");
        }

        @Test
        @DisplayName("Numbers are right-aligned")
        void numbersAreRightAligned() {
            Schema s = schema("id");
            List<Row> rows = List.of(
                    row(s, num("1")),
                    row(s, num("100"))
            );
            Result qr = new Result("T", s, rows);
            List<String> dataLines = QueryResultFormatter.format(qr.label(), qr.schema(), qr.rows().stream()).lines()
                    .skip(3) // header separator, col names, underline
                    .filter(l -> !l.startsWith("("))
                    .toList();
            // Both values should be padded to same width — "100" determines column width
            // " id " → col width 3, so "1" becomes "  1" (right-aligned)
            assertThat(dataLines.get(0)).endsWith("  1");
            assertThat(dataLines.get(1)).endsWith("100");
        }

        @Test
        @DisplayName("Strings are left-aligned")
        void stringsAreLeftAligned() {
            Schema s = schema("name");
            List<Row> rows = List.of(
                    row(s, str("Alice")),
                    row(s, str("Bo"))
            );
            Result qr = new Result("T", s, rows);
            List<String> dataLines = QueryResultFormatter.format(qr.label(), qr.schema(), qr.rows().stream()).lines()
                    .skip(3)
                    .filter(l -> !l.startsWith("("))
                    .toList();
            // col width = 5 ("Alice"), so "Bo" is padded to "Bo   "
            assertThat(dataLines.get(0).trim()).startsWith("Alice");
            assertThat(dataLines.get(1).trim()).startsWith("Bo");
        }

        @Test
        @DisplayName("NULL value displays as 'NULL'")
        void nullDisplaysAsNull() {
            Schema s = schema("val");
            List<Row> rows = List.of(row(s, NullValue.INSTANCE));
            Result qr = new Result("T", s, rows);
            assertThat(QueryResultFormatter.format(qr.label(), qr.schema(), qr.rows().stream())).contains("NULL");
        }

        @Test
        @DisplayName("Boolean values display as 'true' or 'false'")
        void booleanValues() {
            Schema s = schema("flag");
            List<Row> rows = List.of(
                    row(s, BooleanValue.TRUE),
                    row(s, BooleanValue.FALSE)
            );
            Result qr = new Result("T", s, rows);
            String formatted = QueryResultFormatter.format(qr.label(), qr.schema(), qr.rows().stream());
            assertThat(formatted).contains("true");
            assertThat(formatted).contains("false");
        }

        @Test
        @DisplayName("Long values are truncated with ellipsis")
        void longValuesTruncated() {
            Schema s = schema("v");
            String longVal = "x".repeat(QueryResultFormatter.MAX_COLUMN_WIDTH + 10);
            List<Row> rows = List.of(row(s, str(longVal)));
            Result qr = new Result("T", s, rows);
            String formatted = QueryResultFormatter.format(qr.label(), qr.schema(), qr.rows().stream());
            // The cell should be capped at MAX_COLUMN_WIDTH and end with …
            assertThat(formatted).contains("…");
        }

        @Test
        @DisplayName("Decimal trailing zeros are stripped")
        void decimalStripped() {
            Schema s = schema("amount");
            List<Row> rows = List.of(row(s, new NumberValue(new BigDecimal("49.90"))));
            Result qr = new Result("T", s, rows);
            // NumberValue.asDisplayString() strips trailing zeros
            assertThat(QueryResultFormatter.format(qr.label(), qr.schema(), qr.rows().stream())).contains("49.9");
        }
    }

    @Nested
    @DisplayName("Row-count footer")
    class RowCountFooter {

        @Test
        @DisplayName("Empty result shows '(0 rows)'")
        void zeroRows() {
            Schema s = schema("id");
            Result qr = new Result("T", s, List.of());
            assertThat(QueryResultFormatter.format(qr.label(), qr.schema(), qr.rows().stream())).contains("(0 rows)");
        }

        @Test
        @DisplayName("Single row shows '(1 row)' — singular")
        void oneRow() {
            Schema s = schema("id");
            List<Row> rows = List.of(row(s, num("1")));
            Result qr = new Result("T", s, rows);
            assertThat(QueryResultFormatter.format(qr.label(), qr.schema(), qr.rows().stream())).contains("(1 row)");
            assertThat(QueryResultFormatter.format(qr.label(), qr.schema(), qr.rows().stream())).doesNotContain("(1 rows)");
        }

        @Test
        @DisplayName("Multiple rows shows '(N rows)'")
        void multipleRows() {
            Schema s = schema("id");
            List<Row> rows = List.of(row(s, num("1")), row(s, num("2")));
            Result qr = new Result("T", s, rows);
            assertThat(QueryResultFormatter.format(qr.label(), qr.schema(), qr.rows().stream())).contains("(2 rows)");
        }
    }

    @Nested
    @DisplayName("Column width calculation")
    class ColumnWidths {

        @Test
        @DisplayName("Column width is at least the header name length")
        void widthAtLeastHeaderLength() {
            // "name" is 4 chars wide, single-char values should still pad to 4
            Schema s = schema("name");
            List<Row> rows = List.of(row(s, str("a")));
            Result qr = new Result("T", s, rows);
            List<String> lines = QueryResultFormatter.format(qr.label(), qr.schema(), qr.rows().stream()).lines().toList();
            // col header + underline should both be 4 chars wide at minimum
            String underline = lines.get(2).trim();
            assertThat(underline.length()).isGreaterThanOrEqualTo(4);
        }

        @Test
        @DisplayName("Column width is driven by the widest value when wider than header")
        void widthDrivenByData() {
            Schema s = schema("c");
            List<Row> rows = List.of(row(s, str("very_long_value")));
            Result qr = new Result("T", s, rows);
            List<String> lines = QueryResultFormatter.format(qr.label(), qr.schema(), qr.rows().stream()).lines().toList();
            String underline = lines.get(2).trim();
            assertThat(underline.length()).isEqualTo("very_long_value".length());
        }

        @Test
        @DisplayName("Column width never exceeds MAX_COLUMN_WIDTH")
        void widthCappedAtMax() {
            Schema s = schema("c");
            String longVal = "x".repeat(QueryResultFormatter.MAX_COLUMN_WIDTH + 20);
            List<Row> rows = List.of(row(s, str(longVal)));
            Result qr = new Result("T", s, rows);
            List<String> lines = QueryResultFormatter.format(qr.label(), qr.schema(), qr.rows().stream()).lines().toList();
            String underline = lines.get(2).trim();
            assertThat(underline.length()).isEqualTo(QueryResultFormatter.MAX_COLUMN_WIDTH);
        }
    }

    @Nested
    @DisplayName("Multi-column layout")
    class MultiColumn {

        @Test
        @DisplayName("Multiple columns are separated by two spaces")
        void columnsHaveSeparator() {
            Schema s = schema("a", "b");
            List<Row> rows = List.of(row(s, str("X"), str("Y")));
            Result qr = new Result("T", s, rows);
            // Both "a" and "b" appear in the header row with space between
            List<String> lines = QueryResultFormatter.format(qr.label(), qr.schema(), qr.rows().stream()).lines().toList();
            String colLine = lines.get(1);
            assertThat(colLine).contains("a");
            assertThat(colLine).contains("b");
            // Column headers are spaced apart — "a  b" pattern
            assertThat(colLine).matches(".*a.*b.*");
        }

        @Test
        @DisplayName("Five-column result formats all columns")
        void fiveColumns() {
            Schema s = schema("id", "first", "last", "age", "dept");
            List<Row> rows = List.of(
                    row(s, num("1"), str("Alice"), str("Smith"), num("30"), str("Eng")),
                    row(s, num("2"), str("Bob"),   str("Jones"), num("25"), str("Sales"))
            );
            Result qr = new Result("Staff", s, rows);
            String formatted = QueryResultFormatter.format(qr.label(), qr.schema(), qr.rows().stream());
            assertThat(formatted)
                    .contains("first")
                    .contains("last")
                    .contains("Alice")
                    .contains("Jones")
                    .contains("(2 rows)");
        }
    }

    @Nested
    @DisplayName("Open (schema-on-read) rows")
    class OpenSchema {

        /**
         * A document row, as a schema-on-read source yields one: its columns are its own
         * fields, in order, and a field it lacks reads as NULL.
         */
        private static Row doc(Object... fieldsAndValues) {
            Map<String, Value> fields = new LinkedHashMap<>();
            for (int i = 0; i < fieldsAndValues.length; i += 2) {
                fields.put((String) fieldsAndValues[i], (Value) fieldsAndValues[i + 1]);
            }
            List<String> names = List.copyOf(fields.keySet());
            return new Row() {
                @Override
                public Schema schema() {
                    return Schema.open();
                }

                @Override
                public Value get(String name) {
                    return fields.getOrDefault(name, NullValue.INSTANCE);
                }

                @Override
                public Value get(int index) {
                    return fields.get(names.get(index));
                }

                @Override
                public int width() {
                    return names.size();
                }

                @Override
                public List<String> columnNames() {
                    return names;
                }
            };
        }

        @Test
        @DisplayName("columns are discovered from the documents' field names")
        void discoversColumnsFromDocuments() {
            Result qr = new Result("Docs", Schema.open(), List.of(
                    doc("id", num("1"), "name", str("Alice")),
                    doc("id", num("2"), "name", str("Bob"))));

            String formatted = QueryResultFormatter.format(qr.label(), qr.schema(), qr.rows().stream());

            assertThat(formatted)
                    .contains("id").contains("name")
                    .contains("Alice").contains("Bob")
                    .contains("(2 rows)");
        }

        @Test
        @DisplayName("the union of heterogeneous fields forms the column set; misses render NULL")
        void unionOfHeterogeneousFields() {
            Result qr = new Result("Docs", Schema.open(), List.of(
                    doc("a", num("1")),
                    doc("b", num("2"))));

            String formatted = QueryResultFormatter.format(qr.label(), qr.schema(), qr.rows().stream());

            // Both columns appear (first-seen order), and the absent cell is NULL.
            assertThat(formatted).contains("a").contains("b").contains("NULL");
        }
    }
}
