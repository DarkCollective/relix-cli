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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code relix init}, end to end through {@code Main}.
 */
@DisplayName("relix init")
class InitTest {

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

    private static Stream<String> tree(Path dir) throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.map(p -> dir.relativize(p).toString().replace('\\', '/')).sorted().toList().stream();
        }
    }

    @Test
    @DisplayName("makes catalog/, a private empty profiles.json and the .gitignore, and nothing else")
    void plain() throws IOException {
        var result = cli().run("init");

        assertThat(result.status()).as(result.toString()).isZero();
        assertThat(result.out()).isEmpty();
        Path level = acme.resolve(".relix");
        assertThat(tree(level)).containsExactly("", ".gitignore", "catalog", "profiles.json");
        assertThat(Files.readString(level.resolve("profiles.json"))).isEqualTo("{}\n");
        assertThat(Files.readString(level.resolve(".gitignore"))).isEqualTo("profiles.json\n");
        assertThat(result.err()).contains("created " + level).contains("trusting " + level);
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    @DisplayName("makes profiles.json readable by its owner alone")
    void privateProfiles() throws IOException {
        cli().run("init");

        assertThat(PosixFilePermissions.toString(
                Files.getPosixFilePermissions(acme.resolve(".relix/profiles.json")))).isEqualTo("rw-------");
    }

    @Test
    @DisplayName("writes relixrc with -o and -P, defining the profile it names, and root with --root")
    void everyFlag() throws IOException {
        Path other = home.resolve("work/other");
        var result = cli().run("init", "../other", "--root", "--profile=staging", "--output=csv");

        assertThat(result.status()).as(result.toString()).isZero();
        Path level = other.resolve(".relix");
        assertThat(tree(level)).containsExactly("", ".gitignore", "catalog", "profiles.json", "relixrc", "root");
        assertThat(Files.readString(level.resolve("relixrc"))).isEqualTo("output = csv\nprofile = staging\n");
        assertThat(Files.readString(level.resolve("profiles.json"))).isEqualTo("{\n  \"staging\": {}\n}\n");
        assertThat(Files.exists(acme.resolve(".relix"))).isFalse();
    }

    @Test
    @DisplayName("leaves the project trusted: the next run there says nothing of trust, and uses relixrc")
    void trusted() {
        cli().run("init", "-o", "csv", "-P", "staging");

        var result = cli().run("-e", "T := [\n| v |\n|---|\n| 1 |\n];\nquery T;");

        assertThat(result.status()).as(result.toString()).isZero();
        assertThat(result.err()).doesNotContain("trust");
        assertThat(result.out()).isEqualTo("v\n1\n");
    }

    @Test
    @DisplayName("refuses a directory that has a .relix/ already, exiting 2 and touching nothing")
    void exists() throws IOException {
        cli().run("init");
        Files.writeString(acme.resolve(".relix/profiles.json"), "{ \"dev\": {} }\n");

        var result = cli().run("init", "--root", "-o", "csv");

        assertThat(result.status()).as(result.toString()).isEqualTo(2);
        assertThat(result.err()).contains(acme.resolve(".relix") + " already exists");
        assertThat(tree(acme.resolve(".relix"))).containsExactly("", ".gitignore", "catalog", "profiles.json");
        assertThat(Files.readString(acme.resolve(".relix/profiles.json"))).isEqualTo("{ \"dev\": {} }\n");
    }

    @Test
    @DisplayName("refuses an unknown output format before writing anything")
    void badFormat() {
        var result = cli().run("init", "-o", "yaml");

        assertThat(result.status()).as(result.toString()).isEqualTo(2);
        assertThat(result.err()).contains("unknown output format: 'yaml'");
        assertThat(Files.exists(acme.resolve(".relix"))).isFalse();
    }

    @Test
    @DisplayName("warns, outside $HOME, that only the working directory's .relix/ is read there")
    void outsideHome() throws IOException {
        Path elsewhere = Files.createDirectories(root.resolve("srv/app"));

        var result = Cli.in(elsewhere).home(home).piped().run("init");

        assertThat(result.status()).as(result.toString()).isZero();
        assertThat(result.err()).contains("warning: " + elsewhere + " is outside " + home);
        assertThat(Files.isDirectory(elsewhere.resolve(".relix/catalog"))).isTrue();
    }

    @Test
    @DisplayName("in $HOME makes the user's own ~/.relix, which needs no trust")
    void userLevel() throws IOException {
        var result = Cli.in(home).home(home).piped().run("init");

        assertThat(result.status()).as(result.toString()).isZero();
        assertThat(result.err()).doesNotContain("trusting");
        assertThat(Files.exists(home.resolve(".relix/trusted"))).isFalse();
    }
}
