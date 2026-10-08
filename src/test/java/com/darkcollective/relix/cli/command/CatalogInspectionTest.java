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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code relix catalog files}, {@code ls}, {@code schema} and {@code where} (design §5.3),
 * end to end through {@code Main}, over three levels: {@code ~/.relix}, a project and an
 * untrusted sub-project.
 */
@DisplayName("Catalog inspection")
class CatalogInspectionTest {

    @TempDir
    Path root;

    Path home;
    Path acme;
    Path reports;

    Path base;
    Path project;
    Path secret;

    @BeforeEach
    void tree() throws IOException {
        home = Files.createDirectories(root.resolve("home"));
        acme = Files.createDirectories(home.resolve("acme"));
        reports = Files.createDirectories(acme.resolve("reports"));
        base = write(home.resolve(".relix/catalog/base.relix"),
                "Orders := [\n| id | amount |\n|----|--------|\n| 1 | 5 |\n];\n"
                        + "Big := { σ amount > 1 (Orders) };\n");
        project = write(acme.resolve(".relix/catalog/10-acme.relix"),
                "-- the project's own Orders\n"
                        + "source Orders from csv(\"orders.csv\") "
                        + "{ header: true, schema: { id: NUMBER, amount: NUMBER, note: STRING } };\n"
                        + "Customers := [\n| cid | name |\n|-----|------|\n| 1 | ada |\n];\n");
        secret = write(reports.resolve(".relix/catalog/secret.relix"),
                "Secret := [\n| s |\n|---|\n| 1 |\n];\nOrders := [\n| id |\n|----|\n| 9 |\n];\n");
        var trusted = Cli.in(acme).home(home).run("catalog", "trust");
        assertThat(trusted.status()).as(trusted.toString()).isZero();
    }

    private static Path write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        return Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    /** In the sub-project, whose own level is not trusted, writing tsv. */
    private Cli piped() {
        return Cli.in(reports).home(home).piped();
    }

    /** In the sub-project, on a terminal, so writing a table. */
    private Cli terminal() {
        return Cli.in(reports).home(home);
    }

    @Nested
    @DisplayName("files")
    class Files_ {

        @Test
        @DisplayName("every file in load order, its names and whether it is trusted, as tsv")
        void tsv() {
            var result = piped().run("catalog", "files");

            assertThat(result.status()).as(result.toString()).isZero();
            assertThat(result.out()).isEqualTo("file\ttrusted\tdeclares\n"
                    + base + "\ttrue\tOrders Big\n"
                    + project + "\ttrue\tOrders Customers\n"
                    + secret + "\tfalse\tSecret Orders\n");
            assertThat(result.err()).contains("relix catalog trust " + reports);
        }

        @Test
        @DisplayName("as a table")
        void table() {
            var result = terminal().run("catalog", "files", "--color=never");

            assertThat(result.status()).as(result.toString()).isZero();
            assertThat(result.out()).contains("file").contains("trusted").contains("declares")
                    .contains("Orders Customers").contains("false").contains("(3 rows)");
        }

        @Test
        @DisplayName("-N leaves only the --catalog files")
        void noCatalog() throws IOException {
            Path extra = write(root.resolve("extra.relix"), "X := [\n| x |\n|---|\n| 1 |\n];\n");

            var result = piped().run("-N", "--catalog", extra.toString(), "catalog", "files");

            assertThat(result.out()).isEqualTo("file\ttrusted\tdeclares\n" + extra + "\ttrue\tX\n");
        }
    }

    @Nested
    @DisplayName("ls")
    class Ls {

        @Test
        @DisplayName("name, kind, arity and the winning file, from the engine's relix.relations, as tsv")
        void tsv() {
            var result = piped().run("catalog", "ls");

            assertThat(result.status()).as(result.toString()).isZero();
            assertThat(result.out()).isEqualTo("name\tkind\tarity\tfile\n"
                    + "Big\tQR\t3\t" + base + "\n"
                    + "Customers\tINL\t2\t" + project + "\n"
                    + "Orders\tSRC\t3\t" + project + "\n");
        }

        @Test
        @DisplayName("as a table")
        void table() {
            var result = terminal().run("catalog", "ls", "--color=never");

            assertThat(result.status()).as(result.toString()).isZero();
            assertThat(result.out()).contains("── relations").contains("Customers").contains("INL")
                    .contains("(3 rows)").doesNotContain("Secret");
        }

        @Test
        @DisplayName("as ndjson")
        void ndjson() {
            var result = piped().run("catalog", "ls", "-o", "ndjson");

            assertThat(result.out()).startsWith("{\"name\":\"Big\",\"kind\":\"QR\",\"arity\":3,\"file\":");
        }
    }

    @Nested
    @DisplayName("schema")
    class Schema {

        @Test
        @DisplayName("the winning declaration's columns and types, in order, as tsv")
        void tsv() {
            var result = piped().run("catalog", "schema", "Orders");

            assertThat(result.status()).as(result.toString()).isZero();
            assertThat(result.out()).isEqualTo("column\ttype\nid\tN\namount\tN\nnote\tS\n");
        }

        @Test
        @DisplayName("of a view, as a table")
        void table() {
            var result = terminal().run("catalog", "schema", "Big", "--color=never");

            assertThat(result.status()).as(result.toString()).isZero();
            assertThat(result.out()).contains("── Big").contains("column").contains("note")
                    .contains("(3 rows)");
        }

        @Test
        @DisplayName("of an unknown name exits 3")
        void unknown() {
            var result = piped().run("catalog", "schema", "Nowhere");

            assertThat(result.status()).as(result.toString()).isEqualTo(3);
            assertThat(result.out()).isEmpty();
            assertThat(result.err()).contains("the catalog declares no 'Nowhere'");
        }

        @Test
        @DisplayName("of a name only an untrusted level declares exits 3")
        void untrusted() {
            var result = piped().run("catalog", "schema", "Secret");

            assertThat(result.status()).as(result.toString()).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("where")
    class Where {

        @Test
        @DisplayName("the declaration that won, then those it shadowed, and an untrusted one, as tsv")
        void tsv() {
            var result = piped().run("catalog", "where", "Orders");

            assertThat(result.status()).as(result.toString()).isZero();
            assertThat(result.out()).isEqualTo("file\tline\tkind\tstate\n"
                    + secret + "\t6\ttable\tuntrusted\n"
                    + project + "\t2\tsource\twins\n"
                    + base + "\t1\ttable\tshadowed\n");
        }

        @Test
        @DisplayName("as a table")
        void table() {
            var result = terminal().run("catalog", "where", "Orders", "--color=never");

            assertThat(result.status()).as(result.toString()).isZero();
            assertThat(result.out()).contains("── Orders").contains("wins").contains("shadowed")
                    .contains("untrusted").contains("(3 rows)");
        }

        @Test
        @DisplayName("once the sub-project is trusted, its declaration wins and shadows both")
        void trusted() {
            assertThat(Cli.in(reports).home(home).run("catalog", "trust").status()).isZero();

            var result = piped().run("catalog", "where", "Orders");

            assertThat(result.out()).isEqualTo("file\tline\tkind\tstate\n"
                    + secret + "\t6\ttable\twins\n"
                    + project + "\t2\tsource\tshadowed\n"
                    + base + "\t1\ttable\tshadowed\n");
        }

        @Test
        @DisplayName("of an unknown name exits 3")
        void unknown() {
            var result = piped().run("catalog", "where", "Nowhere");

            assertThat(result.status()).isEqualTo(3);
            assertThat(result.err()).contains("the catalog declares no 'Nowhere'");
        }
    }
}
