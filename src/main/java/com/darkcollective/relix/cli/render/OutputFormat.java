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
import com.darkcollective.relix.symbol.Schema;

import java.io.PrintWriter;
import java.util.List;
import java.util.stream.Stream;

/**
 * Output format for {@code --exec} query results.
 *
 * <p>Each constant knows how to {@link #write} one query result to a
 * {@link PrintWriter}, consuming the lazy row {@link Stream} as it goes.  Three of
 * the four formats are <em>fully streaming</em> — they emit each row as it arrives
 * and run in constant memory regardless of result size:
 * <ul>
 *   <li>{@link #CSV} — RFC&nbsp;4180 comma-separated values with a header row;</li>
 *   <li>{@link #JSON} — a JSON array of one object per row;</li>
 *   <li>{@link #MARKDOWN} — a GitHub-flavoured Markdown table.</li>
 * </ul>
 * The default {@link #TABLE} format renders an aligned ASCII table and must buffer
 * each query's rows to compute column widths.
 *
 * <p>Every format ends its lines with {@code \n}, on Windows too: the output is data for
 * the next stage of a pipeline, and must not change with the platform it was made on.
 */
public enum OutputFormat {

    /** Aligned ASCII table (default). Buffers each query for column-width alignment. */
    TABLE {
        @Override
        public void write(PrintWriter out, String label, Schema schema, Stream<Row> rows) {
            out.print(QueryResultFormatter.format(label, schema, rows));
        }
    },

    /** RFC 4180 CSV with a header row, its lines ended by {@code \n}. Fully streaming. */
    CSV {
        @Override
        public void write(PrintWriter out, String label, Schema schema, Stream<Row> rows) {
            List<ColumnDefinition> cols = schema.columns();
            StringBuilder header = new StringBuilder();
            for (int i = 0; i < cols.size(); i++) {
                if (i > 0) header.append(',');
                header.append(csvField(cols.get(i).name()));
            }
            out.print(header + "\n");
            rows.forEach(row -> {
                StringBuilder line = new StringBuilder();
                for (int i = 0; i < cols.size(); i++) {
                    if (i > 0) line.append(',');
                    line.append(csvField(plainCell(row.get(i))));
                }
                out.print(line + "\n");
            });
        }
    },

    /** JSON array of one object per row ({@code {column: value}}). Fully streaming. */
    JSON {
        @Override
        public void write(PrintWriter out, String label, Schema schema, Stream<Row> rows) {
            List<ColumnDefinition> cols = schema.columns();
            out.print('[');
            boolean[] first = {true};
            rows.forEach(row -> {
                out.print(first[0] ? "\n  " : ",\n  ");
                first[0] = false;
                StringBuilder obj = new StringBuilder("{");
                for (int i = 0; i < cols.size(); i++) {
                    if (i > 0) obj.append(',');
                    obj.append(JsonText.quote(cols.get(i).name())).append(':').append(jsonValue(row.get(i)));
                }
                out.print(obj.append('}'));
            });
            out.print(first[0] ? "]\n" : "\n]\n");
        }
    },

    /** GitHub-flavoured Markdown table, under a {@code ###} heading. Fully streaming. */
    MARKDOWN {
        @Override
        public void write(PrintWriter out, String label, Schema schema, Stream<Row> rows) {
            List<ColumnDefinition> cols = schema.columns();
            out.print("### " + label + "\n");
            out.print("\n");
            StringBuilder header = new StringBuilder("|");
            StringBuilder rule   = new StringBuilder("|");
            for (ColumnDefinition col : cols) {
                header.append(' ').append(mdCell(col.name())).append(" |");
                rule.append(" --- |");
            }
            out.print(header + "\n");
            out.print(rule + "\n");
            rows.forEach(row -> {
                StringBuilder line = new StringBuilder("|");
                for (int i = 0; i < cols.size(); i++) {
                    line.append(' ').append(mdCell(plainCell(row.get(i)))).append(" |");
                }
                out.print(line + "\n");
            });
            out.print("\n");
        }
    };

    /** Writes one query result, consuming {@code rows}. */
    public abstract void write(PrintWriter out, String label, Schema schema, Stream<Row> rows);

    /**
     * Resolves a format name (case-insensitive) to a constant.
     *
     * @param name the requested format name
     * @return the matching format
     * @throws IllegalArgumentException if {@code name} matches no format
     */
    public static OutputFormat fromString(String name) {
        for (OutputFormat format : values()) {
            if (format.name().equalsIgnoreCase(name)) {
                return format;
            }
        }
        throw new IllegalArgumentException(
                "unknown output format: '" + name + "' (expected one of: table, csv, json, markdown)");
    }

    // ── shared value rendering ──────────────────────────────────────────────

    /** Plain text for a cell; SQL {@code NULL} renders as the empty string. */
    private static String plainCell(Value value) {
        return value.isNull() ? "" : value.asDisplayString();
    }

    /** Quotes a CSV field when it contains a comma, quote, or line break (RFC 4180). */
    private static String csvField(String s) {
        if (s.indexOf(',') >= 0 || s.indexOf('"') >= 0
                || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0) {
            return '"' + s.replace("\"", "\"\"") + '"';
        }
        return s;
    }

    /** Escapes a Markdown table cell: pipes are escaped and line breaks become spaces. */
    private static String mdCell(String s) {
        return s.replace("|", "\\|").replace('\n', ' ').replace('\r', ' ');
    }

    /** Renders a value as JSON: scalars as literals, structs/arrays recursively. */
    public static String jsonValue(Value value) {
        return switch (value) {
            case NullValue ignored   -> "null";
            case BooleanValue b       -> Boolean.toString(b.value());
            case NumberValue n        -> n.asDisplayString();   // plain decimal: a valid JSON number
            case StringValue s        -> JsonText.quote(s.value());
            case StructValue st       -> st.fields().entrySet().stream()
                    .map(e -> JsonText.quote(e.getKey()) + ":" + jsonValue(e.getValue()))
                    .collect(java.util.stream.Collectors.joining(",", "{", "}"));
            case ArrayValue ar        -> ar.elements().stream()
                    .map(OutputFormat::jsonValue)
                    .collect(java.util.stream.Collectors.joining(",", "[", "]"));
            // Temporal values render as their canonical ISO-8601 string.
            case DateValue d          -> JsonText.quote(d.asDisplayString());
            case TimeValue t          -> JsonText.quote(t.asDisplayString());
            case TimestampValue ts    -> JsonText.quote(ts.asDisplayString());
            case DurationValue du     -> JsonText.quote(du.asDisplayString());
        };
    }

    /** Quotes and escapes a string as a JSON string literal. */
}
