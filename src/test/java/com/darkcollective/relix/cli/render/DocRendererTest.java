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

import com.darkcollective.relix.docs.RelixDocs;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DocRenderer")
final class DocRendererTest {

    // =========================================================================
    // Section headers
    // =========================================================================

    @Nested
    @DisplayName("Section headers")
    class SectionHeaders {

        @Test
        @DisplayName("# Name: … becomes a boxed title")
        void nameHeader() {
            String out = DocRenderer.render("# Name: Selection (σ / SELECT)\n", "operators/select.md");
            assertThat(out).contains("Selection (σ / SELECT)");
            assertThat(out).contains("═══"); // decorator present
        }

        @Test
        @DisplayName("# Syntax: becomes an uppercase separator")
        void syntaxHeader() {
            String out = DocRenderer.render("# Syntax:\nσ predicate (R)\n", "operators/select.md");
            assertThat(out).contains("── SYNTAX");
            assertThat(out).contains("σ predicate (R)");
        }

        @Test
        @DisplayName("# Description: becomes DESCRIPTION label")
        void descriptionHeader() {
            String out = DocRenderer.render("# Description:\nKeeps matching rows.\n", "operators/select.md");
            assertThat(out).contains("── DESCRIPTION");
            assertThat(out).contains("Keeps matching rows.");
        }

        @Test
        @DisplayName("Section header text is uppercased")
        void sectionUppercase() {
            String out = DocRenderer.render("# See Also:\nfoo\n", "x.md");
            assertThat(out).contains("── SEE ALSO");
        }

        @Test
        @DisplayName("A label's inline code is stripped, as every body line's already was")
        void labelInlineCode() {
            String out = DocRenderer.render("# Legacy `_r` disambiguation:\nfoo\n", "x.md");
            assertThat(out).contains("── LEGACY _R DISAMBIGUATION").doesNotContain("`");
        }

        @Test
        @DisplayName("A title's inline code is stripped too")
        void titleInlineCode() {
            String out = DocRenderer.render("# Name: Reserved namespace (`relix.catalog`)\n", "x.md");
            assertThat(out).contains("Reserved namespace (relix.catalog)").doesNotContain("`");
        }
    }

    // =========================================================================
    // Mermaid blocks
    // =========================================================================

    @Nested
    @DisplayName("Mermaid diagram blocks")
    class MermaidBlocks {

        @Test
        @DisplayName("Mermaid fence is replaced with a pointer note")
        void mermaidReplacedWithNote() {
            String input = """
                    Before
                    ```mermaid
                    graph LR
                      A --> B
                    ```
                    After
                    """;
            String out = DocRenderer.render(input, "advanced/closure.md");
            assertThat(out).doesNotContain("graph LR");
            assertThat(out).doesNotContain("A --> B");
            assertThat(out).contains("[diagram — see docs/reference/advanced/closure.md");
            assertThat(out).contains("Before");
            assertThat(out).contains("After");
        }

        @Test
        @DisplayName("Multiple Mermaid blocks are all replaced")
        void multipleMermaidBlocks() {
            String input = """
                    Text
                    ```mermaid
                    graph LR
                      X --> Y
                    ```
                    Middle
                    ```mermaid
                    graph TD
                      P --> Q
                    ```
                    End
                    """;
            String out = DocRenderer.render(input, "foo/bar.md");
            assertThat(out).doesNotContain("graph LR").doesNotContain("graph TD");
            long count = out.lines().filter(l -> l.contains("[diagram")).count();
            assertThat(count).isEqualTo(2);
        }
    }

    // =========================================================================
    // Code blocks (non-Mermaid)
    // =========================================================================

    @Nested
    @DisplayName("Non-Mermaid code blocks")
    class CodeBlocks {

        @Test
        @DisplayName("Relix code block: fence markers removed, content indented")
        void relixCodeBlock() {
            String input = """
                    ```relix
                    Orders := [ | id | 1 | ];
                    ```
                    """;
            String out = DocRenderer.render(input, "operators/select.md");
            assertThat(out).doesNotContain("```");
            assertThat(out).contains("  Orders := [ | id | 1 | ];");
        }

        @Test
        @DisplayName("Plain code block: fence markers removed, content indented")
        void plainCodeBlock() {
            String input = """
                    ```
                    some plain code
                    ```
                    """;
            String out = DocRenderer.render(input, "x.md");
            assertThat(out).doesNotContain("```");
            assertThat(out).contains("  some plain code");
        }
    }

    // =========================================================================
    // Inline Markdown stripping
    // =========================================================================

    @Nested
    @DisplayName("Inline Markdown stripping")
    class InlineMarkdown {

        @Test
        @DisplayName("Bold **text** is stripped to plain text")
        void bold() {
            String out = DocRenderer.stripInlineMarkdown("This is **important**.");
            assertThat(out).isEqualTo("This is important.");
        }

        @Test
        @DisplayName("Italic *text* is stripped")
        void italic() {
            String out = DocRenderer.stripInlineMarkdown("A *key* point.");
            assertThat(out).isEqualTo("A key point.");
        }

        @Test
        @DisplayName("Inline code `text` is stripped to plain text")
        void inlineCode() {
            String out = DocRenderer.stripInlineMarkdown("Use `CLOSURE` here.");
            assertThat(out).isEqualTo("Use CLOSURE here.");
        }

        @Test
        @DisplayName("A run of backticks is one span, so a name keeps the backticks it is made of")
        void backtickRun() {
            String out = DocRenderer.stripInlineMarkdown("The name `` `order` `` is delimited.");
            assertThat(out).isEqualTo("The name `order` is delimited.");
        }

        @Test
        @DisplayName("[text](url) link is stripped to just the text")
        void link() {
            String out = DocRenderer.stripInlineMarkdown("See [fix](fix.md) for details.");
            assertThat(out).isEqualTo("See fix for details.");
        }

        @Test
        @DisplayName("ASCII table rows pass through unchanged")
        void tableRow() {
            String row = "| origin | dest |";
            assertThat(DocRenderer.stripInlineMarkdown(row)).isEqualTo(row);
        }
    }

    // =========================================================================
    // Full page rendering (integration)
    // =========================================================================

    @Nested
    @DisplayName("Full-page rendering of the engine's reference pages")
    class FullPage {

        @Test
        @DisplayName("Rendered closure page has no Mermaid DSL and contains title")
        void closurePage() {
            String content = RelixDocs.referencePage("advanced/closure.md").orElseThrow();
            String rendered = DocRenderer.render(content, "advanced/closure.md");

            assertThat(rendered).contains("Transitive Closure");
            assertThat(rendered).doesNotContain("graph LR");
            assertThat(rendered).contains("[diagram — see docs/reference/advanced/closure.md");
        }

        @Test
        @DisplayName("Rendered select page has SYNTAX and DESCRIPTION sections")
        void selectPage() {
            String content = RelixDocs.referencePage("operators/select.md").orElseThrow();
            String rendered = DocRenderer.render(content, "operators/select.md");

            assertThat(rendered).contains("── SYNTAX");
            assertThat(rendered).contains("── DESCRIPTION");
        }

        @Test
        @DisplayName("Rendered page contains no raw fence markers (```)")
        void noFenceMarkers() {
            String content = RelixDocs.referencePage("advanced/sessionize.md").orElseThrow();
            String rendered = DocRenderer.render(content, "advanced/sessionize.md");

            assertThat(rendered).doesNotContain("```");
        }
    }
}
