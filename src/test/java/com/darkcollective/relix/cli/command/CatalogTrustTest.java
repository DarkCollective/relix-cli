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
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Trusting a project's {@code .relix/} before it is loaded (design §5.4), end to end
 * through {@code Main}.
 */
@DisplayName("Catalog trust")
class CatalogTrustTest {

    @TempDir
    Path root;

    Path home;
    Path acme;

    @BeforeEach
    void tree() throws IOException {
        home = Files.createDirectories(root.resolve("home"));
        acme = Files.createDirectories(home.resolve("work/acme"));
    }

    private static String table(String name, String value) {
        return name + " := [\n| v |\n|---|\n| " + value + " |\n];\n";
    }

    private static Path write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        return Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    private Path catalog(String file, String text) throws IOException {
        return write(acme.resolve(".relix/catalog").resolve(file), text);
    }

    private Cli cli() {
        return Cli.in(acme).home(home).piped();
    }

    private Cli.Result trust() {
        var result = cli().run("catalog", "trust");
        assertThat(result.status()).as(result.toString()).isZero();
        return result;
    }

    @Nested
    @DisplayName("an untrusted directory")
    class Untrusted {

        private HttpServer server;
        private final AtomicInteger requests = new AtomicInteger();

        @BeforeEach
        void start() throws IOException {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/", exchange -> {
                requests.incrementAndGet();
                byte[] body = "[{\"v\":\"leaked\"}]".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
        }

        @AfterEach
        void stop() {
            server.stop(0);
        }

        private void hook() throws IOException {
            catalog("hook.relix", "source Hook from http { url: \"http://127.0.0.1:"
                    + server.getAddress().getPort() + "/collect\", schema: { v: STRING } };\n");
        }

        @Test
        @DisplayName("is not defined at all: its connection is never opened, and the run exits 5")
        void neverOpened() throws IOException {
            hook();

            var result = cli().run("-e", "Hook");

            assertThat(requests).hasValue(0);
            assertThat(result.status()).as(result.toString()).isEqualTo(5);
            assertThat(result.err())
                    .contains("relix: warning: skipping " + acme.resolve(".relix") + ", which is not trusted;"
                            + " to load it: relix catalog trust " + acme)
                    .contains("Hook is declared in " + acme.resolve(".relix/catalog/hook.relix")
                            + ", which is not trusted");
        }

        @Test
        @DisplayName("is loaded once trusted")
        void loadedOnceTrusted() throws IOException {
            hook();
            trust();

            var result = cli().run("-e", "Hook");

            assertThat(result.status()).as(result.toString()).isZero();
            assertThat(result.out()).isEqualTo("v\nleaked\n");
            assertThat(requests.get()).isPositive();
            assertThat(result.err()).doesNotContain("not trusted");
        }

        @Test
        @DisplayName("is skipped with one line, and a script that does not need it still runs")
        void scriptThatDoesNotNeedIt() throws IOException {
            hook();
            catalog("more.relix", table("Other", "x"));
            write(root.resolve("mine.relix"), table("Mine", "m"));

            var result = cli().run("--catalog", root.resolve("mine.relix").toString(), "-e", "Mine");

            assertThat(result.status()).as(result.toString()).isZero();
            assertThat(result.err().lines()).hasSize(1);
        }

        @Test
        @DisplayName("an unknown name it does not declare is still the script's mistake: exit 3")
        void unrelatedMistake() throws IOException {
            hook();

            var result = cli().run("-e", "Nowhere");

            assertThat(result.status()).as(result.toString()).isEqualTo(3);
        }

        @Test
        @DisplayName("supplies no relixrc and no profile values")
        void noRelixrcOrProfiles() throws IOException {
            write(acme.resolve(".relix/relixrc"), "output = csv\n");
            write(root.resolve("pair.relix"), "P := [\n| a | b |\n|---|---|\n| 1 | 2 |\n];\n");

            var result = cli().run("--catalog", root.resolve("pair.relix").toString(), "-e", "P");

            assertThat(result.out()).isEqualTo("a\tb\n1\t2\n");
        }

        @Test
        @DisplayName("is loaded under RELIX_TRUST_ALL=1")
        void trustAll() throws IOException {
            hook();

            var result = cli().env("RELIX_TRUST_ALL", "1").run("-e", "Hook");

            assertThat(result.status()).as(result.toString()).isZero();
            assertThat(result.out()).isEqualTo("v\nleaked\n");
        }
    }

    @Nested
    @DisplayName("a trusted directory")
    class Trusted {

        @Test
        @DisplayName("is untrusted again when a file is edited, added or removed")
        void changeUntrusts() throws IOException {
            Path file = catalog("a.relix", table("A", "a"));

            trust();
            assertThat(cli().run("-e", "A").status()).isZero();

            write(file, table("A", "edited"));
            assertThat(cli().run("-e", "A").status()).isEqualTo(5);

            trust();
            catalog("b.relix", table("B", "b"));
            assertThat(cli().run("-e", "A").status()).isEqualTo(5);

            trust();
            Files.delete(acme.resolve(".relix/catalog/b.relix"));
            assertThat(cli().run("-e", "A").status()).isEqualTo(5);

            trust();
            write(acme.resolve(".relix/relixrc"), "output = csv\n");
            assertThat(cli().run("-e", "A").status()).isEqualTo(5);
        }

        @Test
        @DisplayName("is trusted by naming the project or its .relix/, from anywhere")
        void namedDirectory() throws IOException {
            catalog("a.relix", table("A", "a"));

            var byProject = Cli.in(home).home(home).run("catalog", "trust", "work/acme");
            var run = cli().run("-e", "A");
            var byLevel = Cli.in(home).home(home).run("catalog", "trust", acme.resolve(".relix").toString());

            assertThat(byProject.status()).as(byProject.toString()).isZero();
            assertThat(run.status()).as(run.toString()).isZero();
            assertThat(byLevel.status()).as(byLevel.toString()).isZero();
        }

        @Test
        @DisplayName("is no longer trusted once revoked")
        void revoke() throws IOException {
            catalog("a.relix", table("A", "a"));
            trust();

            var revoked = Cli.in(home).home(home).run("catalog", "trust", "--revoke", "work/acme");
            var run = cli().run("-e", "A");
            var again = Cli.in(home).home(home).run("catalog", "trust", "--revoke", "work/acme");

            assertThat(revoked.status()).as(revoked.toString()).isZero();
            assertThat(run.status()).as(run.toString()).isEqualTo(5);
            assertThat(again.status()).isEqualTo(2);
            assertThat(again.err()).contains("work/acme is not trusted");
        }

        @Test
        @DisplayName("--list shows each directory and whether it has changed since")
        void list() throws IOException {
            Path other = Files.createDirectories(home.resolve("work/other"));
            write(other.resolve(".relix/catalog/o.relix"), table("O", "o"));
            Path file = catalog("a.relix", table("A", "a"));
            trust();
            assertThat(Cli.in(other).home(home).run("catalog", "trust").status()).isZero();
            write(file, table("A", "edited"));

            var tsv = cli().run("catalog", "trust", "--list");
            var table = Cli.in(acme).home(home).run("catalog", "trust", "--list", "--color=never");

            assertThat(tsv.status()).as(tsv.toString()).isZero();
            assertThat(tsv.out()).isEqualTo("directory\tstate\n" + acme + "\tchanged\n" + other + "\ttrusted\n");
            assertThat(table.out()).contains("directory").contains("changed").contains("(2 rows)");
        }
    }

    @Test
    @DisplayName("~/.relix is trusted without asking")
    void userLevel() throws IOException {
        write(home.resolve(".relix/catalog/u.relix"), table("U", "u"));

        var result = cli().run("-e", "U");

        assertThat(result.status()).as(result.toString()).isZero();
        assertThat(result.err()).isEmpty();
    }

    @Test
    @DisplayName("trusting a directory with no .relix/ is a usage error")
    void nothingToTrust() {
        var result = cli().run("catalog", "trust");

        assertThat(result.status()).isEqualTo(2);
        assertThat(result.err()).contains("has no .relix directory to trust");
    }

    @Test
    @DisplayName("DIR, --list and --revoke go one at a time")
    void oneAtATime() {
        var result = cli().run("catalog", "trust", "--list", "x");

        assertThat(result.status()).isEqualTo(2);
    }

    @Test
    @DisplayName("relix catalog alone prints its usage and exits 2")
    void catalogAlone() {
        var result = cli().run("catalog");

        assertThat(result.status()).isEqualTo(2);
        assertThat(result.err()).contains("Usage: relix catalog");
    }
}
