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
import com.darkcollective.relix.cli.Cli;
import com.darkcollective.relix.embed.Diagnostic;
import com.darkcollective.relix.semantic.Severity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Diagnostics on stderr as {@code FILE:LINE:COL: severity: message} (design §3.5).
 */
@DisplayName("Diagnostics")
class DiagnosticsTest {

    private static final String TABLE = """
            T := [
            | x |
            |---|
            | 1 |
            ];
            """;

    @TempDir
    Path dir;

    @Nested
    @DisplayName("through the command")
    class EndToEnd {

        @Test
        @DisplayName("an error in a file names the file, line and column, and nothing reaches stdout")
        void inAFile() throws IOException {
            Files.writeString(dir.resolve("a.relix"), TABLE + "query { σ y > 0 (T) };\n");

            var result = Cli.in(dir).run("a.relix");

            assertThat(result.status()).isEqualTo(3);
            assertThat(result.out()).isEmpty();
            assertThat(result.err()).startsWith("a.relix:6:11: error: ");
        }

        @Test
        @DisplayName("an error in a bare -e expression points into the expression as typed")
        void inABareExpression() {
            var result = Cli.in(dir).run("-e", "σ y > 0 (relix.relations)");

            assertThat(result.err()).startsWith("-e:1:3: error: ");
        }

        @Test
        @DisplayName("with several -e texts, an error names which one")
        void inTheSecondExpression() {
            var result = Cli.in(dir).run("-e", TABLE, "-e", "σ y > 0 (T)");

            assertThat(result.err()).startsWith("-e#2:1:3: error: ");
        }

        @Test
        @DisplayName("standard input goes by <stdin>")
        void onStdin() {
            var result = Cli.in(dir).stdin(TABLE + "query { σ y > 0 (T) };").run();

            assertThat(result.err()).startsWith("<stdin>:6:11: error: ");
        }

        @Test
        @DisplayName("-q still prints errors")
        void quietKeepsErrors() {
            var result = Cli.in(dir).run("-q", "-e", "σ y > 0 (relix.relations)");

            assertThat(result.err()).startsWith("-e:1:3: error: ");
        }

        @Test
        @DisplayName("-vv adds timings on stderr, and stdout is unchanged")
        void timings() {
            var plain = Cli.in(dir).run("-e", "relix.relations");
            var timed = Cli.in(dir).run("-vv", "-e", "relix.relations");

            assertThat(timed.out()).isEqualTo(plain.out());
            assertThat(timed.err()).contains("relix: -e: analysed: ").contains(" ms\n");
        }

        @Test
        @DisplayName("-q and -v together are a usage error")
        void quietAndVerbose() {
            var result = Cli.in(dir).run("-q", "-v", "-e", "relix.relations");

            assertThat(result.status()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("by verbosity")
    class Verbosity {

        private final ByteArrayOutputStream err = new ByteArrayOutputStream();

        private Reporter reporter(Reporter.Verbosity verbosity) {
            return new Reporter(new PrintStream(err, true, StandardCharsets.UTF_8), verbosity);
        }

        private String printed() {
            return err.toString(StandardCharsets.UTF_8);
        }

        private final Diagnostic warning = new Diagnostic("view 'V' shadows a relation",
                Optional.of(new SourceLocation("<session>", 2, 1)), Severity.WARNING);

        @Test
        @DisplayName("a warning is printed by default")
        void warningByDefault() {
            reporter(Reporter.Verbosity.NORMAL).diagnostic(new Reporter.Place("a.relix", 2, 1), warning);

            assertThat(printed()).isEqualTo("a.relix:2:1: warning: view 'V' shadows a relation\n");
        }

        @Test
        @DisplayName("-q silences warnings")
        void quietSilencesWarnings() {
            reporter(Reporter.Verbosity.QUIET).diagnostic(new Reporter.Place("a.relix", 2, 1), warning);

            assertThat(printed()).isEmpty();
        }

        @Test
        @DisplayName("notices need -v, timings need -vv")
        void noticesAndTimings() {
            Reporter normal = reporter(Reporter.Verbosity.NORMAL);
            normal.notice("loaded JDBC driver org.h2.Driver");
            normal.timing("a.relix: analysed", Duration.ofMillis(3));
            assertThat(printed()).isEmpty();

            Reporter verbose = reporter(Reporter.Verbosity.VERBOSE);
            verbose.notice("loaded JDBC driver org.h2.Driver");
            verbose.timing("a.relix: analysed", Duration.ofMillis(3));
            assertThat(printed()).isEqualTo("relix: loaded JDBC driver org.h2.Driver\n");

            reporter(Reporter.Verbosity.TIMINGS).timing("a.relix: analysed", Duration.ofMillis(3));
            assertThat(printed()).endsWith("relix: a.relix: analysed: 3 ms\n");
        }

        @Test
        @DisplayName("a place without a line is the file alone")
        void placeWithoutLine() {
            assertThat(new Reporter.Place("a.relix", 0, 0)).hasToString("a.relix");
            assertThat(new Reporter.Place("a.relix", 4, 0)).hasToString("a.relix:4");
        }
    }
}
