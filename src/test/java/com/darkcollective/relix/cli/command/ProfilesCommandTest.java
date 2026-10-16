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
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@code relix profiles}, end to end through {@code Main}: its verbs at project and user
 * scope, and the trust rule every command that writes under {@code .relix/} follows.
 */
@DisplayName("relix profiles")
class ProfilesCommandTest {

    private static final String SECRET = "hunter2-do-not-print";

    @TempDir
    Path root;

    Path home;
    Path acme;

    @BeforeEach
    void tree() throws IOException {
        home = Files.createDirectories(root.resolve("home"));
        acme = Files.createDirectories(home.resolve("work/acme"));
    }

    private Cli cli() {
        return Cli.in(acme).home(home).piped();
    }

    private Cli.Result ok(Cli.Result result) {
        assertThat(result.status()).as(result.toString()).isZero();
        return result;
    }

    private void init() {
        ok(cli().run("init"));
    }

    private Path projectFile() {
        return acme.resolve(".relix/profiles.json");
    }

    /** A path as tsv writes it: a backslash, Windows' separator, escaped. */
    private static String escaped(Path path) {
        return path.toString().replace("\\", "\\\\");
    }

    @Nested
    @DisplayName("in a project")
    class Project {

        @BeforeEach
        void project() {
            init();
        }

        @Test
        @DisplayName("set reads the value from standard input and writes it to the project's file alone")
        void set() throws IOException {
            var result = ok(cli().stdin(SECRET + "\n").run("profiles", "set", "staging", "WAREHOUSE_PASSWORD"));

            assertThat(result.out()).isEmpty();
            assertThat(result.err()).doesNotContain(SECRET)
                    .contains("set WAREHOUSE_PASSWORD in profile staging in " + projectFile());
            assertThat(Files.readString(projectFile())).isEqualTo(
                    "{\n  \"staging\": {\n    \"WAREHOUSE_PASSWORD\": \"" + SECRET + "\"\n  }\n}\n");
            assertThat(Files.exists(home.resolve(".relix/profiles.json"))).isFalse();
        }

        @Test
        @DisabledOnOs(OS.WINDOWS)
        @DisplayName("set leaves the file readable by its owner alone")
        void privateFile() throws IOException {
            ok(cli().stdin(SECRET).run("profiles", "set", "staging", "PW"));

            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(projectFile())))
                    .isEqualTo("rw-------");
        }

        @Test
        @DisplayName("ls lists profile, variable and file, never a value; get prints the value")
        void lsAndGet() {
            ok(cli().stdin(SECRET).run("profiles", "set", "staging", "WAREHOUSE_PASSWORD"));
            ok(cli().stdin("jdbc:h2:mem:x").run("profiles", "set", "staging", "WAREHOUSE_URL"));

            var ls = ok(cli().run("profiles", "ls"));
            assertThat(ls.out()).isEqualTo("profile\tvariable\tfile\n"
                    + "staging\tWAREHOUSE_PASSWORD\t" + escaped(projectFile()) + "\n"
                    + "staging\tWAREHOUSE_URL\t" + escaped(projectFile()) + "\n");
            assertThat(ok(cli().run("profiles")).out()).isEqualTo(ls.out());

            assertThat(ok(cli().run("profiles", "get", "staging", "WAREHOUSE_PASSWORD")).out())
                    .isEqualTo(SECRET + "\n");
        }

        @Test
        @DisplayName("set replaces a value, and the value a run resolves is the new one")
        void replace() {
            ok(cli().stdin("one").run("profiles", "set", "dev", "V"));
            ok(cli().stdin("two").run("profiles", "set", "dev", "V"));

            var run = ok(cli().run("-P", "dev", "-e", "T := [\n| v |\n|---|\n| 1 |\n];\nquery T;"));
            assertThat(ok(cli().run("profiles", "get", "dev", "V")).out()).isEqualTo("two\n");
            assertThat(run.err()).doesNotContain("trust");
        }

        @Test
        @DisplayName("rm removes a variable, or a whole profile, and refuses one the file does not set")
        void rm() throws IOException {
            ok(cli().stdin("a").run("profiles", "set", "dev", "A"));
            ok(cli().stdin("b").run("profiles", "set", "dev", "B"));

            ok(cli().run("profiles", "rm", "dev", "A"));
            assertThat(Files.readString(projectFile())).isEqualTo("{\n  \"dev\": {\n    \"B\": \"b\"\n  }\n}\n");

            var missing = cli().run("profiles", "rm", "dev", "A");
            assertThat(missing.status()).as(missing.toString()).isEqualTo(2);
            assertThat(missing.err()).contains("does not set A");

            ok(cli().run("profiles", "rm", "dev"));
            assertThat(Files.readString(projectFile())).isEqualTo("{}\n");
        }

        @Test
        @DisplayName("get of a variable the profile does not set exits 2")
        void getMissing() {
            ok(cli().stdin("a").run("profiles", "set", "dev", "A"));

            var result = cli().run("profiles", "get", "dev", "B");

            assertThat(result.status()).as(result.toString()).isEqualTo(2);
            assertThat(result.out()).isEmpty();
        }

        @Test
        @DisplayName("set refuses an empty value and a name that is not a variable, exiting 2")
        void refusals() {
            var empty = cli().stdin("\n").run("profiles", "set", "dev", "A");
            assertThat(empty.status()).as(empty.toString()).isEqualTo(2);
            assertThat(empty.err()).contains("no value");

            var name = cli().stdin("x").run("profiles", "set", "dev", "not-a-name");
            assertThat(name.status()).as(name.toString()).isEqualTo(2);
        }

        @Test
        @DisabledOnOs(OS.WINDOWS)
        @DisplayName("set refuses a file readable by others, exiting 5, and leaves it as it was")
        void loose() throws IOException {
            Files.setPosixFilePermissions(projectFile(), PosixFilePermissions.fromString("rw-r--r--"));

            var result = cli().stdin(SECRET).run("profiles", "set", "dev", "PW");

            assertThat(result.status()).as(result.toString()).isEqualTo(5);
            assertThat(result.err()).contains("chmod 600");
            assertThat(Files.readString(projectFile())).isEqualTo("{}\n");
        }

        @Test
        @DisplayName("warns when git tracks the file")
        void tracked() throws IOException, InterruptedException {
            String path = System.getenv("PATH");
            assumeTrue(path != null && git(acme, "init", "-q") == 0, "git is installed");
            assumeTrue(git(acme, "add", "-f", ".relix/profiles.json") == 0, "git can add a file");

            var result = ok(cli().env("PATH", path).stdin(SECRET).run("profiles", "set", "dev", "PW"));

            assertThat(result.err()).contains("is tracked by git").contains("git rm --cached");
        }

        @Test
        @DisplayName("says nothing of git when the file is not tracked")
        void untracked() throws IOException, InterruptedException {
            String path = System.getenv("PATH");
            assumeTrue(path != null && git(acme, "init", "-q") == 0, "git is installed");

            var result = ok(cli().env("PATH", path).stdin(SECRET).run("profiles", "set", "dev", "PW"));

            assertThat(result.err()).doesNotContain("git");
        }
    }

    @Nested
    @DisplayName("with --user")
    class User {

        @Test
        @DisplayName("every verb works on ~/.relix/profiles.json, with or without a project")
        void everyVerb() throws IOException {
            Path file = home.resolve(".relix/profiles.json");

            ok(cli().stdin(SECRET).run("profiles", "set", "--user", "dev", "PW"));
            assertThat(Files.readString(file)).contains(SECRET);
            assertThat(ok(cli().run("profiles", "ls", "--user")).out())
                    .isEqualTo("profile\tvariable\tfile\ndev\tPW\t" + escaped(file) + "\n");
            assertThat(ok(cli().run("profiles", "get", "--user", "dev", "PW")).out()).isEqualTo(SECRET + "\n");
            ok(cli().run("profiles", "rm", "--user", "dev", "PW"));
            assertThat(Files.readString(file)).isEqualTo("{\n  \"dev\": {}\n}\n");
        }

        @Test
        @DisplayName("is what set needs where there is no project, which it otherwise refuses with exit 2")
        void noProject() {
            var result = cli().stdin(SECRET).run("profiles", "set", "dev", "PW");

            assertThat(result.status()).as(result.toString()).isEqualTo(2);
            assertThat(result.err()).contains("relix init").contains("--user");
        }

        @Test
        @DisplayName("a project's value beats the user's for get, as it does for a run")
        void nearestWins() {
            init();
            ok(cli().stdin("user").run("profiles", "set", "--user", "dev", "V"));
            ok(cli().stdin("project").run("profiles", "set", "dev", "V"));

            assertThat(ok(cli().run("profiles", "get", "dev", "V")).out()).isEqualTo("project\n");
            assertThat(ok(cli().run("profiles", "get", "--user", "dev", "V")).out()).isEqualTo("user\n");
        }
    }

    @Nested
    @DisplayName("keeps a project's trust as it was")
    class Trust {

        private String state() {
            var list = ok(cli().run("catalog", "trust", "--list"));
            return list.out().lines()
                    .filter(line -> line.startsWith(escaped(acme) + "\t"))
                    .map(line -> line.substring(line.indexOf('\t') + 1))
                    .findFirst().orElse("untrusted");
        }

        @Test
        @DisplayName("a trusted, unchanged project stays trusted across set and rm")
        void staysTrusted() {
            init();

            var set = ok(cli().stdin(SECRET).run("profiles", "set", "dev", "PW"));
            assertThat(set.err()).contains(acme.resolve(".relix") + " is still trusted");
            assertThat(state()).isEqualTo("trusted");

            ok(cli().run("profiles", "rm", "dev"));
            assertThat(state()).isEqualTo("trusted");
        }

        @Test
        @DisplayName("a project changed by someone else since it was trusted stays untrusted")
        void changedStaysChanged() throws IOException {
            init();
            Files.writeString(acme.resolve(".relix/catalog/00-hook.relix"), "T := [\n| v |\n|---|\n| 1 |\n];\n");

            var set = ok(cli().stdin(SECRET).run("profiles", "set", "dev", "PW"));

            assertThat(set.err()).contains("is not trusted now").contains("relix catalog trust");
            assertThat(state()).isEqualTo("changed");
        }

        @Test
        @DisplayName("a project never trusted stays untrusted")
        void untrustedStaysUntrusted() throws IOException {
            Files.createDirectories(acme.resolve(".relix/catalog"));

            var set = ok(cli().stdin(SECRET).run("profiles", "set", "dev", "PW"));

            assertThat(set.err()).contains("is not trusted now");
            assertThat(state()).isEqualTo("untrusted");
        }
    }

    private static int git(Path dir, String... args) throws IOException, InterruptedException {
        String[] command = new String[args.length + 1];
        command[0] = "git";
        System.arraycopy(args, 0, command, 1, args.length);
        try {
            Process process = new ProcessBuilder(command).directory(dir.toFile())
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            return process.waitFor(30, TimeUnit.SECONDS) ? process.exitValue() : -1;
        } catch (IOException e) {
            return -1;
        }
    }
}
