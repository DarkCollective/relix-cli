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

import com.darkcollective.relix.ast.Spelling;
import com.darkcollective.relix.embed.Relix;
import com.darkcollective.relix.lang.ast.ScriptPrinter;

import java.util.ArrayList;
import java.util.List;

/**
 * Prints a script canonically: as the engine's script printer writes it, which keeps the
 * script's comments, before, inside and after each statement, and its blank lines
 * (DarkCollective/relix-core#99), with the columns of its inline tables aligned.
 *
 * <p>Operators are written as glyphs, the printer's default, or with
 * {@link Spelling#KEYWORDS} as ASCII keywords. Formatting is idempotent: formatting its
 * own output changes nothing.
 */
public final class ScriptFormatter {

    private ScriptFormatter() {
    }

    /**
     * Formats a script.
     *
     * @param text     the script
     * @param source   the name to report a parse error against
     * @param spelling how operators are written
     * @return the script, formatted, ending in one line break
     * @throws com.darkcollective.relix.lang.ast.ScriptParseException when it does not parse
     */
    public static String format(String text, String source, Spelling spelling) {
        return Tables.align(ScriptPrinter.print(Relix.parse(text, source), spelling));
    }

    /**
     * Aligns the columns of the inline tables in a printed script: each run of rows the
     * printer writes on the lines after a table's opening {@code [}, so that a comment
     * whose lines look like rows is left as written.
     */
    private static final class Tables {

        private Tables() {
        }

        static String align(String printed) {
            if (!printed.contains("\n|")) {
                return printed;
            }
            List<String> lines = new ArrayList<>(List.of(printed.split("\n", -1)));
            int i = 0;
            while (i < lines.size()) {
                if (!isRow(lines.get(i)) || i == 0 || !lines.get(i - 1).endsWith("[")) {
                    i++;
                    continue;
                }
                int end = i;
                while (end < lines.size() && isRow(lines.get(end))) {
                    end++;
                }
                align(lines.subList(i, end));
                i = end;
            }
            return String.join("\n", lines);
        }

        private static boolean isRow(String line) {
            return line.length() >= 2 && line.startsWith("|") && line.endsWith("|");
        }

        private static void align(List<String> rows) {
            List<List<String>> cells = new ArrayList<>();
            List<Boolean> rules = new ArrayList<>();
            List<Integer> widths = new ArrayList<>();
            for (String row : rows) {
                List<String> split = cells(row);
                boolean rule = split.stream().allMatch(Tables::isRule);
                cells.add(split);
                rules.add(rule);
                for (int c = 0; c < split.size(); c++) {
                    int width = rule ? 3 : width(split.get(c));
                    if (c == widths.size()) {
                        widths.add(width);
                    } else {
                        widths.set(c, Math.max(widths.get(c), width));
                    }
                }
            }
            for (int r = 0; r < rows.size(); r++) {
                StringBuilder line = new StringBuilder("|");
                List<String> split = cells.get(r);
                for (int c = 0; c < split.size(); c++) {
                    String cell = split.get(c);
                    if (rules.get(r)) {
                        line.append("-".repeat(widths.get(c) + 2)).append('|');
                    } else {
                        line.append(' ').append(cell).append(" ".repeat(widths.get(c) - width(cell))).append(" |");
                    }
                }
                rows.set(r, line.toString());
            }
        }

        /** A row's cells, trimmed, split at the pipes that are not in a string or escaped. */
        private static List<String> cells(String row) {
            List<String> cells = new ArrayList<>();
            StringBuilder cell = new StringBuilder();
            boolean quoted = false;
            for (int i = 1; i < row.length(); i++) {
                char c = row.charAt(i);
                if (c == '\\' && i + 1 < row.length()) {
                    cell.append(c).append(row.charAt(++i));
                } else if (c == '"') {
                    quoted = !quoted;
                    cell.append(c);
                } else if (c == '|' && !quoted) {
                    cells.add(cell.toString().strip());
                    cell.setLength(0);
                } else {
                    cell.append(c);
                }
            }
            return cells;
        }

        private static boolean isRule(String cell) {
            return !cell.isEmpty() && cell.chars().allMatch(ch -> ch == '-' || ch == ':');
        }

        private static int width(String cell) {
            return cell.codePointCount(0, cell.length());
        }
    }
}
