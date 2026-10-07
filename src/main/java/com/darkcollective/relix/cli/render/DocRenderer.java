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

/**
 * Converts a bundled Markdown reference page to plain terminal text.
 *
 * <h2>Transformation rules</h2>
 * <ul>
 *   <li>{@code # Name: …} — printed as a title line with surrounding decoration.</li>
 *   <li>Other {@code # Section:} headers — printed as a labelled separator line.</li>
 *   <li>Fenced {@code ```mermaid} blocks — replaced with a one-line note directing
 *       the reader to the rendered reference page; the raw Mermaid DSL is never
 *       printed because it is not meaningful in a monospace terminal.</li>
 *   <li>Other fenced code blocks — fence markers removed; content indented 2 spaces.</li>
 *   <li>Inline Markdown ({@code **bold**}, {@code *italic*}, {@code `code`},
 *       {@code [text](url)}) — stripped to plain text.</li>
 *   <li>ASCII pipe tables — passed through unchanged (already terminal-friendly).</li>
 *   <li>Blank lines — preserved as-is.</li>
 * </ul>
 */
public final class DocRenderer {

    private static final int WIDTH = 78;

    private DocRenderer() {
    }

    /**
     * Renders {@code markdownContent} as terminal text.
     *
     * @param markdownContent raw Markdown from the bundled reference page
     * @param resourcePath    path relative to {@code docs/reference/} — used in
     *                        the Mermaid replacement note
     * @return terminal-ready plain text
     */
    public static String render(String markdownContent, String resourcePath) {
        StringBuilder out = new StringBuilder();
        String[] lines = markdownContent.split("\n", -1);
        boolean inFence   = false;
        boolean inMermaid = false;

        for (String line : lines) {
            if (!inFence) {
                if (line.startsWith("```")) {
                    String lang = line.substring(3).strip().toLowerCase();
                    inFence   = true;
                    inMermaid = lang.equals("mermaid");
                    // suppress the fence opener
                } else if (line.startsWith("# ")) {
                    out.append(renderSectionHeader(line.substring(2)));
                } else {
                    out.append(stripInlineMarkdown(line)).append('\n');
                }
            } else {
                // inside a fenced block
                if (line.startsWith("```")) {
                    inFence = false;
                    if (inMermaid) {
                        out.append("  [diagram — see docs/reference/")
                           .append(resourcePath)
                           .append(" for rendered version]\n");
                        inMermaid = false;
                    }
                    // suppress the fence closer
                } else if (!inMermaid) {
                    out.append("  ").append(line).append('\n');
                }
                // mermaid content lines are silently suppressed
            }
        }
        return out.toString();
    }

    // -------------------------------------------------------------------------

    /**
     * Renders a {@code # …} section header.
     *
     * <p>{@code # Name: Selection (σ / SELECT)} becomes a boxed title;
     * all other sections become a labelled separator line.
     */
    private static String renderSectionHeader(String headerText) {
        // Strip trailing colon if present ("Syntax:" → "Syntax")
        String title = headerText.endsWith(":") ? headerText.substring(0, headerText.length() - 1) : headerText;

        if (title.startsWith("Name: ")) {
            // First heading — render as a prominent title
            String name = stripInlineMarkdown(title.substring(6));
            String bar = "═".repeat(WIDTH);
            return "\n" + bar + "\n" + name + "\n" + bar + "\n";
        }

        // Regular section header — "── TITLE ───────────────────────────"
        // Stripped like every body line: a heading is markdown too, and printing a
        // backtick where the rest of the page shows none is the terminal's half of
        // the same gap the site and the PDF had.
        String label = "── " + stripInlineMarkdown(title).toUpperCase() + " ";
        int dashes = Math.max(0, WIDTH - label.length());
        return "\n" + label + "─".repeat(dashes) + "\n";
    }

    /**
     * Strips inline Markdown from a single line: bold, italic, inline-code,
     * and link syntax, leaving plain text.  Table rows and bare text are
     * returned unchanged.
     */
    static String stripInlineMarkdown(String line) {
        // [text](url) → text
        line = line.replaceAll("\\[([^\\]]+)]\\([^)]+\\)", "$1");
        // **bold** or __bold__ → bold
        line = line.replaceAll("\\*\\*(.+?)\\*\\*", "$1");
        line = line.replaceAll("__(.+?)__", "$1");
        // *italic* or _italic_ → italic  (but not lone _ in identifiers)
        line = line.replaceAll("(?<![\\w\\*])\\*([^\\*]+)\\*(?![\\w\\*])", "$1");
        // `inline code` / `` `a name in backticks` `` → the text it renders as. A run
        // of n backticks is closed by a run of n, so a name that contains one survives.
        line = line.replaceAll("(`+)\\s?(.+?)\\s?\\1", "$2");
        return line;
    }
}
