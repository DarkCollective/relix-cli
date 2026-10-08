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

import com.darkcollective.relix.cli.io.ScriptText;
import com.darkcollective.relix.docs.RelixDocs;
import com.darkcollective.relix.lang.ast.ScriptPrinter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Prints a script canonically: each statement as the engine's statement printer writes
 * it, the printer behind {@code Relix.definitions()}, with the script's comments kept.
 *
 * <p>The printer drops comments, since a comment is not in the tree it prints from
 * (DarkCollective/relix-core#99), so they are put back from the text:
 * <ul>
 *   <li>A comment between statements stays between them, on a line of its own, or after
 *       the statement it followed on the same line.</li>
 *   <li>A statement with a comment inside it is left exactly as written, because printing
 *       it would lose the comment.</li>
 *   <li>A blank line between two things stays one blank line; more become one.</li>
 * </ul>
 *
 * <p>An inline table's columns are aligned. Operators are written as glyphs, the
 * printer's spelling, or with {@link Spelling#KEYWORDS} as the ASCII keywords the
 * reference's spellings page pairs with them. Formatting is idempotent: formatting its
 * own output changes nothing.
 */
public final class ScriptFormatter {

    /** How operators are written. */
    public enum Spelling {
        /** {@code σ}, {@code ⋈}, {@code ∧}: the printer's own. */
        GLYPHS,
        /** {@code SELECT}, {@code JOIN}, {@code AND}. */
        KEYWORDS
    }

    private static final Pattern NAMESPACE = Pattern.compile("\\bnamespace\\b");

    private ScriptFormatter() {
    }

    /**
     * Formats a script.
     *
     * @param text     the script
     * @param spelling how operators are written
     * @return the script, formatted, ending in one line break
     * @throws com.darkcollective.relix.lang.ast.ScriptParseException when it does not parse
     */
    public static String format(String text, Spelling spelling) {
        ScriptText layout = ScriptText.of(text);
        List<Item> items = new ArrayList<>();
        List<ScriptText.Piece> statements = layout.statements();
        int preamble = statements.isEmpty() ? text.length() : statements.getFirst().span().start();
        layout.script().namespace().ifPresent(name ->
                items.add(new Item(namespaceAt(text, layout.comments(0, preamble), preamble),
                        "namespace " + name + ";")));
        int at = 0;
        for (ScriptText.Piece piece : statements) {
            comments(text, layout.comments(at, piece.span().start()), items);
            items.add(new Item(piece.span().start(), piece.span().end(), statement(text, piece, spelling)));
            at = piece.span().end();
        }
        comments(text, layout.comments(at, text.length()), items);
        items.sort((a, b) -> Integer.compare(a.start(), b.start()));
        return join(text, items);
    }

    /** A thing to print, and where it was in the text. */
    private record Item(int start, int end, String text) {
        Item(ScriptText.Span span, String text) {
            this(span.start(), span.end(), text);
        }
    }

    private static void comments(String text, List<ScriptText.Span> comments, List<Item> items) {
        for (ScriptText.Span comment : comments) {
            items.add(new Item(comment, comment.of(text).stripTrailing()));
        }
    }

    /** Where the namespace declaration is, among the comments before the first statement. */
    private static ScriptText.Span namespaceAt(String text, List<ScriptText.Span> comments, int end) {
        StringBuilder blanked = new StringBuilder(text.substring(0, end));
        for (ScriptText.Span comment : comments) {
            for (int i = comment.start(); i < comment.end(); i++) {
                blanked.setCharAt(i, ' ');
            }
        }
        Matcher m = NAMESPACE.matcher(blanked);
        int start = m.find() ? m.start() : 0;
        int semicolon = blanked.indexOf(";", start);
        return new ScriptText.Span(start, semicolon < 0 ? end : semicolon + 1);
    }

    private static String statement(String text, ScriptText.Piece piece, Spelling spelling) {
        if (!piece.comments().isEmpty()) {
            return piece.span().of(text).strip();
        }
        String printed = ScriptPrinter.print(piece.statement());
        if (spelling == Spelling.KEYWORDS) {
            printed = Respelling.keywords(printed);
        }
        return Tables.align(printed);
    }

    /**
     * The items, each separated from the one before as it was in the text: by a space when
     * it followed on the same line, a line break, or one blank line for one or more.
     */
    private static String join(String text, List<Item> items) {
        StringBuilder out = new StringBuilder();
        Item previous = null;
        for (Item item : items) {
            if (previous != null) {
                String between = text.substring(Math.min(previous.end(), item.start()), item.start());
                long breaks = between.chars().filter(c -> c == '\n').count();
                out.append(breaks == 0 ? " " : breaks == 1 ? "\n" : "\n\n");
            }
            out.append(item.text());
            previous = item;
        }
        return out.isEmpty() ? "" : out.append('\n').toString();
    }

    /**
     * Rewrites the printer's glyphs as the keywords {@code language/spellings.md} pairs with
     * them, outside string literals and delimited names.
     */
    private static final class Respelling {

        private static final Pattern ROW = Pattern.compile("^\\|\\s*`([^`]+)`\\s*\\|\\s*`((?:[^`\\\\]|\\\\.)+)`\\s*\\|");

        private static final Map<String, String> KEYWORDS = read();

        private Respelling() {
        }

        static String keywords(String printed) {
            StringBuilder out = new StringBuilder(printed.length() + 16);
            int i = 0;
            while (i < printed.length()) {
                char c = printed.charAt(i);
                if (c == '"' || c == '\'' || c == '`') {
                    int end = closing(printed, i, c);
                    out.append(printed, i, end);
                    i = end;
                    continue;
                }
                if (c == '[' && i + 1 < printed.length() && printed.charAt(i + 1) == '\n') {
                    // An inline table: its cells are data, not operators.
                    int end = printed.indexOf("\n]", i);
                    end = end < 0 ? printed.length() : end + 2;
                    out.append(printed, i, end);
                    i = end;
                    continue;
                }
                String glyph = String.valueOf(c);
                String keyword = KEYWORDS.get(glyph);
                if (keyword == null) {
                    out.append(c);
                } else {
                    if (!out.isEmpty() && isWordChar(out.charAt(out.length() - 1)) && isWordChar(keyword.charAt(0))) {
                        out.append(' ');
                    }
                    out.append(keyword);
                    if (i + 1 < printed.length() && isWordChar(printed.charAt(i + 1))
                            && isWordChar(keyword.charAt(keyword.length() - 1))) {
                        out.append(' ');
                    }
                }
                i++;
            }
            return out.toString();
        }

        private static int closing(String text, int open, char quote) {
            int i = open + 1;
            while (i < text.length() && text.charAt(i) != quote) {
                i += text.charAt(i) == '\\' && quote != '`' ? 2 : 1;
            }
            return Math.min(text.length(), i + 1);
        }

        private static boolean isWordChar(char c) {
            return Character.isLetterOrDigit(c) || c == '_';
        }

        /** Each row of the spellings tables that pairs one glyph with one ASCII spelling. */
        private static Map<String, String> read() {
            Optional<String> page = RelixDocs.referencePage("language/spellings.md");
            Map<String, String> keywords = new LinkedHashMap<>();
            for (String line : page.orElseThrow().split("\\R")) {
                Matcher m = ROW.matcher(line);
                if (m.find() && m.group(1).length() == 1 && m.group(1).charAt(0) > 0x7f) {
                    keywords.put(m.group(1), m.group(2).replace("\\|", "|"));
                }
            }
            return Map.copyOf(keywords);
        }
    }

    /** Aligns the columns of the inline tables in a printed statement. */
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
                if (!isRow(lines.get(i))) {
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
