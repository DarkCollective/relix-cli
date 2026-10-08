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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.ObjectReadContext;
import tools.jackson.core.json.JsonFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The report commands, {@code check}, {@code explain}, {@code optimize}, {@code trace},
 * {@code bundle}, {@code ir} and {@code provenance}, and {@code run --trace} (design §3,
 * §4), end to end through {@code Main} over the example scripts.
 */
@DisplayName("Report commands")
class ReportCommandsTest {

    static final List<String> EXAMPLES = Examples.NAMES;

    /** A script whose second statement names a relation nothing declares. */
    private static final String BROKEN = """
            N := [
            | x |
            |---|
            | 1 |
            ];
            Bad := { σ x > 1 (Missing) };
            query Bad;
            """;

    private static final JsonFactory JSON = new JsonFactory();

    @TempDir
    Path dir;

    @BeforeEach
    void examples() throws IOException {
        Examples.copyTo(dir);
        Files.writeString(dir.resolve("broken.relix"), BROKEN, StandardCharsets.UTF_8);
    }

    private Cli cli() {
        return Cli.in(dir);
    }

    /** Whether {@code text} is one JSON value and nothing else. */
    private static boolean isJson(String text) {
        try (JsonParser parser = JSON.createParser(ObjectReadContext.empty(), text)) {
            parser.nextToken();
            parser.skipChildren();
            return parser.nextToken() == null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Nested
    @DisplayName("every report command")
    class Every {

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"explain", "optimize", "trace", "bundle", "ir", "provenance"})
        @DisplayName("writes its report of each example to stdout, and nothing to stderr")
        void overTheExamples(String command) {
            for (String example : EXAMPLES) {
                // optimize warns about the rewrites the engine cannot write back.
                var result = cli().piped().run(command, example);

                assertThat(result.status()).as(example + "\n" + result).isZero();
                assertThat(result.out()).as(example).isNotBlank();
                if (!command.equals("optimize")) {
                    assertThat(result.err()).as(example).isEmpty();
                }
            }
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"check", "explain", "optimize", "trace", "ir", "provenance"})
        @DisplayName("prints nothing for a script that does not analyse, reports it, and exits 3")
        void broken(String command) {
            var result = cli().run(command, "broken.relix");

            assertThat(result.status()).as(result.toString()).isEqualTo(3);
            assertThat(result.out()).isEmpty();
            assertThat(result.err()).contains("broken.relix:6:").contains("Missing");
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"check", "explain", "optimize", "trace", "bundle", "ir", "provenance"})
        @DisplayName("reads -e and standard input as run does")
        void sources(String command) {
            var fromE = cli().run(command, "-e", "λ 2 (relix.functions)");
            var fromStdin = cli().stdin("query { λ 2 (relix.functions) };").run(command);

            assertThat(fromE.status()).as(fromE.toString()).isZero();
            assertThat(fromStdin.status()).as(fromStdin.toString()).isZero();
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"explain", "trace", "provenance"})
        @DisplayName("--query names one query, and an unknown one is a usage error")
        void query(String command) {
            var one = cli().run(command, "--query", "LondonBorrowers", "library.relix");
            var unknown = cli().run(command, "--query", "Nope", "library.relix");

            assertThat(one.status()).as(one.toString()).isZero();
            assertThat(unknown.status()).isEqualTo(2);
            assertThat(unknown.err()).contains("library.relix: no query named 'Nope'");
        }
    }

    @Nested
    @DisplayName("check")
    class Check {

        @Test
        @DisplayName("is silent and exits 0 over scripts that analyse")
        void clean() {
            var result = cli().run(new String[] {"check", "introspection.relix", "league.relix",
                "library.relix", "pokemon.relix"});

            assertThat(result.status()).as(result.toString()).isZero();
            assertThat(result.out()).isEmpty();
            assertThat(result.err()).isEmpty();
        }

        @Test
        @DisplayName("as xargs runs it, exits 3 when one file of many is broken, naming only that file")
        void xargs() {
            var result = cli().run("check", "league.relix", "broken.relix", "library.relix");

            assertThat(result.status()).isEqualTo(3);
            assertThat(result.out()).isEmpty();
            assertThat(result.err()).isEqualTo("broken.relix:6:19: error: Undefined relation: 'Missing'\n");
        }

        @Test
        @DisplayName("--json writes every script's diagnostics to stdout as one document, and nothing to stderr")
        void json() {
            var result = cli().run("check", "--json", "league.relix", "broken.relix");

            assertThat(result.status()).isEqualTo(3);
            assertThat(result.err()).isEmpty();
            assertThat(isJson(result.out())).as(result.out()).isTrue();
            assertThat(result.out()).isEqualTo("{\"diagnostics\":[{\"file\":\"broken.relix\",\"line\":6,"
                    + "\"column\":19,\"severity\":\"error\",\"message\":\"Undefined relation: 'Missing'\"}]}\n");
        }

        @Test
        @DisplayName("--json over a clean script is an empty list")
        void jsonClean() {
            var result = cli().run("check", "--json", "league.relix");

            assertThat(result.status()).isZero();
            assertThat(result.out()).isEqualTo("{\"diagnostics\":[]}\n");
        }

        @Test
        @DisplayName("places a diagnostic in an -e fragment")
        void expression() {
            var result = cli().run("check", "-e", "σ x > 1 (Missing)");

            assertThat(result.status()).isEqualTo(3);
            assertThat(result.err()).startsWith("-e:1:");
        }
    }

    @Nested
    @DisplayName("explain")
    class Explain {

        @Test
        @DisplayName("prints one query's plan as the engine draws it")
        void one() {
            var result = cli().run("explain", "--query", "LondonBorrowers", "library.relix");

            assertThat(result.out()).startsWith("Join SEMI/HASH").contains("Scan Members").contains("Scan Loans")
                    .doesNotContain("-- query");
        }

        @Test
        @DisplayName("heads each plan with its query when there are several")
        void several() {
            var result = cli().run("explain", "library.relix");

            assertThat(result.out()).contains("-- query OnLoanDetails\n").contains("-- query LondonBorrowers\n");
        }

        @Test
        @DisplayName("--json writes one object per query per line")
        void json() {
            var result = cli().run("explain", "--json", "library.relix");

            List<String> lines = result.out().lines().toList();
            assertThat(lines).hasSize(7).allSatisfy(line -> assertThat(isJson(line)).as(line).isTrue());
            assertThat(lines.getLast()).startsWith("{\"script\":\"library.relix\",\"query\":\"LondonBorrowers\","
                    + "\"plan\":{\"op\":\"Join\"");
        }
    }

    @Nested
    @DisplayName("optimize")
    class Optimize {

        @ParameterizedTest(name = "{0}")
        @FieldSource("com.darkcollective.relix.cli.command.ReportCommandsTest#EXAMPLES")
        @DisplayName("writes a script that returns what the original does")
        void sameRows(String example) throws IOException {
            var optimized = cli().run("optimize", example);
            Files.writeString(dir.resolve("optimized.relix"), optimized.out(), StandardCharsets.UTF_8);

            var before = cli().run("-o", "ndjson", "--all", example);
            var after = cli().run("-o", "ndjson", "--all", "optimized.relix");

            assertThat(optimized.status()).as(optimized.toString()).isZero();
            assertThat(after.status()).as(after.toString()).isZero();
            assertThat(untagged(after.out())).isEqualTo(untagged(before.out()));
        }

        /** The rows without the label each is tagged with, which a rewritten query may lose. */
        private static String untagged(String ndjson) {
            return ndjson.replaceAll("\\{\"_query\":\"[^\"]*\",", "{");
        }

        @Test
        @DisplayName("rewrites the query statements, and keeps every other statement and comment as written")
        void keepsTheRest() {
            var result = cli().run("optimize", "library.relix");
            String original = Examples.text("library.relix");

            assertThat(result.out()).contains("query { ρ LondonBorrowers (").doesNotContain("query LondonBorrowers;");
            String declarations = original.substring(0, original.indexOf("query OnLoanDetails;"));
            assertThat(result.out()).startsWith(declarations);
        }

        @Test
        @DisplayName("leaves a query as written, with a warning, when the engine cannot write its rewrite back")
        void unwritable() {
            var result = cli().run("optimize", "pokemon.relix");

            assertThat(result.status()).isZero();
            assertThat(result.err()).contains("pokemon.relix: PichuFuture: the engine cannot write its rewrite");
            assertThat(result.out()).contains("query PichuFuture;");
        }

        @Test
        @DisplayName("works as a filter on standard input")
        void filter() {
            var result = cli().stdin("N := [\n| x |\n|---|\n| 1 |\n];\nquery { π x (σ x > 0 (N)) };\n")
                    .run("optimize");

            assertThat(result.status()).as(result.toString()).isZero();
            assertThat(result.out()).startsWith("N := [\n").contains("query { ").endsWith(" };\n");
        }

        @Test
        @DisplayName("--report prints the rules that fired and the trees before and after")
        void report() {
            var result = cli().run("optimize", "--report", "library.relix");

            assertThat(result.out()).contains("RELIX OPTIMIZER  namespace=library")
                    .contains("INLINE-001").contains("── LondonBorrowers").contains("before:").contains("after:");
        }
    }

    @Nested
    @DisplayName("trace")
    class Trace {

        @Test
        @DisplayName("prints each query's events and ends each with the rows it delivered, not the rows")
        void feed() {
            var result = cli().run("trace", "--query", "LondonBorrowers", "library.relix");

            assertThat(result.out()).contains("[OPTIMIZE]  query[1]  INLINE-001  view 'LondonBorrowers' inlined")
                    .contains("[EXECUTE ]  LondonBorrowers  ROWS  query delivered 3 rows")
                    .doesNotContain("Alice");
        }

        @Test
        @DisplayName("ends the feed of every query of the script")
        void everyQuery() {
            var result = cli().run("trace", "library.relix");

            assertThat(result.out().lines().filter(l -> l.contains("  ROWS  "))).hasSize(7);
        }
    }

    @Nested
    @DisplayName("run --trace")
    class RunTrace {

        @Test
        @DisplayName("writes the rows to stdout and the events to stderr in one run")
        void stderr() {
            var result = cli().piped().run("--trace", "--query", "LondonBorrowers", "library.relix");

            assertThat(result.status()).as(result.toString()).isZero();
            assertThat(result.out()).isEqualTo("member_id\tname\tcity\tjoined\n1\tAlice\tLondon\t2019\n"
                    + "3\tCarol\tLondon\t2021\n5\tEve\tLondon\t2022\n");
            assertThat(result.err()).contains("[PLAN    ]  LondonBorrowers  JOIN")
                    .contains("ROWS  query delivered 3 rows");
        }

        @Test
        @DisplayName("traces a table too")
        void table() {
            var result = cli().run("run", "--trace", "--query", "LondonBorrowers", "library.relix");

            assertThat(result.out()).contains("Alice").contains("(3 rows)");
            assertThat(result.err()).contains("ROWS  query delivered 3 rows");
        }

        @Test
        @DisplayName("--trace=FILE writes the events to FILE, and a bare --trace never takes the script for its file")
        void file() throws IOException {
            var result = cli().piped().run("run", "--trace=events.log", "--query", "LondonBorrowers",
                    "library.relix");

            assertThat(result.status()).as(result.toString()).isZero();
            assertThat(result.out()).contains("Alice");
            assertThat(result.err()).isEmpty();
            assertThat(Files.readString(dir.resolve("events.log"))).contains("ROWS  query delivered 3 rows");
            assertThat(Files.readString(dir.resolve("library.relix"))).isEqualTo(Examples.text("library.relix"));
        }

        @Test
        @DisplayName("is run's alone: trace has no --trace")
        void runOnly() {
            var result = cli().run("explain", "--trace", "library.relix");

            assertThat(result.status()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("bundle")
    class Bundle {

        @Test
        @DisplayName("is one JSON document per script, with each query's plans")
        void document() {
            var result = cli().run("bundle", "library.relix");

            assertThat(isJson(result.out())).isTrue();
            assertThat(result.out()).startsWith("{\"namespace\":\"library\",\"diagnostics\":[],\"queries\":[")
                    .contains("\"label\":\"LondonBorrowers\"").contains("\"physicalPlan\":{\"op\":");
        }

        @Test
        @DisplayName("is still produced for a script that does not analyse, carrying its diagnostics, and exits 3")
        void broken() {
            var result = cli().run("bundle", "broken.relix");

            assertThat(result.status()).isEqualTo(3);
            assertThat(isJson(result.out())).as(result.out()).isTrue();
            assertThat(result.out()).contains("\"diagnostics\":[{\"severity\":\"error\","
                    + "\"message\":\"Undefined relation: 'Missing'\",\"file\":\"broken.relix\",\"line\":6,\"column\":19}]");
        }
    }

    @Nested
    @DisplayName("ir")
    class Ir {

        @Test
        @DisplayName("prints the IR report of the whole script")
        void report() {
            var result = cli().run("ir", "library.relix");

            assertThat(result.out()).contains("RELIX IR  namespace=library").contains("LondonBorrowers");
        }
    }

    @Nested
    @DisplayName("provenance")
    class Provenance {

        @Test
        @DisplayName("annotates each row in a [prov] column on a terminal, counting by default")
        void table() {
            var result = cli().run("provenance", "--query", "LondonBorrowers", "library.relix");

            assertThat(result.out()).contains("[prov]").contains("(3 tuples; provenance semiring: counting)");
        }

        @Test
        @DisplayName("writes JSON in a pipe, with the annotation beside each row, never in it")
        void json() {
            var result = cli().piped().run("provenance", "--semiring", "lineage", "--query", "LondonBorrowers",
                    "library.relix");

            assertThat(isJson(result.out())).as(result.out()).isTrue();
            assertThat(result.out()).contains("\"semiring\": \"lineage\"")
                    .contains("{\"row\": {\"member_id\": 1, \"name\": \"Alice\"").contains("}, \"provenance\": {");
        }

        @Test
        @DisplayName("-o table and -o json choose; another format is a usage error")
        void format() {
            assertThat(cli().piped().run("provenance", "-o", "table", "--query", "LondonBorrowers",
                    "library.relix").out()).contains("[prov]");
            assertThat(cli().run("provenance", "-o", "json", "--query", "LondonBorrowers",
                    "library.relix").out()).startsWith("{");
            assertThat(cli().run("provenance", "-o", "csv", "library.relix").status()).isEqualTo(2);
        }

        @Test
        @DisplayName("an unknown semiring is a usage error that lists the ones there are")
        void unknownSemiring() {
            var result = cli().run("provenance", "--semiring", "nope", "library.relix");

            assertThat(result.status()).isEqualTo(2);
            assertThat(result.err()).contains("'nope' is not one of").contains("counting").contains("lineage");
        }
    }
}
