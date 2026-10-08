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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The formats a query's rows are written in (design §3.3).
 *
 * <p>Each constant makes an {@link Encoder} for one result set, which turns it into text a
 * row at a time: what comes before the rows, each row, and what comes after. Every format
 * but {@link #TABLE} is <em>streaming</em>: a row's text is ready as soon as the row is,
 * so a result larger than memory goes straight through, and a reader at the other end of
 * a pipe sees each row as it is made:
 * <ul>
 *   <li>{@link #TSV} — IANA tab-separated values with a header row, its escapes keeping
 *       each row on one line;</li>
 *   <li>{@link #CSV} — RFC&nbsp;4180 comma-separated values with a header row;</li>
 *   <li>{@link #NDJSON} — one JSON object per line;</li>
 *   <li>{@link #JSON} — a JSON array of one object per row;</li>
 *   <li>{@link #MARKDOWN} — a GitHub-flavoured Markdown table.</li>
 * </ul>
 * {@link #TABLE} aligns its columns, so it must see every row before it writes any.
 *
 * <p>Every format ends its lines with {@code \n}, on Windows too: the output is data for
 * the next stage of a pipeline, and must not change with the platform it was made on.
 */
public enum OutputFormat {

    /** Aligned table for a person to read. Buffers each result set to align its columns. */
    TABLE {
        @Override
        public Encoder encoder(String label, Schema schema, Options options) {
            String nullText = options.nullText() != null ? options.nullText() : "NULL";
            List<Row> rows = new ArrayList<>();
            return new Encoder() {
                @Override
                public String row(Row row) {
                    rows.add(row);
                    return "";
                }

                @Override
                public String end() {
                    return QueryResultFormatter.format(label, schema, rows, nullText);
                }
            };
        }
    },

    /**
     * IANA tab-separated values with a header row. A tab, line feed, carriage return or
     * backslash in a value is written {@code \t}, {@code \n}, {@code \r} or {@code \\}, so
     * that a row is always one line, as {@code cut}, {@code awk} and {@code sort} expect.
     */
    TSV {
        @Override
        public Encoder encoder(String label, Schema schema, Options options) {
            return delimited(schema, options, '\t', OutputFormat::tsvField);
        }
    },

    /** RFC 4180 comma-separated values with a header row, its lines ended by {@code \n}. */
    CSV {
        @Override
        public Encoder encoder(String label, Schema schema, Options options) {
            return delimited(schema, options, ',', OutputFormat::csvField);
        }
    },

    /**
     * One JSON object per line ({@code {column: value}}), the values encoded as
     * {@link #JSON} encodes them. There is no header, and a NULL is JSON's {@code null}.
     */
    NDJSON {
        @Override
        public Encoder encoder(String label, Schema schema, Options options) {
            return row -> jsonObject(schema, row) + "\n";
        }
    },

    /** A JSON array of one object per row ({@code {column: value}}). */
    JSON {
        @Override
        public Encoder encoder(String label, Schema schema, Options options) {
            return new Encoder() {
                private boolean first = true;

                @Override
                public String begin() {
                    return "[";
                }

                @Override
                public String row(Row row) {
                    String separator = first ? "\n  " : ",\n  ";
                    first = false;
                    return separator + jsonObject(schema, row);
                }

                @Override
                public String end() {
                    return first ? "]\n" : "\n]\n";
                }
            };
        }
    },

    /** A GitHub-flavoured Markdown table, under a {@code ###} heading. */
    MARKDOWN {
        @Override
        public Encoder encoder(String label, Schema schema, Options options) {
            String nullText = options.nullText() != null ? options.nullText() : "";
            List<ColumnDefinition> cols = schema.columns();
            return new Encoder() {
                @Override
                public String begin() {
                    StringBuilder header = new StringBuilder("|");
                    StringBuilder rule = new StringBuilder("|");
                    for (ColumnDefinition col : cols) {
                        header.append(' ').append(mdCell(col.name())).append(" |");
                        rule.append(" --- |");
                    }
                    return "### " + label + "\n\n" + header + "\n" + rule + "\n";
                }

                @Override
                public String row(Row row) {
                    StringBuilder line = new StringBuilder("|");
                    for (int i = 0; i < cols.size(); i++) {
                        line.append(' ').append(mdCell(plainCell(row.get(i), nullText))).append(" |");
                    }
                    return line.append('\n').toString();
                }

                @Override
                public String end() {
                    return "\n";
                }
            };
        }
    };

    /**
     * How a result set is written, beyond its format.
     *
     * @param header   whether the delimited formats ({@link #TSV}, {@link #CSV}) write a
     *                 header row
     * @param nullText how a NULL is written, or {@code null} for the format's own: empty in
     *                 the delimited formats and Markdown, {@code NULL} in a table. The JSON
     *                 formats always write JSON's {@code null}.
     */
    public record Options(boolean header, String nullText) {

        /** Each format's own defaults: a header row, and its own NULL. */
        public static final Options DEFAULTS = new Options(true, null);
    }

    /**
     * One result set, turned into text a row at a time.
     *
     * <p>The text a call returns is what to write next; an encoder writes nothing itself.
     * An encoder is for one result set, and is called {@link #begin} once, {@link #row} once
     * per row, then {@link #end} once.
     */
    @FunctionalInterface
    public interface Encoder {

        /**
         * What comes before the first row: a header, an opening bracket.
         *
         * @return the text, possibly empty
         */
        default String begin() {
            return "";
        }

        /**
         * One row.
         *
         * @param row the row
         * @return its text, possibly empty when the format holds rows back
         */
        String row(Row row);

        /**
         * What comes after the last row.
         *
         * @return the text, possibly empty
         */
        default String end() {
            return "";
        }
    }

    /**
     * An encoder for one result set.
     *
     * @param label   the result's label: a query's name, or {@code <expression N>}
     * @param schema  the result's heading
     * @param options the header and NULL choices
     * @return an encoder for its rows
     */
    public abstract Encoder encoder(String label, Schema schema, Options options);

    /**
     * Writes one result set in this format's defaults, consuming {@code rows}.
     *
     * @param out    where to write
     * @param label  the result's label
     * @param schema the result's heading
     * @param rows   the rows; consumed, a row written as soon as it arrives
     */
    public void write(PrintWriter out, String label, Schema schema, Stream<? extends Row> rows) {
        write(out, label, schema, rows, Options.DEFAULTS);
    }

    /**
     * Writes one result set, consuming {@code rows}.
     *
     * @param out     where to write
     * @param label   the result's label
     * @param schema  the result's heading
     * @param rows    the rows; consumed, a row written as soon as it arrives
     * @param options the header and NULL choices
     */
    public void write(PrintWriter out, String label, Schema schema, Stream<? extends Row> rows,
                      Options options) {
        Encoder encoder = encoder(label, schema, options);
        out.print(encoder.begin());
        rows.forEach(row -> out.print(encoder.row(row)));
        out.print(encoder.end());
    }

    /**
     * Whether this format writes each row as it arrives, rather than holding them back.
     *
     * @return {@code false} for {@link #TABLE} alone
     */
    public boolean streams() {
        return this != TABLE;
    }

    /**
     * Whether this format is for a program rather than a person: one result set, nothing
     * around it but the format's own syntax.
     *
     * @return {@code true} for {@link #TSV}, {@link #CSV}, {@link #NDJSON} and {@link #JSON}
     */
    public boolean isMachineFormat() {
        return this != TABLE && this != MARKDOWN;
    }

    /**
     * The format's name as an option gives it.
     *
     * @return the lower-case name
     */
    public String optionName() {
        return name().toLowerCase(Locale.ROOT);
    }

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
        throw new IllegalArgumentException("unknown output format: '" + name + "' (expected one of: "
                + Arrays.stream(values()).map(OutputFormat::optionName).collect(Collectors.joining(", "))
                + ")");
    }

    // ── shared value rendering ──────────────────────────────────────────────

    /** A delimited format: a header row unless asked not to, then one line per row. */
    private static Encoder delimited(Schema schema, Options options, char delimiter,
                                     java.util.function.UnaryOperator<String> field) {
        String nullText = options.nullText() != null ? options.nullText() : "";
        List<ColumnDefinition> cols = schema.columns();
        return new Encoder() {
            @Override
            public String begin() {
                if (!options.header()) {
                    return "";
                }
                return cols.stream().map(c -> field.apply(c.name()))
                        .collect(Collectors.joining(String.valueOf(delimiter), "", "\n"));
            }

            @Override
            public String row(Row row) {
                StringBuilder line = new StringBuilder();
                for (int i = 0; i < cols.size(); i++) {
                    if (i > 0) line.append(delimiter);
                    // The NULL spelling is written as given, so that one a reader is told
                    // to expect, such as \N, arrives exactly so.
                    Value value = row.get(i);
                    line.append(value.isNull() ? nullText : field.apply(value.asDisplayString()));
                }
                return line.append('\n').toString();
            }
        };
    }

    /** Plain text for a cell, with a NULL written as {@code nullText}. */
    private static String plainCell(Value value, String nullText) {
        return value.isNull() ? nullText : value.asDisplayString();
    }

    /** Quotes a CSV field when it contains a comma, quote, or line break (RFC 4180). */
    private static String csvField(String s) {
        if (s.indexOf(',') >= 0 || s.indexOf('"') >= 0
                || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0) {
            return '"' + s.replace("\"", "\"\"") + '"';
        }
        return s;
    }

    /** Escapes a TSV field: the backslash first, then tab and the line breaks. */
    private static String tsvField(String s) {
        if (s.indexOf('\\') < 0 && s.indexOf('\t') < 0 && s.indexOf('\n') < 0 && s.indexOf('\r') < 0) {
            return s;
        }
        StringBuilder out = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '\t' -> out.append("\\t");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    /** Escapes a Markdown table cell: pipes are escaped and line breaks become spaces. */
    private static String mdCell(String s) {
        return s.replace("|", "\\|").replace('\n', ' ').replace('\r', ' ');
    }

    /**
     * A row as one JSON object. A closed heading gives its columns in order; an open one,
     * read schema-on-read, gives each row's own.
     */
    private static String jsonObject(Schema schema, Row row) {
        StringBuilder obj = new StringBuilder("{");
        if (schema.isOpen()) {
            List<String> names = row.columnNames();
            for (int i = 0; i < names.size(); i++) {
                if (i > 0) obj.append(',');
                obj.append(JsonText.quote(names.get(i))).append(':').append(jsonValue(row.get(names.get(i))));
            }
        } else {
            List<ColumnDefinition> cols = schema.columns();
            for (int i = 0; i < cols.size(); i++) {
                if (i > 0) obj.append(',');
                obj.append(JsonText.quote(cols.get(i).name())).append(':').append(jsonValue(row.get(i)));
            }
        }
        return obj.append('}').toString();
    }

    /**
     * Renders a value as JSON: scalars as literals, structs and arrays recursively, and the
     * temporal values as their ISO-8601 strings.
     *
     * @param value the value
     * @return its JSON text
     */
    public static String jsonValue(Value value) {
        return switch (value) {
            case NullValue ignored   -> "null";
            case BooleanValue b       -> Boolean.toString(b.value());
            case NumberValue n        -> n.asDisplayString();   // plain decimal: a valid JSON number
            case StringValue s        -> JsonText.quote(s.value());
            case StructValue st       -> st.fields().entrySet().stream()
                    .map(e -> JsonText.quote(e.getKey()) + ":" + jsonValue(e.getValue()))
                    .collect(Collectors.joining(",", "{", "}"));
            case ArrayValue ar        -> ar.elements().stream()
                    .map(OutputFormat::jsonValue)
                    .collect(Collectors.joining(",", "[", "]"));
            case DateValue d          -> JsonText.quote(d.asDisplayString());
            case TimeValue t          -> JsonText.quote(t.asDisplayString());
            case TimestampValue ts    -> JsonText.quote(ts.asDisplayString());
            case DurationValue du     -> JsonText.quote(du.asDisplayString());
        };
    }
}
