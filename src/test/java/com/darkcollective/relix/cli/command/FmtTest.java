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
package com.darkcollective.relix.cli.command;

import com.darkcollective.relix.cli.Cli;
import com.darkcollective.relix.cli.Examples;
import com.darkcollective.relix.embed.Relix;
import com.darkcollective.relix.lang.ast.ScriptPrinter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code relix fmt} (design §3), end to end through {@code Main}: over every example
 * script, formatting is idempotent, keeps the statements and keeps the comments.
 */
@DisplayName("relix fmt")
class FmtTest {

    static final List<String> EXAMPLES = Examples.NAMES;

    /** A line comment or a block comment, as the examples write them. */
    private static final Pattern COMMENT = Pattern.compile("--[^\\n]*|/\\*.*?\\*/", Pattern.DOTALL);

    @TempDir
    Path dir;

    private String fmt(String text, String... options) {
        String[] args = new String[options.length + 1];
        args[0] = "fmt";
        System.arraycopy(options, 0, args, 1, options.length);
        var result = Cli.in(dir).stdin(text).run(args);
        assertThat(result.status()).as(result.toString()).isZero();
        assertThat(result.err()).isEmpty();
        return result.out();
    }

    /** The statements a text parses to, each as the printer writes it, which ignores where they were. */
    private static List<String> statements(String text) {
        return Relix.parse(text).statements().stream().map(ScriptPrinter::print).toList();
    }

    /** The comments in a text, leaving out its inline tables' rows, whose rules are not comments. */
    private static List<String> comments(String text) {
        String code = text.lines().filter(line -> !line.strip().startsWith("|"))
                .collect(java.util.stream.Collectors.joining("\n"));
        Matcher m = COMMENT.matcher(code);
        List<String> found = new java.util.ArrayList<>();
        while (m.find()) {
            found.add(m.group().stripTrailing());
        }
        return found;
    }

    @Nested
    @DisplayName("over every example")
    class OverTheExamples {

        @ParameterizedTest(name = "{0}")
        @FieldSource("com.darkcollective.relix.cli.command.FmtTest#EXAMPLES")
        @DisplayName("is idempotent")
        void idempotent(String example) {
            String once = fmt(Examples.text(example));

            assertThat(fmt(once)).isEqualTo(once);
        }

        @ParameterizedTest(name = "{0}")
        @FieldSource("com.darkcollective.relix.cli.command.FmtTest#EXAMPLES")
        @DisplayName("parses to the same statements")
        void sameStatements(String example) {
            String original = Examples.text(example);

            assertThat(statements(fmt(original))).isEqualTo(statements(original));
            assertThat(Relix.parse(fmt(original)).namespace()).isEqualTo(Relix.parse(original).namespace());
        }

        @ParameterizedTest(name = "{0}")
        @FieldSource("com.darkcollective.relix.cli.command.FmtTest#EXAMPLES")
        @DisplayName("keeps every comment, in order")
        void keepsComments(String example) {
            String original = Examples.text(example);

            assertThat(comments(fmt(original))).isEqualTo(comments(original));
        }

        @ParameterizedTest(name = "{0}")
        @FieldSource("com.darkcollective.relix.cli.command.FmtTest#EXAMPLES")
        @DisplayName("--keywords parses to the same statements, and is idempotent")
        void keywords(String example) {
            String original = Examples.text(example);
            String keywords = fmt(original, "--keywords");

            assertThat(statements(keywords)).isEqualTo(statements(original));
            assertThat(fmt(keywords, "--keywords")).isEqualTo(keywords);
            assertThat(comments(keywords)).isEqualTo(comments(original));
        }

        @ParameterizedTest(name = "{0}")
        @FieldSource("com.darkcollective.relix.cli.command.FmtTest#EXAMPLES")
        @DisplayName("--glyphs turns --keywords' output back into the default's")
        void glyphs(String example) {
            String original = Examples.text(example);

            assertThat(fmt(fmt(original, "--keywords"), "--glyphs")).isEqualTo(fmt(original));
        }
    }

    @Nested
    @DisplayName("the layout")
    class Layout {

        @Test
        @DisplayName("prints each statement canonically, keeping the comments and blank lines around it")
        void canonical() {
            String text = """
                    -- header
                    namespace demo;   -- the namespace


                    N := [| x |
                          | 2 |
                          | 10 |];
                    /* a view */
                    Big := {
                        σ x > 1
                          (N)
                    };  -- trailing
                    query Big;
                    """;

            assertThat(fmt(text)).isEqualTo("""
                    -- header
                    namespace demo; -- the namespace

                    N := [
                    | x   |
                    |-----|
                    | 2   |
                    | 10  |
                    ];
                    /* a view */
                    Big := { σ x > 1 (N) }; -- trailing
                    query Big;
                    """);
        }

        @Test
        @DisplayName("keeps a comment at the end of a table's row on that row, its columns aligned")
        void rowComment() {
            String text = """
                    Goals := [
                    | id | minute |
                    |----|--------|
                    | 1 | 12 |  -- the first
                    | 22 | 5 |
                    | 3 | 105 |  -- and the third
                    ];
                    """;

            assertThat(fmt(text)).isEqualTo("""
                    Goals := [
                    | id  | minute |
                    |-----|--------|
                    | 1   | 12     |  -- the first
                    | 22  | 5      |
                    | 3   | 105    |  -- and the third
                    ];
                    """);
        }

        @Test
        @DisplayName("keeps a blank line between a comment and what follows it")
        void blankAfterComment() {
            String text = "-- header\n\nnamespace demo;\n-- about N\n\nquery N;\n";

            assertThat(fmt(text)).isEqualTo(text);
        }

        @Test
        @DisplayName("formats a statement with a comment inside an expression, keeping the comment")
        void innerComment() {
            String text = "Big := {\n    σ x > 1 -- only the big ones\n      (N)\n};\nquery   Big ;\n";

            String formatted = fmt(text);

            assertThat(formatted).doesNotContain("    σ").endsWith("\nquery Big;\n");
            assertThat(comments(formatted)).containsExactly("-- only the big ones");
            assertThat(statements(formatted)).isEqualTo(statements(text));
            assertThat(fmt(formatted)).isEqualTo(formatted);
        }

        @Test
        @DisplayName("does not take -- in a string, a table cell or a table's rule for a comment")
        void notComments() {
            String text = "T := [\n| a | b |\n|---|---|\n| 1 | x -- y |\n];\nQ := { σ b = \"--\" (T) };\n";

            String formatted = fmt(text);

            assertThat(formatted).contains("| 1   | x -- y |").contains("Q := { σ b = \"--\" (T) };");
            assertThat(statements(formatted)).isEqualTo(statements(text));
        }

        @Test
        @DisplayName("--keywords writes the ASCII spelling, and leaves glyphs in strings alone")
        void keywords() {
            String formatted = fmt("Q := { π a (σ a ≠ \"σ\" ∧ a ≥ 1 (T ⋈ U)) };\n", "--keywords");

            assertThat(formatted).isEqualTo("Q := { PROJECT a (SELECT (a != \"σ\") AND (a >= 1) ((T) JOIN (U))) };\n");
        }

        @Test
        @DisplayName("an empty script stays empty, and a comment alone stays")
        void empty() {
            assertThat(fmt("")).isEmpty();
            assertThat(fmt("  -- just this\n\n")).isEqualTo("-- just this\n");
        }
    }

    @Nested
    @DisplayName("files")
    class Files_ {

        private Path write(String name, String text) throws IOException {
            return Files.writeString(dir.resolve(name), text, StandardCharsets.UTF_8);
        }

        @Test
        @DisplayName("prints each file's formatted text to stdout, changing none")
        void prints() throws IOException {
            write("a.relix", "query   { N } ;\n");
            write("b.relix", "query { M };\n");

            var result = Cli.in(dir).run("fmt", "a.relix", "b.relix");

            assertThat(result.status()).isZero();
            assertThat(result.out()).isEqualTo("query { N };\nquery { M };\n");
            assertThat(Files.readString(dir.resolve("a.relix"))).isEqualTo("query   { N } ;\n");
        }

        @Test
        @DisplayName("-w rewrites the files that change, and only those")
        void write() throws IOException {
            Path changed = write("a.relix", "query   { N } ;\n");
            Path same = write("b.relix", "query { M };\n");
            var before = Files.getLastModifiedTime(same);

            var result = Cli.in(dir).run("fmt", "-w", "a.relix", "b.relix");

            assertThat(result.status()).as(result.toString()).isZero();
            assertThat(result.out()).isEmpty();
            assertThat(Files.readString(changed)).isEqualTo("query { N };\n");
            assertThat(Files.getLastModifiedTime(same)).isEqualTo(before);
        }

        @Test
        @DisplayName("--check exits 0 when nothing would change")
        void checkClean() throws IOException {
            write("b.relix", "query { M };\n");

            var result = Cli.in(dir).run("fmt", "--check", "b.relix");

            assertThat(result.status()).isZero();
            assertThat(result.out()).isEmpty();
        }

        @Test
        @DisplayName("--check names each file that would change, changes none, and exits 1")
        void checkDirty() throws IOException {
            write("a.relix", "query   { N } ;\n");
            write("b.relix", "query { M };\n");

            var result = Cli.in(dir).run("fmt", "--check", "a.relix", "b.relix");

            assertThat(result.status()).isEqualTo(1);
            assertThat(result.out()).isEqualTo("a.relix\n");
            assertThat(Files.readString(dir.resolve("a.relix"))).isEqualTo("query   { N } ;\n");
        }

        @Test
        @DisplayName("a file that does not parse is reported at its place, left alone, and exits 3")
        void unparsable() throws IOException {
            write("bad.relix", "N := [\n| x |\n];\nquery { σ (N };\n");
            write("b.relix", "query   { M };\n");

            var result = Cli.in(dir).run("fmt", "-w", "bad.relix", "b.relix");

            assertThat(result.status()).isEqualTo(3);
            assertThat(result.err()).startsWith("relix: bad.relix:4:");
            assertThat(Files.readString(dir.resolve("b.relix"))).isEqualTo("query { M };\n");
        }

        @Test
        @DisplayName("-w with standard input, and -w with --check, are usage errors")
        void usage() {
            assertThat(Cli.in(dir).stdin("query { N };").run("fmt", "-w").status()).isEqualTo(2);
            assertThat(Cli.in(dir).run("fmt", "-w", "--check", "a.relix").status()).isEqualTo(2);
            assertThat(Cli.in(dir).run("fmt", "--glyphs", "--keywords", "a.relix").status()).isEqualTo(2);
        }

        @Test
        @DisplayName("with nothing named and a terminal on stdin, prints usage and exits 2")
        void nothing() {
            assertThat(Cli.in(dir).run("fmt").status()).isEqualTo(2);
        }
    }
}
