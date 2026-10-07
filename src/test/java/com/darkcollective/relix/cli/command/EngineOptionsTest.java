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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The options that shape the engine session (design §4).
 */
@DisplayName("Engine options")
class EngineOptionsTest {

    private static final String NOW = "PROJECT NOW() -> t (LIMIT 1 (relix.functions))";

    @TempDir
    Path dir;

    @Test
    @DisplayName("--now pins NOW()")
    void now() {
        var result = Cli.in(dir).run("--now=2026-07-22T12:00:00Z", "-e", NOW);

        assertThat(result.status()).as(result.toString()).isZero();
        assertThat(result.out()).contains("2026-07-22");
    }

    @Test
    @DisplayName("RELIX_NOW pins NOW() when --now is not given, and --now beats it")
    void nowFromEnvironment() {
        var fromEnv = Cli.in(dir).env("RELIX_NOW", "2025-01-02T00:00:00Z").run("-e", NOW);
        var fromOption = Cli.in(dir).env("RELIX_NOW", "2025-01-02T00:00:00Z")
                .run("--now=2026-07-22T12:00:00Z", "-e", NOW);

        assertThat(fromEnv.out()).as(fromEnv.toString()).contains("2025-01-02");
        assertThat(fromOption.out()).contains("2026-07-22");
    }

    @Test
    @DisplayName("options may come before or after the command's name")
    void inheritedOptions() {
        var before = Cli.in(dir).run("--now=2026-07-22T12:00:00Z", "run", "-e", NOW);
        var after = Cli.in(dir).run("run", "--now=2026-07-22T12:00:00Z", "-e", NOW);

        assertThat(before.out()).contains("2026-07-22").isEqualTo(after.out());
    }

    @Test
    @DisplayName("short options cluster")
    void clustering() {
        var result = Cli.in(dir).run("-qe", "relix.relations");

        assertThat(result.status()).as(result.toString()).isZero();
    }

    @Test
    @DisplayName("a closed --sandbox refuses an external declaration it does not hold")
    void sandboxRefuses() throws IOException {
        Files.writeString(dir.resolve("sandbox.json"), "{}");
        Files.writeString(dir.resolve("t.csv"), "x\n1\n");

        var result = Cli.in(dir).run("--sandbox=sandbox.json", "-e", """
                source T from csv("t.csv") { header: true, schema: { x: NUMBER } };
                query { T };
                """);

        assertThat(result.status()).as(result.toString()).isEqualTo(3);
        assertThat(result.out()).isEmpty();
    }

    @Test
    @DisplayName("a --sandbox result cut at its row limit says so on stderr")
    void sandboxTruncates() throws IOException {
        Files.writeString(dir.resolve("sandbox.json"), "{ \"limits\": { \"maxOutputRows\": 2 } }");

        var result = Cli.in(dir).run("--sandbox=sandbox.json", "-e", "relix.functions");

        assertThat(result.status()).as(result.toString()).isZero();
        assertThat(result.out()).contains("(2 rows)");
        assertThat(result.err()).contains("the sandbox cut the result at 2 rows");
    }

    @Test
    @DisplayName("a --sandbox file that cannot be read is a usage error")
    void sandboxUnreadable() {
        var result = Cli.in(dir).run("--sandbox=missing.json", "-e", "relix.relations");

        assertThat(result.status()).isEqualTo(2);
        assertThat(result.err()).startsWith("relix: --sandbox ");
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"500ms, PT0.5S", "30s, PT30S", "5m, PT5M", "1h, PT1H", "PT2M, PT2M", "pt2m, PT2M"})
    @DisplayName("--timeout reads a number and a unit, or ISO-8601")
    void durations(String text, String expected) {
        assertThat(new DurationConverter().convert(text)).isEqualTo(Duration.parse(expected));
    }

    @Test
    @DisplayName("--timeout refuses what is not a positive duration")
    void badDurations() {
        assertThatThrownBy(() -> new DurationConverter().convert("soon")).hasMessageContaining("not a duration");
        assertThatThrownBy(() -> new DurationConverter().convert("0s")).hasMessageContaining("not a positive");
    }
}
