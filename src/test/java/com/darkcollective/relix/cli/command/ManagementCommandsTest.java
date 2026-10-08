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
import com.darkcollective.relix.docs.RelixDocs;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The management commands, {@code drivers}, {@code connectors}, {@code doc}, {@code help},
 * {@code version} and {@code completion} (design §3), end to end through {@code Main}.
 *
 * <p>Nothing here downloads: installing a driver or connector that is not installed would
 * reach the network, so only the paths that do not are run.
 */
@DisplayName("Management commands")
class ManagementCommandsTest {

    @TempDir
    Path dir;

    private Cli cli() {
        return Cli.in(dir);
    }

    @Nested
    @DisplayName("version")
    class Version {

        @Test
        @DisplayName("prints relix's version and the engine's, as --version does")
        void version() {
            var result = cli().run("version");

            assertThat(result.status()).isZero();
            assertThat(result.out()).matches("relix \\S+ \\(engine \\S+\\)\\n").isEqualTo(cli().run("--version").out());
        }
    }

    @Nested
    @DisplayName("help")
    class Help {

        @Test
        @DisplayName("prints a command's usage to stdout")
        void command() {
            var result = cli().run("help", "fmt");

            assertThat(result.status()).isZero();
            assertThat(result.out()).startsWith("Usage: relix fmt").contains("--keywords");
        }

        @Test
        @DisplayName("prints the main usage, listing every command, with no COMMAND")
        void main() {
            var result = cli().run("help");

            assertThat(result.status()).isZero();
            assertThat(result.out()).startsWith("Usage: relix");
            for (String command : List.of("run", "check", "explain", "optimize", "trace", "bundle", "ir",
                    "provenance", "fmt", "catalog", "drivers", "connectors", "doc", "help", "version", "completion")) {
                assertThat(result.out()).as(command).containsPattern("(?m)^  " + command + "\\b");
            }
        }
    }

    @Nested
    @DisplayName("drivers")
    class Drivers {

        @Test
        @DisplayName("ls lists the drivers relix can install, as rows")
        void ls() {
            var result = cli().piped().run("drivers", "ls");

            assertThat(result.status()).as(result.toString()).isZero();
            List<String> lines = result.out().lines().toList();
            assertThat(lines.getFirst()).isEqualTo("name\tscheme\tinstalled");
            assertThat(lines).anyMatch(l -> l.startsWith("postgresql\tjdbc:postgresql:\t"))
                    .anyMatch(l -> l.startsWith("mysql\tjdbc:mysql:\t"));
        }

        @Test
        @DisplayName("ls writes ndjson with -o")
        void ndjson() {
            var result = cli().run("drivers", "ls", "-o", "ndjson");

            assertThat(result.out()).contains("{\"name\":\"postgresql\",\"scheme\":\"jdbc:postgresql:\",\"installed\":");
        }

        @Test
        @DisplayName("install of a driver relix does not know is a usage error naming those it does")
        void unknown() {
            var result = cli().run("drivers", "install", "nope");

            assertThat(result.status()).isEqualTo(2);
            assertThat(result.err()).isEqualTo("relix: no driver named 'nope'; relix can install postgresql, mysql\n");
        }

        @Test
        @DisplayName("with no subcommand prints usage and exits 2")
        void bare() {
            assertThat(cli().run("drivers").status()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("connectors")
    class Connectors {

        @Test
        @DisplayName("ls lists the connectors installed, as rows")
        void ls() {
            var result = cli().piped().run("connectors", "ls");

            assertThat(result.status()).isZero();
            assertThat(result.out()).startsWith("type\tinstalled\n").contains("csv\ttrue\n");
        }

        @Test
        @DisplayName("install of one already installed says so and downloads nothing")
        void installed() {
            var result = cli().run("connectors", "install", "csv");

            assertThat(result.status()).isZero();
            assertThat(result.out()).isEmpty();
            assertThat(result.err()).isEqualTo("relix: the csv connector is already installed\n");
        }
    }

    @Nested
    @DisplayName("doc")
    class Doc {

        @Test
        @DisplayName("shows one page for a glyph, its keyword and its name")
        void spellings() {
            var glyph = cli().run("doc", "σ");

            assertThat(glyph.status()).isZero();
            assertThat(glyph.out()).contains("Selection (σ / SELECT)");
            assertThat(cli().run("doc", "select").out()).isEqualTo(glyph.out());
            assertThat(cli().run("doc", "Selection").out()).isEqualTo(glyph.out());
        }

        @Test
        @DisplayName("--markdown prints the page as the reference holds it")
        void markdown() {
            var result = cli().run("doc", "--markdown", "σ");

            assertThat(result.out()).isEqualTo(RelixDocs.referencePage("operators/select.md").orElseThrow());
        }

        @Test
        @DisplayName("gives a shared name to the operator, and --function finds the function")
        void function() {
            assertThat(cli().run("doc", "fix").out()).contains("(FIX)");
            assertThat(cli().run("doc", "--function", "fix").out()).contains("Fix (");
        }

        @Test
        @DisplayName("an unknown topic is a usage error")
        void unknown() {
            var result = cli().run("doc", "no-such-topic");

            assertThat(result.status()).isEqualTo(2);
            assertThat(result.err()).isEqualTo("relix: no reference page for 'no-such-topic'; relix doc lists them\n");
        }

        @Test
        @DisplayName("with no topic lists the pages as rows, language pages then functions")
        void list() {
            var result = cli().piped().run("doc");
            List<String> lines = result.out().lines().toList();

            assertThat(lines.getFirst()).isEqualTo("topic\tcategory\ttitle\tsummary");
            assertThat(lines).anyMatch(l -> l.startsWith("σ\toperator\tSelection\t"))
                    .anyMatch(l -> l.startsWith("Fix\tfunction\tFix\t"));
            assertThat(lines.size() - 1).isGreaterThan(RelixDocs.referencePages().size());
        }

        @Test
        @DisplayName("--function lists only the functions")
        void functions() {
            var result = cli().piped().run("doc", "--function");

            assertThat(result.out().lines().skip(1)).isNotEmpty().allMatch(l -> l.split("\t")[1].equals("function"));
        }

        @Test
        @DisplayName("resolves every key of every language page")
        void everyKey() {
            List<String> missing = new ArrayList<>();
            for (var page : RelixDocs.referencePages()) {
                for (String key : page.keys()) {
                    var result = cli().run("doc", "--markdown", key);
                    if (result.status() != 0 || result.out().isEmpty()) {
                        missing.add(page.path() + ": " + key);
                    }
                }
            }
            assertThat(missing).isEmpty();
        }
    }

    @Nested
    @DisplayName("completion")
    class Completion_ {

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"bash", "zsh", "fish", "powershell"})
        @DisplayName("prints a script that completes every command")
        void script(String shell) {
            var result = cli().run("completion", shell);

            assertThat(result.status()).as(result.toString()).isZero();
            for (String command : List.of("run", "check", "fmt", "catalog", "drivers", "doc", "completion")) {
                assertThat(result.out()).as(command).contains(command);
            }
            assertThat(result.out()).contains("keywords").contains("trace");
        }

        @Test
        @DisplayName("an unknown shell is a usage error")
        void unknown() {
            var result = cli().run("completion", "tcsh");

            assertThat(result.status()).isEqualTo(2);
            assertThat(result.err()).contains("'tcsh' is not one of bash, zsh, fish, powershell");
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"bash", "zsh", "fish"})
        @DisabledOnOs(OS.WINDOWS)
        @DisplayName("is read without error by its shell, where the shell is installed")
        void posixSyntax(String shell) throws Exception {
            Path script = write(shell);
            List<String> check = shell.equals("fish")
                    ? List.of("fish", "--no-execute", script.toString())
                    : List.of(shell, "-n", script.toString());

            assertSyntax(shell, check);
        }

        @Test
        @DisplayName("is read without error by PowerShell, where it is installed")
        void powershellSyntax() throws Exception {
            Path script = write("powershell");
            String parse = "$errors = $null; [System.Management.Automation.Language.Parser]::ParseFile('"
                    + script.toString().replace("'", "''") + "', [ref]$null, [ref]$errors) | Out-Null; "
                    + "if ($errors.Count -gt 0) { $errors | ForEach-Object { Write-Error $_.Message }; exit 1 }";

            assertSyntax("pwsh", List.of("pwsh", "-NoProfile", "-NonInteractive", "-Command", parse));
        }

        private Path write(String shell) throws IOException {
            var result = cli().run("completion", shell);
            return Files.writeString(dir.resolve("relix." + shell), result.out(), StandardCharsets.UTF_8);
        }

        private void assertSyntax(String shell, List<String> command) throws Exception {
            assumeTrue(available(shell), shell + " is not installed");
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue();
            assertThat(process.exitValue()).as(output).isZero();
        }

        private static boolean available(String shell) {
            try {
                Process process = new ProcessBuilder(shell, shell.equals("pwsh") ? "-Version" : "--version")
                        .redirectErrorStream(true).start();
                process.getInputStream().readAllBytes();
                return process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0;
            } catch (IOException e) {
                return false;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }
}
