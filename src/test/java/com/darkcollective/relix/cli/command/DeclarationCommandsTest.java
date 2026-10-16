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
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code relix source} and {@code relix connection}, end to end through {@code Main},
 * against CSV and JSON files and an H2 database.
 */
@DisplayName("relix source and relix connection")
class DeclarationCommandsTest {

    @TempDir
    Path root;

    Path home;
    Path acme;
    Path database;

    @BeforeEach
    void tree() throws IOException, SQLException {
        home = Files.createDirectories(root.resolve("home"));
        acme = Files.createDirectories(home.resolve("work/acme"));
        Files.createDirectories(acme.resolve("data"));
        Files.writeString(acme.resolve("data/orders.csv"), "order_id,amount,placed\n1,120,2026-01-02\n2,50,2026-01-03\n");
        Files.writeString(acme.resolve("data/docs.json"),
                "[{\"id\": 1, \"title\": \"a\", \"open\": true}, {\"id\": 2, \"title\": \"b\", \"open\": false}]");
        database = acme.resolve("data/warehouse");
        try (Connection connection = DriverManager.getConnection("jdbc:h2:" + database, "shop", "s3cret");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE STOCK (SKU VARCHAR(16), ON_HAND INT)");
            statement.execute("INSERT INTO STOCK VALUES ('mug', 12), ('tee', 0)");
        }
        ok(cli().run("init"));
    }

    private Cli cli() {
        return Cli.in(acme).home(home).piped()
                .env("WAREHOUSE_URL", "jdbc:h2:" + database + ";IFEXISTS=TRUE")
                .env("WAREHOUSE_USER", "shop")
                .env("WAREHOUSE_PASSWORD", "s3cret");
    }

    private static Cli.Result ok(Cli.Result result) {
        assertThat(result.status()).as(result.toString()).isZero();
        return result;
    }

    private static Cli.Result fails(int status, Cli.Result result) {
        assertThat(result.status()).as(result.toString()).isEqualTo(status);
        return result;
    }

    private Path catalogFile(String name) {
        return acme.resolve(".relix/catalog").resolve(name);
    }

    private String read(String name) throws IOException {
        return Files.readString(catalogFile(name));
    }

    /** A path as tsv writes it: a backslash, Windows' separator, escaped. */
    private static String escaped(Path path) {
        return path.toString().replace("\\", "\\\\");
    }

    private String trustState() {
        return ok(cli().run("catalog", "trust", "--list")).out().lines()
                .filter(line -> line.startsWith(escaped(acme) + "\t"))
                .map(line -> line.substring(line.indexOf('\t') + 1))
                .findFirst().orElse("untrusted");
    }

    private void addOrders() {
        ok(cli().run("source", "add", "Orders", "csv", "./data/orders.csv", "--header", "--infer-schema"));
    }

    private void addWarehouse() {
        ok(cli().run("connection", "add", "warehouse", "database", "--url=${WAREHOUSE_URL}",
                "--user=${WAREHOUSE_USER}", "--password=${WAREHOUSE_PASSWORD}"));
    }

    @Nested
    @DisplayName("add")
    class Add {

        @Test
        @DisplayName("writes a csv source to a file of its own, its path relative to the file, its schema inferred")
        void csv() throws IOException {
            var result = ok(cli().run("source", "add", "Orders", "csv", "./data/orders.csv", "--header",
                    "--infer-schema"));

            assertThat(result.out()).isEmpty();
            assertThat(read("00-source-Orders.relix")).isEqualTo("source Orders from csv(\"../../data/orders.csv\")"
                    + " { header: true, schema: { order_id: number, amount: number, placed: date } };\n");
            assertThat(ok(cli().run("-e", "PROJECT order_id (SELECT amount > 100 (Orders))")).out())
                    .isEqualTo("order_id\n1\n");
        }

        @Test
        @DisplayName("writes a json source, which reads its rows as they come and so takes no --infer-schema")
        void json() throws IOException {
            ok(cli().run("source", "add", "Docs", "json", "data/docs.json"));

            assertThat(read("00-source-Docs.relix")).isEqualTo("source Docs from json(\"../../data/docs.json\") {  };\n");
            assertThat(ok(cli().run("-e", "PROJECT title (SELECT id = 2 (Docs))")).out()).isEqualTo("title\nb\n");
            assertThat(fails(2, cli().run("source", "add", "Other", "json", "data/docs.json", "--infer-schema")).err())
                    .contains("a json source declares no schema");
        }

        @Test
        @DisplayName("writes a connection and a source over its table, with secrets as placeholders")
        void jdbc() throws IOException {
            addWarehouse();
            ok(cli().run("source", "add", "Stock", "warehouse", "--table=STOCK", "--infer-schema"));

            assertThat(read("00-connection-warehouse.relix")).isEqualTo("connection warehouse from jdbc"
                    + " { password: \"${WAREHOUSE_PASSWORD}\", url: \"${WAREHOUSE_URL}\", user: \"${WAREHOUSE_USER}\" };\n");
            assertThat(read("00-source-Stock.relix")).isEqualTo("source Stock from warehouse"
                    + " { table: \"STOCK\", schema: { SKU: string, ON_HAND: number } };\n");
            assertThat(ok(cli().run("-e", "PROJECT SKU (SELECT ON_HAND = 0 (Stock))")).out()).isEqualTo("SKU\ntee\n");
        }

        @Test
        @DisplayName("keeps a trusted project trusted, and leaves the run with no trust warning")
        void trust() {
            addOrders();
            addWarehouse();

            assertThat(trustState()).isEqualTo("trusted");
            assertThat(cli().run("-e", "Orders").err()).doesNotContain("trust");
        }

        @Test
        @DisplayName("refuses a missing required value with exit 2, naming the flag")
        void missing() {
            assertThat(fails(2, cli().run("source", "add", "Orders", "csv")).err()).contains("LOCATION is required");
            assertThat(fails(2, cli().run("source", "add", "T", "database", "--table=T", "--schema={ a: NUMBER }"))
                    .err()).contains("--url is required");
            addWarehouse();
            assertThat(fails(2, cli().run("source", "add", "Stock", "warehouse", "--table=STOCK")).err())
                    .contains("--schema is required").contains("--infer-schema");
            assertThat(fails(2, cli().run("connection", "add", "w", "database")).err()).contains("--url is required");
        }

        @Test
        @DisplayName("refuses a secret given as its value, which it never writes")
        void secret() throws IOException {
            var result = fails(2, cli().run("connection", "add", "w", "database", "--url=jdbc:h2:mem:x",
                    "--password=hunter2"));

            assertThat(result.err()).contains("--password: a secret is written as a ${NAME} placeholder");
            assertThat(fails(2, cli().run("connection", "add", "w", "database",
                    "--url=jdbc:postgresql://db/app?user=a&password=hunter2")).err()).contains("--url");
            try (var files = Files.list(acme.resolve(".relix/catalog"))) {
                assertThat(files).isEmpty();
            }
        }

        @Test
        @DisplayName("refuses a kind that is neither a source kind nor a declared connection")
        void unknownKind() {
            assertThat(fails(2, cli().run("source", "add", "Stock", "warehouse", "--table=STOCK")).err())
                    .contains("'warehouse' is neither a source kind").contains("relix connection add");
        }

        @Test
        @DisplayName("refuses a name the same level declares already, and a flag with no =")
        void duplicate() {
            addOrders();

            assertThat(fails(2, cli().run("source", "add", "Orders", "csv", "data/orders.csv")).err())
                    .contains("Orders is already declared here").contains("relix source edit Orders");
            assertThat(fails(2, cli().run("source", "add", "Other", "csv", "data/orders.csv", "--header", "true"))
                    .err()).contains("expected --OPTION=VALUE");
        }

        @Test
        @DisplayName("allows a name an outer level declares, warning of the file it shadows")
        void overlay() {
            ok(cli().run("source", "add", "--global", "Orders", "csv", "./data/orders.csv", "--header", "--infer-schema"));
            Path outer = home.resolve(".relix/catalog/00-source-Orders.relix");
            assertThat(outer).exists();

            var result = ok(cli().run("source", "add", "Orders", "csv", "./data/orders.csv", "--infer-schema"));

            assertThat(result.err()).contains("warning: Orders is declared at " + outer + ":1 too");
        }

        @Test
        @DisplayName("refuses to write where there is no project, pointing to relix init and --global")
        void noProject() throws IOException {
            Path elsewhere = Files.createDirectories(home.resolve("elsewhere"));

            var result = fails(2, Cli.in(elsewhere).home(home).piped()
                    .run("source", "add", "T", "csv", "../work/acme/data/orders.csv"));
            assertThat(result.err()).contains("relix init").contains("--global");
        }
    }

    @Nested
    @DisplayName("ls")
    class Ls {

        @Test
        @DisplayName("lists each source and connection: name, kind, location and file")
        void both() {
            addOrders();
            addWarehouse();
            ok(cli().run("source", "add", "Stock", "warehouse", "--table=STOCK", "--infer-schema"));

            assertThat(ok(cli().run("source", "ls")).out()).isEqualTo("name\tkind\tlocation\tfile\n"
                    + "Orders\tcsv\t../../data/orders.csv\t" + escaped(catalogFile("00-source-Orders.relix")) + "\n"
                    + "Stock\twarehouse\tSTOCK\t" + escaped(catalogFile("00-source-Stock.relix")) + "\n");
            assertThat(ok(cli().run("connection", "ls")).out()).isEqualTo("name\tkind\tlocation\tfile\n"
                    + "warehouse\tjdbc\t${WAREHOUSE_URL}\t" + escaped(catalogFile("00-connection-warehouse.relix")) + "\n");
        }
    }

    @Nested
    @DisplayName("test")
    class TestVerb {

        @Test
        @DisplayName("passes every source and connection that can be read, exiting 0")
        void passes() {
            addOrders();
            addWarehouse();
            ok(cli().run("source", "add", "Stock", "warehouse", "--table=STOCK", "--infer-schema"));
            ok(cli().run("source", "add", "Docs", "json", "data/docs.json"));

            assertThat(ok(cli().run("source", "test")).out()).isEqualTo(
                    "name\tstatus\tproblem\nDocs\tok\t\nOrders\tok\t\nStock\tok\t\n");
            assertThat(ok(cli().run("connection", "test")).out()).isEqualTo("name\tstatus\tproblem\nwarehouse\tok\t\n");
        }

        @Test
        @DisplayName("fails a source whose file is gone, or whose schema does not match, exiting 5")
        void failing() throws IOException {
            addOrders();
            ok(cli().run("source", "add", "Gone", "csv", "data/gone.csv", "--schema={ a: NUMBER }"));
            ok(cli().run("source", "add", "Wrong", "csv", "data/orders.csv",
                    "--schema={ order_id: NUMBER, missing: STRING }"));
            ok(cli().run("source", "add", "Typed", "csv", "data/orders.csv",
                    "--schema={ order_id: NUMBER, placed: NUMBER }"));

            var result = DeclarationCommandsTest.fails(5, cli().run("source", "test"));

            assertThat(result.out()).startsWith("name\tstatus\tproblem\n")
                    .contains("Gone\tfailed\tCannot read CSV file")
                    .contains("Orders\tok\t\n")
                    .contains("Wrong\tfailed\t").contains("'missing'")
                    .contains("Typed\tfailed\t");
            assertThat(ok(cli().run("source", "test", "Orders")).out()).isEqualTo("name\tstatus\tproblem\nOrders\tok\t\n");
        }

        @Test
        @DisplayName("fails a connection that cannot be reached, or whose placeholder has no value")
        void connectionFails() {
            ok(cli().run("connection", "add", "down", "database", "--url=jdbc:h2:tcp://127.0.0.1:1/nothing"));
            addWarehouse();

            var result = DeclarationCommandsTest.fails(5, Cli.in(acme).home(home).piped()
                    .run("connection", "test"));

            assertThat(result.out()).contains("down\tfailed\t").contains("Connection")
                    .contains("warehouse\tfailed\t${WAREHOUSE_PASSWORD} has no value");
        }
    }

    @Nested
    @DisplayName("edit")
    class Edit {

        @Test
        @DisplayName("sets and removes options, and moves a file source, keeping the project trusted")
        void flags() throws IOException {
            addOrders();
            Files.copy(acme.resolve("data/orders.csv"), acme.resolve("data/orders-2026.csv"));

            ok(cli().run("source", "edit", "Orders", "--unset=header", "--location=data/orders-2026.csv"));

            assertThat(read("00-source-Orders.relix")).isEqualTo("source Orders from csv(\"../../data/orders-2026.csv\")"
                    + " { header: true, schema: { order_id: number, amount: number, placed: date } };\n");
            ok(cli().run("connection", "add", "w", "database", "--url=jdbc:h2:mem:a"));
            ok(cli().run("connection", "edit", "w", "--url=jdbc:h2:mem:b", "--dialect=h2"));
            assertThat(read("00-connection-w.relix")).isEqualTo(
                    "connection w from jdbc { dialect: \"h2\", url: \"jdbc:h2:mem:b\" };\n");
            assertThat(trustState()).isEqualTo("trusted");
        }

        @Test
        @DisplayName("refuses nothing to change, and an option it does not set")
        void refusals() {
            addOrders();

            assertThat(fails(2, cli().run("source", "edit", "Orders")).err()).contains("nothing to change");
            assertThat(fails(2, cli().run("source", "edit", "Orders", "--unset=table")).err()).contains("sets no table");
            assertThat(fails(2, cli().run("source", "edit", "Nope", "--header")).err())
                    .contains("the catalog declares no source Nope");
        }

        @Test
        @DisplayName("reports a declaration beside others in a hand-written file, with its line, and suggests --editor")
        void handWritten() throws IOException {
            Files.writeString(catalogFile("10-mixed.relix"), "-- by hand\nT := [\n| v |\n|---|\n| 1 |\n];\n\n"
                    + "source Mixed from csv(\"../../data/orders.csv\") { header: true };\n");
            ok(cli().run("catalog", "trust"));

            var edit = fails(2, cli().run("source", "edit", "Mixed", "--header=false"));
            assertThat(edit.err()).contains(catalogFile("10-mixed.relix") + ":8 declares Mixed beside other declarations")
                    .contains("--editor");
            var rm = fails(2, cli().run("source", "rm", "Mixed"));
            assertThat(rm.err()).contains(catalogFile("10-mixed.relix") + ":8");
            assertThat(read("10-mixed.relix")).contains("source Mixed");
        }

        @Test
        @DisabledOnOs(OS.WINDOWS)
        @DisplayName("--editor opens $EDITOR at the declaration's line, and keeps the project trusted")
        void editor() throws IOException {
            Files.writeString(catalogFile("10-mixed.relix"), "T := [\n| v |\n|---|\n| 1 |\n];\n"
                    + "source Mixed from csv(\"../../data/orders.csv\") { header: true };\n");
            ok(cli().run("catalog", "trust"));
            Path editor = root.resolve("editor.sh");
            Path args = root.resolve("args.txt");
            Files.writeString(editor, "#!/bin/sh\necho \"$@\" > '" + args + "'\necho '-- edited' >> \"$2\"\n");
            Files.setPosixFilePermissions(editor, PosixFilePermissions.fromString("rwx------"));

            ok(cli().env("EDITOR", editor.toString()).run("source", "edit", "Mixed", "--editor"));

            assertThat(Files.readString(args).strip()).isEqualTo("+6 " + catalogFile("10-mixed.relix"));
            assertThat(read("10-mixed.relix")).endsWith("-- edited\n");
            assertThat(trustState()).isEqualTo("trusted");
        }
    }

    @Nested
    @DisplayName("rm")
    class Rm {

        @Test
        @DisplayName("refuses a declaration another reads, listing them, unless --force")
        void readers() throws IOException {
            addOrders();
            Files.writeString(catalogFile("10-views.relix"), "Big := { SELECT amount > 100 (Orders) };\n");
            ok(cli().run("catalog", "trust"));

            var refused = fails(2, cli().run("source", "rm", "Orders"));
            assertThat(refused.err()).contains("Orders is read by").contains("Big (" + catalogFile("10-views.relix") + ":1)");
            assertThat(catalogFile("00-source-Orders.relix")).exists();

            ok(cli().run("source", "rm", "Orders", "--force"));
            assertThat(catalogFile("00-source-Orders.relix")).doesNotExist();
            assertThat(trustState()).isEqualTo("trusted");
        }

        @Test
        @DisplayName("refuses a connection a source reads")
        void connection() {
            addWarehouse();
            ok(cli().run("source", "add", "Stock", "warehouse", "--table=STOCK", "--infer-schema"));

            assertThat(fails(2, cli().run("connection", "rm", "warehouse")).err()).contains("Stock (");
            ok(cli().run("source", "rm", "Stock"));
            ok(cli().run("connection", "rm", "warehouse"));
            assertThat(ok(cli().run("connection", "ls")).out()).isEqualTo("name\tkind\tlocation\tfile\n");
        }

        @Test
        @DisplayName("removes only the nearest declaration, and says when a farther one now shows through")
        void showsThrough() {
            ok(cli().run("source", "add", "--global", "Orders", "csv", "./data/orders.csv", "--infer-schema"));
            addOrders();

            var result = ok(cli().run("source", "rm", "Orders"));

            Path outer = home.resolve(".relix/catalog/00-source-Orders.relix");
            assertThat(result.err()).contains("Orders now comes from " + outer + ":1");
            assertThat(outer).exists();
        }
    }
}
