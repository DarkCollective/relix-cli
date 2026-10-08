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
import com.darkcollective.relix.cli.io.Interruption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Where rows go and in what shape (design §3.3, §3.4, §3.6), end to end through
 * {@code Main}.
 */
@DisplayName("Output")
class OutputTest {

    private static final String NUMBERS = """
            N := [
            | x | s     |
            |---|-------|
            | 1 | one   |
            | 2 | two   |
            | 3 | NULL  |
            ];
            """;

    private static final String TWO_QUERIES = NUMBERS + """
            Small := { σ x < 2 (N) };
            Big := { σ x > 2 (N) };
            query Small;
            query Big;
            """;

    private static final String NATURALS = "source Naturals from generator { name: \"Naturals\" };";

    @TempDir
    Path dir;

    @Nested
    @DisplayName("choosing a format")
    class Choosing {

        @Test
        @DisplayName("a terminal gets a table")
        void terminalGetsTable() {
            var result = Cli.in(dir).run("-e", NUMBERS, "-e", "π x (N)");

            assertThat(result.status()).as(result.toString()).isZero();
            assertThat(result.out()).startsWith("── ").contains("(3 rows)");
        }

        @Test
        @DisplayName("a pipe gets tsv")
        void pipeGetsTsv() {
            var result = Cli.in(dir).piped().run("-e", NUMBERS, "-e", "N");

            assertThat(result.status()).as(result.toString()).isZero();
            assertThat(result.out()).isEqualTo("x\ts\n1\tone\n2\ttwo\n3\t\n");
        }

        @Test
        @DisplayName("-o chooses the format, on a terminal too")
        void minusO() {
            var result = Cli.in(dir).run("-o", "ndjson", "-e", NUMBERS, "-e", "σ x = 1 (N)");

            assertThat(result.out()).isEqualTo("{\"x\":1,\"s\":\"one\"}\n");
        }

        @Test
        @DisplayName("$RELIX_OUTPUT chooses it when -o does not, and -o beats it")
        void environment() {
            var fromEnv = Cli.in(dir).env("RELIX_OUTPUT", "csv").run("-e", NUMBERS, "-e", "σ x = 1 (N)");
            var fromOption = Cli.in(dir).env("RELIX_OUTPUT", "csv")
                    .run("--output=tsv", "-e", NUMBERS, "-e", "σ x = 1 (N)");

            assertThat(fromEnv.out()).isEqualTo("x,s\n1,one\n");
            assertThat(fromOption.out()).isEqualTo("x\ts\n1\tone\n");
        }

        @Test
        @DisplayName("an unknown format is a usage error, naming the ones there are")
        void unknownFormat() {
            var option = Cli.in(dir).run("-o", "xml", "-e", "N");
            var variable = Cli.in(dir).env("RELIX_OUTPUT", "xml").run("-e", "N");

            assertThat(option.status()).isEqualTo(2);
            assertThat(option.out()).isEmpty();
            assertThat(option.err()).contains("-o: unknown output format: 'xml'").contains("ndjson");
            assertThat(variable.status()).isEqualTo(2);
            assertThat(variable.err()).contains("$RELIX_OUTPUT: unknown output format: 'xml'");
        }

        @Test
        @DisplayName("relix run takes the same options")
        void runCommand() {
            var result = Cli.in(dir).run("run", "-o", "csv", "--no-header", "-e", NUMBERS, "-e", "σ x = 1 (N)");

            assertThat(result.out()).isEqualTo("1,one\n");
        }
    }

    @Nested
    @DisplayName("modifiers")
    class Modifiers {

        @Test
        @DisplayName("--no-header leaves out the header row")
        void noHeader() {
            var result = Cli.in(dir).piped().run("--no-header", "-e", NUMBERS, "-e", "N");

            assertThat(result.out()).isEqualTo("1\tone\n2\ttwo\n3\t\n");
        }

        @Test
        @DisplayName("--null spells NULL, in a pipe and in a table")
        void nullSpelling() {
            var tsv = Cli.in(dir).piped().run("--null=\\N", "-e", NUMBERS, "-e", "σ x = 3 (N)");
            var table = Cli.in(dir).run("--null=-", "--color=never", "-e", NUMBERS, "-e", "σ x = 3 (N)");

            assertThat(tsv.out()).isEqualTo("x\ts\n3\t\\N\n");
            assertThat(table.out()).contains(" 3  -").doesNotContain("NULL");
        }

        @Test
        @DisplayName("--color=auto colours a table on a terminal, unless $NO_COLOR is set")
        void colorAuto() {
            var terminal = Cli.in(dir).run("-e", NUMBERS, "-e", "N");
            var noColor = Cli.in(dir).env("NO_COLOR", "1").run("-e", NUMBERS, "-e", "N");
            var pipe = Cli.in(dir).piped().run("-o", "table", "-e", NUMBERS, "-e", "N");

            assertThat(terminal.out()).contains("\u001b[1m").contains("\u001b[2mNULL");
            assertThat(noColor.out()).doesNotContain("\u001b[");
            assertThat(pipe.out()).doesNotContain("\u001b[");
        }

        @Test
        @DisplayName("--color=always and --color=never decide whatever the terminal")
        void colorAlwaysNever() {
            var always = Cli.in(dir).piped().env("NO_COLOR", "1")
                    .run("-o", "table", "--color=always", "-e", NUMBERS, "-e", "N");
            var never = Cli.in(dir).run("--color=never", "-e", NUMBERS, "-e", "N");
            var bad = Cli.in(dir).run("--color=sometimes", "-e", "N");

            assertThat(always.out()).contains("\u001b[1m");
            assertThat(never.out()).doesNotContain("\u001b[");
            assertThat(bad.status()).isEqualTo(2);
            assertThat(bad.err()).contains("'sometimes' is not one of auto, always, never");
        }

        @Test
        @DisplayName("a machine format is never coloured")
        void machineFormatsUncoloured() {
            var result = Cli.in(dir).run("-o", "tsv", "--color=always", "-e", NUMBERS, "-e", "N");

            assertThat(result.out()).doesNotContain("\u001b[");
        }
    }

    @Nested
    @DisplayName("several queries")
    class SeveralQueries {

        @Test
        @DisplayName("a table prints each, labelled")
        void tablePrintsEach() {
            var result = Cli.in(dir).run("-e", TWO_QUERIES);

            assertThat(result.out()).contains("── Small ").contains("── Big ");
        }

        @Test
        @DisplayName("a machine format prints the last")
        void machinePrintsLast() {
            var result = Cli.in(dir).piped().run("-e", TWO_QUERIES);

            assertThat(result.status()).as(result.toString()).isZero();
            assertThat(result.out()).isEqualTo("x\ts\n3\t\n");
        }

        @Test
        @DisplayName("--query=NAME prints the one named")
        void queryByName() {
            var result = Cli.in(dir).piped().run("--query=Small", "-e", TWO_QUERIES);

            assertThat(result.out()).isEqualTo("x\ts\n1\tone\n");
        }

        @Test
        @DisplayName("--query naming no query is a usage error")
        void unknownQuery() {
            var result = Cli.in(dir).piped().run("--query=Medium", "-e", TWO_QUERIES);

            assertThat(result.status()).isEqualTo(2);
            assertThat(result.out()).isEmpty();
            assertThat(result.err()).isEqualTo("relix: -e: no query named 'Medium'\n");
        }

        @Test
        @DisplayName("--all with ndjson prints every query, each row tagged with its name")
        void allNdjson() {
            var result = Cli.in(dir).run("-o", "ndjson", "--all", "-e", TWO_QUERIES);

            assertThat(result.out()).isEqualTo("""
                    {"_query":"Small","x":1,"s":"one"}
                    {"_query":"Big","x":3,"s":null}
                    """);
        }

        @Test
        @DisplayName("--all with another machine format is a usage error")
        void allOtherFormat() {
            for (String format : new String[] {"tsv", "csv", "json"}) {
                var result = Cli.in(dir).run("-o", format, "--all", "-e", TWO_QUERIES);

                assertThat(result.status()).as(format).isEqualTo(2);
                assertThat(result.out()).as(format).isEmpty();
                assertThat(result.err()).as(format).contains("--all prints several queries");
            }
        }

        @Test
        @DisplayName("--all and --query together are a usage error")
        void allAndQuery() {
            var result = Cli.in(dir).run("-o", "ndjson", "--all", "--query=Big", "-e", TWO_QUERIES);

            assertThat(result.status()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("assertions")
    class Assertions {

        @Test
        @DisplayName("--fail-empty exits 1 on no rows, and 0 on some")
        void failEmpty() {
            var empty = Cli.in(dir).piped().run("--fail-empty", "-e", NUMBERS, "-e", "σ x > 9 (N)");
            var some = Cli.in(dir).piped().run("--fail-empty", "-e", NUMBERS, "-e", "N");

            assertThat(empty.status()).isEqualTo(1);
            assertThat(empty.out()).as("the header row is still written").isEqualTo("x\ts\n");
            assertThat(empty.err()).isEmpty();
            assertThat(some.status()).isZero();
        }

        @Test
        @DisplayName("--fail-rows exits 1 on any row, and 0 on none")
        void failRows() {
            var some = Cli.in(dir).piped().run("--fail-rows", "-e", NUMBERS, "-e", "σ x = 1 (N)");
            var none = Cli.in(dir).piped().run("--fail-rows", "-e", NUMBERS, "-e", "σ x > 9 (N)");

            assertThat(some.status()).isEqualTo(1);
            assertThat(some.err()).isEmpty();
            assertThat(none.status()).isZero();
        }

        @Test
        @DisplayName("an assertion counts only the query printed")
        void onlyPrinted() {
            var result = Cli.in(dir).piped().run("--fail-rows", "--query=Big", "-e",
                    NUMBERS + "Small := { σ x < 2 (N) }; Big := { σ x > 9 (N) }; query Small; query Big;");

            assertThat(result.status()).isZero();
        }

        @Test
        @DisplayName("a failed script is reported as itself, not as an assertion")
        void failureWins() {
            var result = Cli.in(dir).piped().run("--fail-empty", "-e", "σ x > 1 (Nope)");

            assertThat(result.status()).isEqualTo(3);
        }

        @Test
        @DisplayName("--fail-empty and --fail-rows together are a usage error")
        void both() {
            var result = Cli.in(dir).run("--fail-empty", "--fail-rows", "-e", "N");

            assertThat(result.status()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("pipe and signal discipline")
    class Discipline {

        @Test
        @Timeout(60)
        @DisplayName("relix -e 'Naturals' | head ends when head does: exit 141, nothing said")
        void head() {
            var head = new Head(5);

            var result = Cli.in(dir).stdout(head).run("-e", NATURALS, "-e", "Naturals");

            assertThat(result.status()).isEqualTo(141);
            assertThat(result.err()).isEmpty();
        }

        @Test
        @Timeout(60)
        @DisplayName("line-buffered, each row reaches the reader as it is made, and the run ends at the first refused")
        void headLineBuffered() {
            var head = new Head(5);

            var result = Cli.in(dir).stdout(head).run("--line-buffered", "-e", NATURALS, "-e", "Naturals");

            assertThat(result.status()).isEqualTo(141);
            assertThat(result.err()).isEmpty();
            assertThat(result.out()).isEqualTo("n\n0\n1\n2\n3\n");
        }

        @Test
        @Timeout(60)
        @DisplayName("an endless result in a table is refused rather than collected forever")
        void endlessTable() {
            var result = Cli.in(dir).run("-e", NATURALS, "-e", "Naturals");

            assertThat(result.status()).isEqualTo(3);
            assertThat(result.out()).isEmpty();
        }

        @Test
        @Timeout(60)
        @DisplayName("an interrupt stops the query in flight: exit 130, nothing said")
        void interrupt() {
            Interruption interruption = new Interruption();
            AtomicLong written = new AtomicLong();
            OutputStream reader = new OutputStream() {
                @Override
                public void write(int b) {
                    write(new byte[] {(byte) b}, 0, 1);
                }

                @Override
                public void write(byte[] b, int off, int len) {
                    // The first rows to arrive set off the interrupt, from another thread,
                    // as a signal's shutdown hook would.
                    if (written.getAndAdd(len) == 0) {
                        Thread.ofPlatform().start(interruption::interrupt);
                    }
                }
            };

            var result = Cli.in(dir).stdout(reader).interruption(interruption)
                    .run("--line-buffered", "-e", NATURALS, "-e", "Naturals");

            assertThat(result.status()).isEqualTo(130);
            assertThat(result.err()).isEmpty();
            assertThat(interruption.interrupted()).isTrue();
        }
    }

    /** {@code head -n}: reads {@code lines} lines, then goes away. */
    static final class Head extends OutputStream {
        private int lines;

        Head(int lines) {
            this.lines = lines;
        }

        @Override
        public void write(int b) throws IOException {
            if (lines <= 0) {
                throw new IOException("Broken pipe");
            }
            if (b == '\n') {
                lines--;
            }
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            for (int i = 0; i < len; i++) {
                write(b[off + i]);
            }
        }
    }
}
