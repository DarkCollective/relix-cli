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
package com.darkcollective.relix.cli.io;

import com.darkcollective.relix.ast.SourceLocation;
import com.darkcollective.relix.embed.Relix;
import com.darkcollective.relix.lang.ast.Script;
import com.darkcollective.relix.lang.ast.Statement;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A script's text together with what it parses to: where in the text each statement is.
 *
 * <p>The parser gives each statement the place it starts; this finds where it ends, at the
 * semicolon that closes it, passing over the comments a semicolon may be in. Those are the
 * language's, read as its two lexers read them: {@code --} to the end of the line and {@code /* *}{@code /}, outside
 * a string. Within an inline table only a line that begins with one is a comment, since a
 * cell may hold {@code --} as data, and a table's {@code |---|} rule is not one.
 */
public final class ScriptText {

    /**
     * A stretch of the text, from {@code start} up to but not including {@code end}.
     *
     * @param start the offset of its first character
     * @param end   the offset just after its last
     */
    public record Span(int start, int end) {

        /**
         * The text this stretch covers.
         *
         * @param text the whole text
         * @return the stretch of it
         */
        public String of(String text) {
            return text.substring(start, end);
        }
    }

    /**
     * One statement and where it is written.
     *
     * @param statement what it parses to
     * @param span      its text, from its first character to the semicolon that ends it
     */
    public record Piece(Statement statement, Span span) {

        /** Checks every component. */
        public Piece {
            Objects.requireNonNull(statement, "statement");
            Objects.requireNonNull(span, "span");
        }
    }

    private final String text;
    private final List<Piece> pieces;

    private ScriptText(String text, List<Piece> pieces) {
        this.text = text;
        this.pieces = List.copyOf(pieces);
    }

    /**
     * Parses a script and places its statements in its text.
     *
     * @param text the script
     * @return the script and its layout
     * @throws com.darkcollective.relix.embed.RelixException when the text does not parse
     */
    public static ScriptText of(String text) {
        Script script = Relix.parse(text);
        int[] lines = lineStarts(text);
        List<Piece> pieces = new ArrayList<>();
        for (Statement statement : script.statements()) {
            int start = offset(statement.location(), lines, text);
            Scan scan = new Scan(text, start);
            scan.toEndOfStatement();
            pieces.add(new Piece(statement, new Span(start, scan.position)));
        }
        return new ScriptText(text, pieces);
    }

    /** {@return the text} */
    public String text() {
        return text;
    }

    /** {@return each statement and where it is written, in order} */
    public List<Piece> statements() {
        return pieces;
    }

    private static int[] lineStarts(String text) {
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                starts.add(i + 1);
            }
        }
        return starts.stream().mapToInt(Integer::intValue).toArray();
    }

    private static int offset(SourceLocation location, int[] lines, String text) {
        int line = Math.max(1, Math.min(location.line(), lines.length));
        return Math.min(text.length(), lines[line - 1] + Math.max(0, location.column() - 1));
    }

    /**
     * Reads the text as the lexers do, as far as finding the end of a statement needs:
     * through comments, strings, delimited names, braces (an expression body) and brackets
     * (an inline table).
     */
    private static final class Scan {

        private enum Context { TOP, BRACE, BRACKET }

        private final String text;
        private final List<Context> stack = new ArrayList<>(List.of(Context.TOP));
        private int position;

        Scan(String text, int start) {
            this.text = text;
            this.position = start;
        }

        /** Reads up to and including the semicolon that ends the statement. */
        void toEndOfStatement() {
            while (position < text.length()) {
                if (stack.getLast() == Context.TOP && text.charAt(position) == ';') {
                    position++;
                    return;
                }
                step();
            }
        }

        private void step() {
            char c = text.charAt(position);
            Context context = stack.getLast();
            if (comment(context)) {
                return;
            }
            switch (c) {
                case '"' -> quoted('"');
                case '\'' -> {
                    // A quote is a string in an expression; in a table it is a cell's text.
                    if (context == Context.BRACE) {
                        quoted('\'');
                    } else {
                        position++;
                    }
                }
                case '`' -> {
                    if (context == Context.BRACKET) {
                        position++;
                    } else {
                        quoted('`');
                    }
                }
                case '{' -> {
                    if (context != Context.BRACKET) {
                        stack.add(Context.BRACE);
                    }
                    position++;
                }
                case '}' -> {
                    if (context == Context.BRACE) {
                        stack.removeLast();
                    }
                    position++;
                }
                case '[' -> {
                    // Brackets inside an expression are an array, not a table.
                    if (context != Context.BRACE) {
                        stack.add(Context.BRACKET);
                    }
                    position++;
                }
                case ']' -> {
                    if (context == Context.BRACKET) {
                        stack.removeLast();
                    }
                    position++;
                }
                default -> position++;
            }
        }

        /** Reads past a comment starting here, if one does. */
        private boolean comment(Context context) {
            if (!text.startsWith("--", position) && !text.startsWith("/*", position)) {
                return false;
            }
            if (context == Context.BRACKET && !atLineStart()) {
                return false;
            }
            if (text.startsWith("--", position)) {
                int end = text.indexOf('\n', position);
                position = end < 0 ? text.length() : end;
            } else {
                int end = text.indexOf("*/", position + 2);
                position = end < 0 ? text.length() : end + 2;
            }
            return true;
        }

        private boolean atLineStart() {
            for (int i = position - 1; i >= 0; i--) {
                char c = text.charAt(i);
                if (c == '\n') {
                    return true;
                }
                if (c != ' ' && c != '\t' && c != '\r') {
                    return false;
                }
            }
            return true;
        }

        private void quoted(char quote) {
            position++;
            while (position < text.length() && text.charAt(position) != quote) {
                if (text.charAt(position) == '\\' && quote != '`') {
                    position++;
                }
                position++;
            }
            position = Math.min(text.length(), position + 1);
        }
    }
}
