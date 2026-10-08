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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Binding data to relation names with {@code -i} (design §3.2), end to end through
 * {@code Main}.
 */
@DisplayName("Inputs")
class InputTest {

    /** The same two orders in each format. */
    private static final String CSV = "id,customer\n1,ada\n2,bob\n";
    private static final String TSV = "id\tcustomer\n1\tada\n2\tbob\n";
    private static final String JSON = "[{\"id\":1,\"customer\":\"ada\"},{\"id\":2,\"customer\":\"bob\"}]";
    private static final String NDJSON = "{\"id\":1,\"customer\":\"ada\"}\n{\"id\":2,\"customer\":\"bob\"}\n";

    @TempDir
    Path dir;

    private static String text(String format) {
        return switch (format) {
            case "csv" -> CSV;
            case "tsv" -> TSV;
            case "json" -> JSON;
            default -> NDJSON;
        };
    }

    private void write(String name, String text) throws IOException {
        Files.writeString(dir.resolve(name), text, StandardCharsets.UTF_8);
    }

    @Nested
    @DisplayName("each format")
    class Formats {

        @ParameterizedTest(name = "{0} from a file, told by its extension")
        @CsvSource({"csv", "tsv", "json", "ndjson"})
        void fromFile(String format) throws IOException {
            write("orders." + format, text(format));

            var result = Cli.in(dir).piped().run("-i", "orders=orders." + format, "-e", "σ id > 1 (orders)");

            assertThat(result.status()).as(result.toString()).isZero();
            assertThat(result.out()).isEqualTo("id\tcustomer\n2\tbob\n");
        }

        @ParameterizedTest(name = "{0} from standard input")
        @CsvSource({"csv", "tsv", "json", "ndjson"})
        void fromStdin(String format) {
            var result = Cli.in(dir).piped().stdin(text(format))
                    .run("-i", "orders=" + format + ":-", "-e", "σ id > 1 (orders)");

            assertThat(result.status()).as(result.toString()).isZero();
            assertThat(result.out()).isEqualTo("id\tcustomer\n2\tbob\n");
        }

        @Test
        @DisplayName("a named format beats the extension")
        void formatBeatsExtension() throws IOException {
            write("orders.txt", TSV);

            var result = Cli.in(dir).piped().run("--input=orders=tsv:orders.txt", "-e", "orders");

            assertThat(result.status()).as(result.toString()).isZero();
            assertThat(result.out()).isEqualTo(TSV);
        }

        @Test
        @DisplayName("what relix writes as tsv, relix reads back as it was: tabs, line breaks, backslashes, NULL")
        void tsvRoundTrip() {
            String awkward = "{\"k\":1,\"s\":\"a\\tb\\nc\\rd\\\\e\"}\n{\"k\":2,\"s\":null}\n";
            var tsv = Cli.in(dir).piped().stdin(awkward).run("-i", "r=ndjson:-", "-e", "r");
            var back = Cli.in(dir).piped().stdin(tsv.out()).run("-i", "r=tsv:-", "-o", "ndjson", "-e", "r");

            assertThat(tsv.out()).isEqualTo("k\ts\n1\ta\\tb\\nc\\rd\\\\e\n2\t\n");
            assertThat(back.status()).as(back.toString()).isZero();
            assertThat(back.out()).isEqualTo(awkward);
        }

        @Test
        @DisplayName("inputs join with each other and with the script's own relations")
        void join() throws IOException {
            write("customers.csv", "customer,city\nada,Leeds\nbob,York\n");

            var result = Cli.in(dir).piped().stdin(NDJSON).run(
                    "-i", "orders=ndjson:-", "-i", "customers=customers.csv",
                    "-e", "Cities := [\n| city | region |\n|---|---|\n| York | north |\n];",
                    "-e", "π id, region (orders ⋈ customers ⋈ Cities)");

            assertThat(result.status()).as(result.toString()).isZero();
            assertThat(result.out()).isEqualTo("id\tregion\n2\tnorth\n");
        }

        @Test
        @DisplayName("a script file reads a bound input as -e does")
        void scriptFile() throws IOException {
            write("orders.csv", CSV);
            write("count.relix", "query { γ COUNT(*) -> n (orders) };\n");

            var result = Cli.in(dir).piped().run("-i", "orders=orders.csv", "count.relix");

            assertThat(result.status()).as(result.toString()).isZero();
            assertThat(result.out()).isEqualTo("n\n2\n");
        }
    }

    @Nested
    @DisplayName("the heading")
    class Heading {

        @Test
        @DisplayName("--schema declares it instead of inferring it")
        void schema() throws IOException {
            write("orders.csv", CSV);

            var inferred = Cli.in(dir).run("-o", "ndjson", "-i", "orders=orders.csv", "-e", "σ id = 1 (orders)");
            var declared = Cli.in(dir).run("-o", "ndjson", "-i", "orders=orders.csv",
                    "--schema", "orders={ id: STRING, customer: STRING }", "-e", "σ id = '1' (orders)");

            assertThat(inferred.out()).isEqualTo("{\"id\":1,\"customer\":\"ada\"}\n");
            assertThat(declared.status()).as(declared.toString()).isZero();
            assertThat(declared.out()).isEqualTo("{\"id\":\"1\",\"customer\":\"ada\"}\n");
        }

        @Test
        @DisplayName("--infer-rows says how many records the heading is inferred from")
        void inferRows() {
            String mixed = "{\"v\":1}\n{\"v\":\"x\"}\n";

            var wide = Cli.in(dir).piped().stdin(mixed).run("-i", "r=ndjson:-", "-e", "r");
            var narrow = Cli.in(dir).piped().stdin(mixed).run("-i", "r=ndjson:-", "--infer-rows=1", "-e", "r");

            assertThat(wide.status()).as(wide.toString()).isZero();
            assertThat(narrow.status()).as(narrow.toString()).isEqualTo(4);
            assertThat(narrow.err()).startsWith("relix: -e: ");
        }

        @Test
        @DisplayName("a malformed --schema is a usage error")
        void badSchema() throws IOException {
            write("orders.csv", CSV);

            var result = Cli.in(dir).run("-i", "orders=orders.csv", "--schema", "orders=id NUMBER", "-e", "orders");

            assertThat(result.status()).isEqualTo(2);
            assertThat(result.err()).contains("--schema orders: expected a heading");
        }

        @Test
        @DisplayName("text that cannot be read as its format fails as execution does, naming the input")
        void unreadable() {
            var result = Cli.in(dir).piped().stdin("not json at all\n").run("-i", "r=json:-", "-e", "r");

            assertThat(result.status()).as(result.toString()).isEqualTo(4);
            assertThat(result.err()).startsWith("relix: -e: -i r=-: ");
        }
    }

    @Nested
    @DisplayName("usage errors")
    class Usage {

        private void assertUsage(Cli.Result result, String message) {
            assertThat(result.status()).as(result.toString()).isEqualTo(2);
            assertThat(result.out()).isEmpty();
            assertThat(result.err()).contains(message);
        }

        @Test
        @DisplayName("standard input with no format")
        void stdinNeedsFormat() {
            assertUsage(Cli.in(dir).stdin(CSV).run("-i", "r=-", "-e", "r"),
                    "-i r=-: standard input has no extension to tell its format by");
        }

        @Test
        @DisplayName("a file whose extension tells no format")
        void unknownExtension() throws IOException {
            write("orders.txt", CSV);

            assertUsage(Cli.in(dir).run("-i", "r=orders.txt", "-e", "r"), "cannot tell the format from the extension .txt");
        }

        @Test
        @DisplayName("a file that is not there")
        void missingFile() {
            assertUsage(Cli.in(dir).run("-i", "r=nope.csv", "-e", "r"), "-i r=nope.csv: nope.csv: no such file");
        }

        @Test
        @DisplayName("a binding with no name or no location")
        void malformed() {
            assertUsage(Cli.in(dir).run("-i", "orders.csv", "-e", "r"), "expected NAME=[FORMAT:]LOCATION");
            assertUsage(Cli.in(dir).run("-i", "r=", "-e", "r"), "expected NAME=[FORMAT:]LOCATION");
            assertUsage(Cli.in(dir).run("-i", "r=csv:", "-e", "r"), "expected NAME=[FORMAT:]LOCATION");
        }

        @Test
        @DisplayName("a URL without --remote")
        void urlNeedsRemote() {
            assertUsage(Cli.in(dir).run("-i", "r=https://example.com/orders.csv", "-e", "r"),
                    "reading a URL needs --remote");
        }

        @Test
        @DisplayName("a name bound twice")
        void boundTwice() throws IOException {
            write("orders.csv", CSV);

            assertUsage(Cli.in(dir).run("-i", "r=orders.csv", "-i", "r=orders.csv", "-e", "r"), "'r' is bound twice");
        }

        @Test
        @DisplayName("standard input bound twice")
        void stdinTwice() {
            assertUsage(Cli.in(dir).stdin(CSV).run("-i", "a=csv:-", "-i", "b=csv:-", "-e", "a"),
                    "standard input (-) can be bound to one input only");
        }

        @Test
        @DisplayName("standard input bound, and the script read from it too")
        void stdinIsData() throws IOException {
            write("a.relix", "query { r };\n");

            assertUsage(Cli.in(dir).stdin(CSV).run("-i", "r=csv:-"), "cannot be the script too");
            assertUsage(Cli.in(dir).stdin(CSV).run("-i", "r=csv:-", "-"), "cannot be the script too");
            assertUsage(Cli.in(dir).stdin(CSV).run("-i", "r=csv:-", "a.relix", "a.relix"),
                    "can be given to one script only");
        }

        @Test
        @DisplayName("--schema or --unbounded for a name no -i binds")
        void unboundName() throws IOException {
            write("orders.csv", CSV);

            assertUsage(Cli.in(dir).run("-i", "r=orders.csv", "--schema", "s={ id: NUMBER }", "-e", "r"),
                    "--schema s: no input of that name is bound with -i");
            assertUsage(Cli.in(dir).run("-i", "r=orders.csv", "--unbounded=s", "-e", "r"),
                    "--unbounded s: no input of that name is bound with -i");
        }

        @Test
        @DisplayName("an unbounded JSON array, and --infer-rows below 1")
        void badModifiers() {
            assertUsage(Cli.in(dir).stdin(JSON).run("-i", "r=json:-", "--unbounded=r", "-e", "r"),
                    "a JSON array is read whole");
            assertUsage(Cli.in(dir).stdin(JSON).run("-i", "r=json:-", "--infer-rows=0", "-e", "r"),
                    "--infer-rows must be a positive number");
        }
    }

    @Nested
    @DisplayName("an unbounded input")
    class Unbounded {

        @ParameterizedTest(name = "{0}")
        @CsvSource(delimiter = ';', value = {
            "τ id (r)",
            "γ customer, COUNT(id) → n (r)"})
        @DisplayName("a blocking operator over it is refused before it starts")
        void blockingRefused(String query) {
            var result = Cli.in(dir).piped().stdin(NDJSON)
                    .run("-i", "r=ndjson:-", "--unbounded=r", "--infer-rows=1", "-e", query);

            assertThat(result.status()).as(result.toString()).isEqualTo(3);
            assertThat(result.out()).isEmpty();
            assertThat(result.err()).as(result.toString()).contains("unbounded");
        }

        @Test
        @Timeout(60)
        @DisplayName("streaming ndjson on standard input emits a row before the input ends")
        void streams() throws Exception {
            PipedOutputStream feed = new PipedOutputStream();
            PipedInputStream stdin = new PipedInputStream(feed);
            CountDownLatch firstRow = new CountDownLatch(1);
            ByteArrayOutputStream seen = new ByteArrayOutputStream();
            OutputStream reader = new OutputStream() {
                @Override
                public synchronized void write(int b) {
                    seen.write(b);
                    if (seen.toString(StandardCharsets.UTF_8).contains("503")) {
                        firstRow.countDown();
                    }
                }
            };

            CompletableFuture<Cli.Result> run = CompletableFuture.supplyAsync(() ->
                    Cli.in(dir).stdin(stdin).stdout(reader).run(
                            "-i", "log=ndjson:-", "--unbounded=log", "--infer-rows=1",
                            "-o", "ndjson", "--line-buffered", "-e", "σ status >= 500 (log)"));

            feed.write("{\"status\":200}\n{\"status\":503}\n".getBytes(StandardCharsets.UTF_8));
            feed.flush();
            boolean emitted = firstRow.await(30, TimeUnit.SECONDS);
            assertThat(run).as("still waiting for more input").isNotDone();
            feed.write("{\"status\":500}\n".getBytes(StandardCharsets.UTF_8));
            feed.close();
            Cli.Result result = run.get(30, TimeUnit.SECONDS);

            assertThat(emitted).as("a row before the input ended: " + result).isTrue();
            assertThat(result.status()).as(result.toString()).isZero();
            assertThat(result.out()).isEqualTo("{\"status\":503}\n{\"status\":500}\n");
        }
    }
}
