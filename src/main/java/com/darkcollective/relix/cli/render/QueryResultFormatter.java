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
import com.darkcollective.relix.value.NumberValue;
import com.darkcollective.relix.value.Value;
import com.darkcollective.relix.symbol.Schema;

import java.util.List;
import java.util.stream.Stream;

/**
 * Formats a query's rows as a human-readable ASCII table.
 *
 * <h2>Output format</h2>
 * <pre>
 * ── Orders ───────────────────────────────────────────────────────────────────
 *  id    customer_id  amount
 *  ────  ───────────  ──────
 *     1            7   49.99
 *     2            3  120.00
 * (2 rows)
 * </pre>
 *
 * <ul>
 *   <li>Column headers and string values are left-aligned.</li>
 *   <li>Number values are right-aligned.</li>
 *   <li>Column widths are the maximum of the header name and the widest value,
 *       capped at {@value #MAX_COLUMN_WIDTH} characters.</li>
 *   <li>Values longer than the cap are truncated with {@code …}.</li>
 *   <li>{@code NULL} values display as {@code NULL}, or as the caller says.</li>
 * </ul>
 *
 * <p>This class is stateless and cannot be instantiated.
 */
public final class QueryResultFormatter {

    /** Maximum characters allowed in a single column. */
    static final int MAX_COLUMN_WIDTH = 30;

    private QueryResultFormatter() {}

    /**
     * Formats a streamed query result as a printable ASCII table string.
     *
     * <p>Aligned tabular output requires the width of every column, which can only
     * be known after seeing every value — so this method buffers {@code rows} into
     * a list before rendering.  This is the one place the CLI materialises a result;
     * a streaming, non-aligned output format would avoid it.
     *
     * @param label  the result's display label; must not be {@code null}
     * @param schema the result's output schema; must not be {@code null}
     * @param rows   the result rows; consumed fully by this method
     * @return the formatted table as a multi-line string; never null or empty
     */
    public static String format(String label, Schema schema, Stream<Row> rows) {
        return format(label, schema, rows.toList(), "NULL");
    }

    /**
     * Formats a query's rows, already collected, as a printable ASCII table.
     *
     * @param label    the result's display label; must not be {@code null}
     * @param schema   the result's output schema; must not be {@code null}
     * @param rows     the result rows
     * @param nullText how a NULL is shown
     * @return the formatted table as a multi-line string; never null or empty
     */
    public static String format(String label, Schema schema, List<? extends Row> rows, String nullText) {
        // Closed schemas enumerate their declared columns; open (schema-on-read)
        // schemas have none, so we discover the column set from the rows — the
        // ordered union of each document's field names (see DocumentRow).
        List<String> columns = schema.isOpen()
                ? discoverColumns(rows)
                : schema.columns().stream().map(c -> c.name()).toList();
        int ncols = columns.size();

        // ── compute column widths ────────────────────────────────────────────
        int[] widths = new int[ncols];
        for (int i = 0; i < ncols; i++) {
            widths[i] = columns.get(i).length();
        }
        for (Row row : rows) {
            for (int i = 0; i < ncols; i++) {
                int len = cell(row.get(columns.get(i)), nullText).length();
                widths[i] = Math.min(MAX_COLUMN_WIDTH, Math.max(widths[i], len));
            }
        }

        // ── build output ─────────────────────────────────────────────────────
        var sb = new StringBuilder();

        // Section header: "── <label> ───..."  (total ≤80 chars including newline)
        sb.append("── ").append(label).append(' ');
        int dashes = Math.max(0, 76 - label.length());
        sb.append("─".repeat(dashes)).append('\n');

        // Column headers
        sb.append(' ');
        for (int i = 0; i < ncols; i++) {
            if (i > 0) sb.append("  ");
            sb.append(pad(columns.get(i), widths[i], false));
        }
        sb.append('\n');

        // Underlines
        sb.append(' ');
        for (int i = 0; i < ncols; i++) {
            if (i > 0) sb.append("  ");
            sb.append("─".repeat(widths[i]));
        }
        sb.append('\n');

        // Data rows
        for (Row row : rows) {
            sb.append(' ');
            for (int i = 0; i < ncols; i++) {
                if (i > 0) sb.append("  ");
                var value    = row.get(columns.get(i));
                String cell  = truncate(cell(value, nullText), widths[i]);
                boolean rightAlign = value instanceof NumberValue;
                sb.append(pad(cell, widths[i], rightAlign));
            }
            sb.append('\n');
        }

        // Row-count footer
        int n = rows.size();
        sb.append('(').append(n).append(n == 1 ? " row)" : " rows)").append('\n');

        return sb.toString();
    }

    /** The ordered union of every row's column names (first-seen order). */
    private static List<String> discoverColumns(List<? extends Row> rows) {
        var columns = new java.util.LinkedHashSet<String>();
        for (Row row : rows) {
            columns.addAll(row.columnNames());
        }
        return List.copyOf(columns);
    }

    // ── private helpers ──────────────────────────────────────────────────────

    /** A cell's text, with a NULL shown as {@code nullText}. */
    private static String cell(Value value, String nullText) {
        return value.isNull() ? nullText : value.asDisplayString();
    }

    /** Pads {@code s} to {@code width} chars, right- or left-aligning. */
    private static String pad(String s, int width, boolean rightAlign) {
        int gap = width - s.length();
        if (gap <= 0) return s;
        String spaces = " ".repeat(gap);
        return rightAlign ? spaces + s : s + spaces;
    }

    /** Truncates {@code s} to {@code maxWidth}, appending {@code …} if cut. */
    private static String truncate(String s, int maxWidth) {
        if (s.length() <= maxWidth) return s;
        return s.substring(0, maxWidth - 1) + "…";
    }
}
