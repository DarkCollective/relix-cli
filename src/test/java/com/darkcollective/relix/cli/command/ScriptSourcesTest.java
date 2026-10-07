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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Where the script comes from (design §3.1), end to end through {@code Main}.
 */
@DisplayName("Where the script comes from")
class ScriptSourcesTest {

    private static final String NUMBERS = """
            N := [
            | x |
            |---|
            | 1 |
            | 2 |
            | 3 |
            ];
            """;

    @TempDir
    Path dir;

    @Test
    @DisplayName("a bare -e expression runs as a query")
    void bareExpression() {
        var result = Cli.in(dir).run("-e", "λ 1 (relix.functions)");

        assertThat(result.status()).as(result.toString()).isZero();
        assertThat(result.out()).contains("(1 row)");
    }

    @Test
    @DisplayName("several -e texts are one script, statements and expressions alike")
    void severalExpressionsAreOneScript() {
        var result = Cli.in(dir).run("-e", NUMBERS, "-e", "σ x > 1 (N)");

        assertThat(result.status()).as(result.toString()).isZero();
        assertThat(result.out()).contains(" 2\n").contains(" 3\n").contains("(2 rows)");
        assertThat(result.err()).isEmpty();
    }

    @Test
    @DisplayName("a script file runs, and its rows go to stdout alone")
    void scriptFile() throws IOException {
        write("a.relix", NUMBERS + "query { σ x = 2 (N) };\n");

        var result = Cli.in(dir).run("a.relix");

        assertThat(result.status()).as(result.toString()).isZero();
        assertThat(result.out()).contains(" 2\n").contains("(1 row)");
        assertThat(result.err()).isEmpty();
    }

    @Test
    @DisplayName("each script file runs in a session of its own")
    void filesAreIndependent() throws IOException {
        write("a.relix", NUMBERS + "query { N };\n");
        write("b.relix", "query { N };\n");

        var result = Cli.in(dir).run("a.relix", "b.relix");

        assertThat(result.out()).as("a.relix still runs").contains("(3 rows)");
        assertThat(result.err()).startsWith("b.relix:");
        assertThat(result.status()).isEqualTo(3);
    }

    @Test
    @DisplayName("- names standard input")
    void dashIsStdin() {
        var result = Cli.in(dir).stdin(NUMBERS + "query { N };").run("-");

        assertThat(result.status()).as(result.toString()).isZero();
        assertThat(result.out()).contains("(3 rows)");
    }

    @Test
    @DisplayName("standard input is read when nothing is named and it is not a terminal")
    void stdinWhenPiped() {
        var result = Cli.in(dir).stdin(NUMBERS + "query { N };").run();

        assertThat(result.status()).as(result.toString()).isZero();
        assertThat(result.out()).contains("(3 rows)");
    }

    @Test
    @DisplayName("with nothing named and a terminal on stdin, usage goes to stderr and the exit is 2")
    void nothingToRun() {
        var result = Cli.in(dir).run();

        assertThat(result.status()).isEqualTo(2);
        assertThat(result.out()).isEmpty();
        assertThat(result.err()).startsWith("Usage: relix");
    }

    @Test
    @DisplayName("-e and script files together are a usage error")
    void expressionAndFiles() throws IOException {
        write("a.relix", NUMBERS);

        var result = Cli.in(dir).run("-e", "N", "a.relix");

        assertThat(result.status()).isEqualTo(2);
        assertThat(result.err()).contains("not both");
    }

    @Test
    @DisplayName("standard input named twice is a usage error")
    void stdinTwice() {
        var result = Cli.in(dir).stdin("query { relix.relations };").run("-", "-");

        assertThat(result.status()).isEqualTo(2);
        assertThat(result.err()).contains("only once");
    }

    @Test
    @DisplayName("a script file that is not there is a usage error")
    void missingFile() {
        var result = Cli.in(dir).run("nope.relix");

        assertThat(result.status()).isEqualTo(2);
        assertThat(result.err()).isEqualTo("relix: nope.relix: no such file\n");
    }

    @Test
    @DisplayName("relix run is the default command")
    void runIsTheDefault() throws IOException {
        write("a.relix", NUMBERS + "query { N };\n");

        var implicit = Cli.in(dir).run("a.relix");
        var explicit = Cli.in(dir).run("run", "a.relix");

        assertThat(explicit.status()).isZero();
        assertThat(explicit.out()).isEqualTo(implicit.out());
    }

    @Test
    @DisplayName("-C DIR is where relative script paths start")
    void minusC() throws IOException {
        Files.createDirectories(dir.resolve("sub"));
        write("sub/a.relix", NUMBERS + "query { N };\n");

        var result = Cli.in(dir).run("-C", "sub", "a.relix");

        assertThat(result.status()).as(result.toString()).isZero();
        assertThat(result.out()).contains("(3 rows)");
    }

    @Test
    @DisplayName("-C naming no directory is a usage error")
    void minusCMissing() {
        var result = Cli.in(dir).run("-C", "nowhere", "-e", "relix.relations");

        assertThat(result.status()).isEqualTo(2);
        assertThat(result.err()).contains("-C nowhere: not a directory");
    }

    @Test
    @DisplayName("a script's relative source path resolves beside the script, wherever relix starts")
    void relativeSourceBesideScript() throws IOException {
        Files.createDirectories(dir.resolve("project"));
        write("project/t.csv", "x\n1\n2\n");
        write("project/a.relix", """
                source T from csv("t.csv") { header: true, schema: { x: NUMBER } };
                query { T };
                """);

        var result = Cli.in(dir).run("project/a.relix");

        assertThat(result.status()).as(result.toString()).isZero();
        assertThat(result.out()).contains("(2 rows)");
    }

    private void write(String path, String text) throws IOException {
        Files.writeString(dir.resolve(path), text);
    }
}
