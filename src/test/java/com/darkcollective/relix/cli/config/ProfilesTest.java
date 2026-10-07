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
package com.darkcollective.relix.cli.config;

import com.darkcollective.relix.cli.Cli;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Profiles and {@code ${VAR}} placeholders (design §5.5), end to end over directory trees.
 *
 * <p>The tree is a home with a project and a subdirectory of it. A script reads a CSV file
 * whose name is a placeholder, and the rows it prints say which value won: each candidate
 * file holds a different number of rows.
 */
@DisplayName("Profiles and placeholders")
class ProfilesTest {

    private static final String SCRIPT = """
            source T from csv("${DATA}") { header: true, schema: { x: NUMBER } };
            query { T };
            """;

    @TempDir
    Path home;

    Path project;
    Path work;

    @BeforeEach
    void tree() throws IOException {
        project = Files.createDirectories(home.resolve("project"));
        work = Files.createDirectories(project.resolve("work"));
        // one.csv has one row, two.csv two, and so on: a run's row count names the file it read.
        for (String name : new String[] {"one", "two", "three", "four"}) {
            int rows = switch (name) { case "one" -> 1; case "two" -> 2; case "three" -> 3; default -> 4; };
            Files.writeString(work.resolve(name + ".csv"), "x\n" + "1\n".repeat(rows));
        }
    }

    private Cli cli() {
        return Cli.in(work).home(home);
    }

    private void profiles(Path dir, String json) throws IOException {
        Path relix = Files.createDirectories(dir.resolve(".relix"));
        Path file = Files.writeString(relix.resolve("profiles.json"), json);
        if (Files.getFileAttributeView(file, PosixFileAttributeView.class) != null) {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        }
    }

    @Nested
    @DisplayName("precedence")
    class Precedence {

        @BeforeEach
        void profile() throws IOException {
            profiles(project, "{ \"dev\": { \"DATA\": \"two.csv\" } }");
        }

        @Test
        @DisplayName("the process environment answers when nothing else does")
        void environment() {
            var result = cli().env("DATA", "one.csv").run("-e", SCRIPT);

            assertThat(result.out()).as(result.toString()).contains("(1 row)");
        }

        @Test
        @DisplayName("the selected profile beats the environment")
        void profileBeatsEnvironment() {
            var result = cli().env("DATA", "one.csv").run("-P", "dev", "-e", SCRIPT);

            assertThat(result.out()).as(result.toString()).contains("(2 rows)");
        }

        @Test
        @DisplayName("-D beats the profile")
        void defineBeatsProfile() {
            var result = cli().env("DATA", "one.csv").run("-P", "dev", "-D", "DATA=three.csv", "-e", SCRIPT);

            assertThat(result.out()).as(result.toString()).contains("(3 rows)");
        }

        @Test
        @DisplayName("RELIX_PROFILE selects a profile when -P does not")
        void profileFromEnvironment() {
            var result = cli().env("RELIX_PROFILE", "dev").run("-e", SCRIPT);

            assertThat(result.out()).as(result.toString()).contains("(2 rows)");
        }

        @Test
        @DisplayName("an unselected profile is not read")
        void unselected() {
            var result = cli().env("DATA", "one.csv").run("-e", SCRIPT);

            assertThat(result.out()).as(result.toString()).contains("(1 row)");
        }
    }

    @Nested
    @DisplayName("merging across levels")
    class Levels {

        @Test
        @DisplayName("a nearer file's value replaces a farther one's, variable by variable")
        void nearestWins() throws IOException {
            profiles(home, "{ \"dev\": { \"DATA\": \"one.csv\", \"OTHER\": \"x\" } }");
            profiles(project, "{ \"dev\": { \"DATA\": \"two.csv\" } }");
            profiles(work, "{ \"dev\": { \"DATA\": \"three.csv\" } }");

            var result = cli().run("-P", "dev", "-e", SCRIPT);

            assertThat(result.out()).as(result.toString()).contains("(3 rows)");
        }

        @Test
        @DisplayName("a profile defined only farther up still applies")
        void inheritedFromAbove() throws IOException {
            profiles(home, "{ \"dev\": { \"DATA\": \"four.csv\" } }");
            profiles(work, "{ \"prod\": { \"DATA\": \"one.csv\" } }");

            var result = cli().run("-P", "dev", "-e", SCRIPT);

            assertThat(result.out()).as(result.toString()).contains("(4 rows)");
        }

        @Test
        @DisplayName("a .relix/root marker stops the walk, though the user's own level still applies")
        void rootMarker() throws IOException {
            profiles(home, "{ \"dev\": { \"DATA\": \"four.csv\" } }");
            profiles(project, "{ \"dev\": { \"DATA\": \"two.csv\" } }");
            Files.createDirectories(work.resolve(".relix"));
            Files.writeString(work.resolve(".relix/root"), "");

            var result = cli().run("-P", "dev", "-e", SCRIPT);

            assertThat(result.out()).as("project's file is beyond the root").contains("(4 rows)");
        }

        @Test
        @DisplayName("-C moves where the walk starts")
        void minusC() throws IOException {
            profiles(work, "{ \"dev\": { \"DATA\": \"three.csv\" } }");
            Files.writeString(project.resolve("one.csv"), "x\n1\n");

            var fromProject = Cli.in(project).home(home).run("-P", "dev", "-e", SCRIPT);
            var fromWork = Cli.in(project).home(home).run("-C", "work", "-P", "dev", "-e", SCRIPT);

            assertThat(fromProject.status()).as("no profile above project").isEqualTo(2);
            assertThat(fromWork.out()).as(fromWork.toString()).contains("(3 rows)");
        }

        @Test
        @DisplayName("outside the home, only the working directory's own .relix/ is read")
        void outsideHome(@TempDir Path elsewhere) throws IOException {
            Path dir = Files.createDirectories(elsewhere.resolve("a/b"));
            Files.writeString(dir.resolve("two.csv"), "x\n1\n1\n");
            profiles(elsewhere.resolve("a"), "{ \"dev\": { \"DATA\": \"one.csv\" } }");
            profiles(dir, "{ \"dev\": { \"DATA\": \"two.csv\" } }");

            var result = Cli.in(dir).home(home).run("-P", "dev", "-e", SCRIPT);

            assertThat(result.out()).as(result.toString()).contains("(2 rows)");
            assertThat(RelixDirectories.discover(dir, home)).containsExactly(dir.resolve(".relix"));
        }
    }

    @Nested
    @DisplayName("refusals")
    class Refusals {

        @Test
        @DisplayName("a profiles.json group or others can read is refused, naming the chmod that fixes it")
        void loosePermissions() throws IOException {
            profiles(project, "{ \"dev\": { \"DATA\": \"two.csv\" } }");
            Path file = project.resolve(".relix/profiles.json");
            assumeTrue(Files.getFileAttributeView(file, PosixFileAttributeView.class) != null,
                    "the permission check applies where there are POSIX permissions");
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r-----"));

            var result = cli().run("-P", "dev", "-e", SCRIPT);

            assertThat(result.status()).isEqualTo(5);
            assertThat(result.out()).isEmpty();
            assertThat(result.err()).contains("is readable by group or others")
                    .contains("chmod 600 " + file);
        }

        @Test
        @DisplayName("an unknown profile is a usage error naming those that exist")
        void unknownProfile() throws IOException {
            profiles(project, "{ \"dev\": {}, \"prod\": {} }");

            var result = cli().run("-P", "staging", "-e", SCRIPT);

            assertThat(result.status()).isEqualTo(2);
            assertThat(result.err()).isEqualTo("relix: no profile 'staging' (defined: dev, prod)\n");
        }

        @Test
        @DisplayName("a file that is not an object of objects of strings is refused")
        void malformed() throws IOException {
            profiles(project, "{ \"dev\": { \"PORT\": 5432 } }");

            var result = cli().run("-P", "dev", "-e", SCRIPT);

            assertThat(result.status()).isEqualTo(5);
            assertThat(result.err()).contains("profiles.json: expected 'PORT' in profile 'dev' to be a string");
        }

        @Test
        @DisplayName("a placeholder nothing answers is an error naming it")
        void unresolved() {
            var result = cli().run("-e", SCRIPT);

            assertThat(result.status()).isEqualTo(3);
            assertThat(result.err()).contains("${DATA}");
        }
    }
}
