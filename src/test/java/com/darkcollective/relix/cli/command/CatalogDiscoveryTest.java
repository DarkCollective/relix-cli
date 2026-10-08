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

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The catalog the {@code .relix/} directories supply (design §5.1, §5.2), end to end
 * through {@code Main}: which directories are read, in what order, and which declaration
 * of a name wins.
 */
@DisplayName("Catalog discovery")
class CatalogDiscoveryTest {

    @TempDir
    Path root;

    /** {@code $HOME}. */
    Path home;
    /** {@code ~/work/acme}, a project. */
    Path acme;
    /** {@code ~/work/acme/reports}, a sub-project and the working directory. */
    Path reports;

    @BeforeEach
    void tree() throws IOException {
        home = Files.createDirectories(root.resolve("home"));
        acme = Files.createDirectories(home.resolve("work/acme"));
        reports = Files.createDirectories(acme.resolve("reports"));
    }

    /** A one-row table named {@code name} whose {@code v} is {@code value}. */
    private static String table(String name, String value) {
        return name + " := [\n| v |\n|---|\n| " + value + " |\n];\n";
    }

    private static Path write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        return Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    private static Path catalog(Path project, String file, String text) throws IOException {
        return write(project.resolve(".relix/catalog").resolve(file), text);
    }

    /** The command in {@code ~/work/acme/reports}, writing tsv. */
    private Cli cli() {
        return Cli.in(reports).home(home).piped();
    }

    @Nested
    @DisplayName("which directories are read")
    class Walk {

        @Test
        @DisplayName("every level from the working directory up to $HOME, and ~/.relix")
        void everyLevel() throws IOException {
            catalog(home, "user.relix", table("U", "user"));
            catalog(home.resolve("work"), "work.relix", table("W", "work"));
            catalog(acme, "acme.relix", table("A", "acme"));
            catalog(reports, "reports.relix", table("R", "reports"));

            var result = cli().run("-e", "U ∪ W ∪ A ∪ R");

            assertThat(result.status()).as(result.toString()).isZero();
            assertThat(result.out()).isEqualTo("v\nuser\nwork\nacme\nreports\n");
        }

        @Test
        @DisplayName("nothing above $HOME")
        void homeCeiling() throws IOException {
            catalog(root, "above.relix", table("Above", "x"));

            var result = cli().run("-e", "Above");

            assertThat(result.status()).as(result.toString()).isEqualTo(3);
            assertThat(result.err()).contains("Above");
        }

        @Test
        @DisplayName("a root marker ends the walk at its directory; ~/.relix is still read")
        void rootMarker() throws IOException {
            catalog(home, "user.relix", table("U", "user"));
            catalog(home.resolve("work"), "work.relix", table("W", "work"));
            catalog(acme, "acme.relix", table("A", "acme"));
            write(acme.resolve(".relix/root"), "");

            var inside = cli().run("-e", "U ∪ A");
            var above = cli().run("-e", "W");

            assertThat(inside.status()).as(inside.toString()).isZero();
            assertThat(inside.out()).isEqualTo("v\nuser\nacme\n");
            assertThat(above.status()).as(above.toString()).isEqualTo(3);
        }

        @Test
        @DisplayName("outside $HOME, only the working directory's .relix/")
        void outsideHome() throws IOException {
            Path elsewhere = Files.createDirectories(root.resolve("srv/app"));
            catalog(root.resolve("srv"), "srv.relix", table("Parent", "p"));
            catalog(elsewhere, "app.relix", table("App", "app"));

            var own = Cli.in(elsewhere).home(home).piped().run("-e", "App");
            var parent = Cli.in(elsewhere).home(home).piped().run("-e", "Parent");

            assertThat(own.status()).as(own.toString()).isZero();
            assertThat(own.out()).isEqualTo("v\napp\n");
            assertThat(parent.status()).as(parent.toString()).isEqualTo(3);
        }

        @Test
        @DisplayName("-C moves where the walk starts")
        void minusC() throws IOException {
            catalog(acme, "acme.relix", table("A", "acme"));

            var result = Cli.in(home).home(home).piped().run("-C", "work/acme", "-e", "A");

            assertThat(result.status()).as(result.toString()).isZero();
            assertThat(result.out()).isEqualTo("v\nacme\n");
        }

        @Test
        @DisplayName("only catalog/*.relix is read")
        void onlyRelixFiles() throws IOException {
            catalog(acme, "notes.txt", "this is not Relix");
            write(acme.resolve(".relix/stray.relix"), "not read either");
            catalog(acme, "acme.relix", table("A", "acme"));

            var result = cli().run("-e", "A");

            assertThat(result.status()).as(result.toString()).isZero();
        }
    }

    @Nested
    @DisplayName("which declaration wins")
    class Overlay {

        @Test
        @DisplayName("within a directory, files load in lexical order, so the later wins")
        void lexicalOrder() throws IOException {
            catalog(acme, "10-later.relix", table("T", "later"));
            catalog(acme, "00-first.relix", table("T", "first"));

            var result = cli().run("-e", "T");

            assertThat(result.status()).as(result.toString()).isZero();
            assertThat(result.out()).isEqualTo("v\nlater\n");
        }

        @Test
        @DisplayName("a nearer level replaces a farther one's declaration, without a duplicate-name error")
        void nearestWins() throws IOException {
            catalog(home, "user.relix", table("T", "user"));
            catalog(acme, "acme.relix", table("T", "acme"));
            catalog(reports, "reports.relix", table("T", "reports"));

            var result = cli().run("-e", "T");

            assertThat(result.status()).as(result.toString()).isZero();
            assertThat(result.out()).isEqualTo("v\nreports\n");
        }

        @Test
        @DisplayName("an outer view reads the inner replacement of its source")
        void outerViewReadsInnerSource() throws IOException {
            catalog(acme, "acme.relix", "Orders := [\n| id | amount |\n|----|--------|\n| 1 | 500 |\n| 2 | 50 |\n];\n"
                    + "Big := { σ amount > 100 (Orders) };\n");
            catalog(reports, "sample.relix", "Orders := [\n| id | amount |\n|----|--------|\n| 7 | 700 |\n];\n");

            var inReports = cli().run("-e", "π id (Big)");
            var inAcme = Cli.in(acme).home(home).piped().run("-e", "π id (Big)");

            assertThat(inReports.status()).as(inReports.toString()).isZero();
            assertThat(inReports.out()).isEqualTo("id\n7\n");
            assertThat(inAcme.out()).isEqualTo("id\n1\n");
        }

        @Test
        @DisplayName("a relative path in a catalog file is read from beside that file")
        void relativePathBesideFile() throws IOException {
            write(acme.resolve(".relix/catalog/data/orders.csv"), "id,amount\n1,10\n");
            catalog(acme, "orders.relix", "source Orders from csv(\"data/orders.csv\") "
                    + "{ header: true, schema: { id: NUMBER, amount: NUMBER } };\n");

            var result = cli().run("-e", "Orders");

            assertThat(result.status()).as(result.toString()).isZero();
            assertThat(result.out()).isEqualTo("id\tamount\n1\t10\n");
        }

        @Test
        @DisplayName("an input bound with -i replaces the catalog's relation of that name")
        void inputShadows() throws IOException {
            catalog(acme, "acme.relix", "Orders := [\n| id |\n|----|\n| 1 |\n];\nAll := { Orders };\n");
            write(reports.resolve("sample.csv"), "id\n9\n");

            var result = cli().run("-i", "Orders=sample.csv", "-e", "All");

            assertThat(result.status()).as(result.toString()).isZero();
            assertThat(result.out()).isEqualTo("id\n9\n");
        }

        @Test
        @DisplayName("the script's own declaration replaces the catalog's")
        void scriptShadows() throws IOException {
            catalog(acme, "acme.relix", table("T", "catalog"));

            var result = cli().run("-e", table("T", "script") + "query { T };");

            assertThat(result.status()).as(result.toString()).isZero();
            assertThat(result.out()).isEqualTo("v\nscript\n");
        }
    }

    @Nested
    @DisplayName("files named outside the tree")
    class Named {

        @Test
        @DisplayName("-N ignores the directories")
        void noCatalog() throws IOException {
            catalog(acme, "acme.relix", table("A", "acme"));

            var result = cli().run("-N", "-e", "A");

            assertThat(result.status()).as(result.toString()).isEqualTo(3);
        }

        @Test
        @DisplayName("--catalog adds a file, nearer than every directory, and loads under -N too")
        void catalogOption() throws IOException {
            catalog(acme, "acme.relix", table("T", "acme") + table("A", "acme"));
            write(root.resolve("extra.relix"), table("T", "extra"));

            var overlaid = cli().run("--catalog=" + root.resolve("extra.relix"), "-e", "T ∪ A");
            var alone = cli().run("-N", "--catalog", root.resolve("extra.relix").toString(), "-e", "T");

            assertThat(overlaid.status()).as(overlaid.toString()).isZero();
            assertThat(overlaid.out()).isEqualTo("v\nextra\nacme\n");
            assertThat(alone.out()).isEqualTo("v\nextra\n");
        }

        @Test
        @DisplayName("$RELIX_CATALOG_PATH adds files, farther than --catalog")
        void catalogPath() throws IOException {
            write(root.resolve("a.relix"), table("A", "a") + table("T", "a"));
            write(root.resolve("b.relix"), table("B", "b") + table("T", "b"));
            write(root.resolve("c.relix"), table("T", "c"));
            String path = root.resolve("a.relix") + File.pathSeparator + root.resolve("b.relix");

            var fromPath = cli().env("RELIX_CATALOG_PATH", path).run("-e", "A ∪ B ∪ T");
            var withOption = cli().env("RELIX_CATALOG_PATH", path)
                    .run("--catalog", root.resolve("c.relix").toString(), "-e", "T");

            assertThat(fromPath.status()).as(fromPath.toString()).isZero();
            assertThat(fromPath.out()).isEqualTo("v\na\nb\n");
            assertThat(withOption.out()).isEqualTo("v\nc\n");
        }

        @Test
        @DisplayName("a --catalog file that is not there is a usage error")
        void missingCatalogFile() {
            var result = cli().run("--catalog", "nowhere.relix", "-e", "1");

            assertThat(result.status()).isEqualTo(2);
            assertThat(result.err()).contains("--catalog: nowhere.relix: no such file");
        }
    }

    @Test
    @DisplayName("a piped script resolves catalog names")
    void pipedScript() throws IOException {
        catalog(acme, "acme.relix", table("A", "acme"));

        var result = cli().stdin("query { A };").run();

        assertThat(result.status()).as(result.toString()).isZero();
        assertThat(result.out()).isEqualTo("v\nacme\n");
    }

    @Test
    @DisplayName("a catalog file that does not parse fails the run with 3, naming the file")
    void catalogDoesNotParse() throws IOException {
        Path bad = catalog(acme, "bad.relix", "Orders := [ oops");

        var result = cli().run("-e", "1");

        assertThat(result.status()).as(result.toString()).isEqualTo(3);
        assertThat(result.err()).contains(bad.toString());
    }

    @Test
    @DisplayName("a catalog file may not hold a query")
    void catalogHoldsQuery() throws IOException {
        catalog(acme, "q.relix", table("T", "x") + "query { T };\n");

        var result = cli().run("-e", "T");

        assertThat(result.status()).as(result.toString()).isEqualTo(3);
        assertThat(result.err()).contains("a query belongs in a script");
    }

    @Test
    @DisplayName("-v names each catalog file read")
    void verboseNamesFiles() throws IOException {
        Path file = catalog(acme, "acme.relix", table("A", "acme"));

        var result = cli().run("-v", "-e", "A");

        assertThat(result.err()).contains("relix: catalog " + file);
    }

    @Nested
    @DisplayName("relixrc")
    class Rc {

        /** Two columns, so that csv and tsv differ. */
        static final String PAIR = "P := [\n| a | b |\n|---|---|\n| 1 | 2 |\n];\n";

        @Test
        @DisplayName("sets the output format, the nearest file winning")
        void output() throws IOException {
            write(acme.resolve(".relix/relixrc"), "# defaults\noutput = ndjson\n");
            write(reports.resolve(".relix/relixrc"), "output = csv\n");
            catalog(acme, "acme.relix", PAIR);

            var result = cli().run("-e", "P");

            assertThat(result.status()).as(result.toString()).isZero();
            assertThat(result.out()).isEqualTo("a,b\n1,2\n");
        }

        @Test
        @DisplayName("-o and $RELIX_OUTPUT beat it")
        void optionBeatsIt() throws IOException {
            write(acme.resolve(".relix/relixrc"), "output = csv\n");
            catalog(acme, "acme.relix", table("A", "acme"));

            var option = cli().run("-o", "ndjson", "-e", "A");
            var variable = cli().env("RELIX_OUTPUT", "ndjson").run("-e", "A");

            assertThat(option.out()).isEqualTo("{\"v\":\"acme\"}\n");
            assertThat(variable.out()).isEqualTo("{\"v\":\"acme\"}\n");
        }

        @Test
        @DisplayName("sets the profile; -P beats it")
        void profile() throws IOException {
            write(acme.resolve(".relix/relixrc"), "profile = staging\n");
            Path profiles = write(home.resolve(".relix/profiles.json"),
                    "{ \"staging\": { \"WHO\": \"stage\" }, \"production\": { \"WHO\": \"prod\" } }");
            if (Files.getFileAttributeView(profiles, PosixFileAttributeView.class) != null) {
                Files.setPosixFilePermissions(profiles, PosixFilePermissions.fromString("rw-------"));
            }
            catalog(acme, "acme.relix", "source Who from csv(\"${WHO}.csv\") { header: true, schema: { v: STRING } };\n");
            write(acme.resolve(".relix/catalog/stage.csv"), "v\nfrom staging\n");
            write(acme.resolve(".relix/catalog/prod.csv"), "v\nfrom production\n");

            var fromRc = cli().run("-e", "Who");
            var fromOption = cli().run("-P", "production", "-e", "Who");

            assertThat(fromRc.status()).as(fromRc.toString()).isZero();
            assertThat(fromRc.out()).isEqualTo("v\nfrom staging\n");
            assertThat(fromOption.out()).isEqualTo("v\nfrom production\n");
        }

        @Test
        @DisplayName("an unknown key is a warning, and -N ignores the file")
        void unknownKey() throws IOException {
            write(acme.resolve(".relix/relixrc"), "colour = always\noutput = csv\n");
            write(root.resolve("pair.relix"), PAIR);
            String pair = "--catalog=" + root.resolve("pair.relix");

            var warned = cli().run(pair, "-e", "P");
            var ignored = cli().run("-N", pair, "-e", "P");

            assertThat(warned.err()).contains("relixrc:1: ignored: unknown key 'colour'");
            assertThat(warned.out()).isEqualTo("a,b\n1,2\n");
            assertThat(ignored.err()).isEmpty();
            assertThat(ignored.out()).isEqualTo("a\tb\n1\t2\n");
        }
    }
}
