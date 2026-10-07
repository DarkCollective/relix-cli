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
package com.darkcollective.relix.cli;

import com.darkcollective.relix.embed.QueryExecutionException;
import com.darkcollective.relix.embed.RelixException;
import com.darkcollective.relix.embed.UnboundedRelationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.net.ConnectException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The exit-status table (design §3.4).
 */
@DisplayName("Exit status")
class ExitCodeTest {

    private static final String SOURCE = """
            source T from csv("t.csv") { header: true, schema: { x: NUMBER } };
            query { T };
            """;

    @TempDir
    Path dir;

    @Test
    @DisplayName("the codes are the design's")
    void table() {
        Map<ExitCode, Integer> codes = Arrays.stream(ExitCode.values())
                .collect(Collectors.toMap(Function.identity(), ExitCode::status));

        assertThat(codes).containsExactlyInAnyOrderEntriesOf(Map.of(
                ExitCode.SUCCESS, 0, ExitCode.ASSERTION, 1, ExitCode.USAGE, 2,
                ExitCode.ANALYSIS, 3, ExitCode.EXECUTION, 4, ExitCode.ENVIRONMENT, 5,
                ExitCode.INTERNAL, 70, ExitCode.INTERRUPTED, 130, ExitCode.PIPE_CLOSED, 141));
    }

    /** A run that should end with a given status: its name, how to set it up, its arguments. */
    record Case(String name, int status, Setup setup, String... args) {
        @Override
        public String toString() {
            return name;
        }
    }

    @FunctionalInterface
    interface Setup {
        Cli apply(Path dir) throws IOException;
    }

    static Stream<Arguments> runs() {
        return Stream.of(
                new Case("success", 0, Cli::in, "-e", "relix.relations"),
                new Case("an unknown option", 2, Cli::in, "--bogus"),
                new Case("nothing to run", 2, Cli::in),
                new Case("a bad --now", 2, Cli::in, "--now=yesterday", "-e", "relix.relations"),
                new Case("a bad --timeout", 2, Cli::in, "--timeout=soon", "-e", "relix.relations"),
                new Case("a non-positive limit", 2, Cli::in, "--max-processed-rows=0", "-e", "relix.relations"),
                new Case("an unknown profile", 2, Cli::in, "-P", "nope", "-e", "relix.relations"),
                new Case("a script that does not analyse", 3, Cli::in, "-e", "σ y > 0 (relix.relations)"),
                new Case("a script that does not parse", 3, Cli::in, "-e", "query { ( };"),
                new Case("a source that cannot be read", 4, Cli::in, "-e", SOURCE),
                new Case("a limit hit", 4, dir -> {
                    Files.writeString(dir.resolve("t.csv"), "x\n1\n2\n3\n");
                    return Cli.in(dir);
                }, "--max-processed-rows=1", "-e", SOURCE),
                new Case("a profiles.json others can read", 5, dir -> {
                    Path relix = Files.createDirectories(dir.resolve(".relix"));
                    Path file = Files.writeString(relix.resolve("profiles.json"), "{ \"p\": {} }");
                    assumeTrue(Files.getFileAttributeView(file,
                            java.nio.file.attribute.PosixFileAttributeView.class) != null, "POSIX permissions");
                    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));
                    return Cli.in(dir);
                }, "-P", "p", "-e", "relix.relations")
        ).map(Arguments::of);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("runs")
    @DisplayName("each class of outcome exits with its own code")
    void exitsWith(Case run) throws IOException {
        var result = run.setup().apply(dir).run(run.args());

        assertThat(result.status()).as(result.toString()).isEqualTo(run.status());
    }

    @Test
    @DisplayName("a query that could not reach its data is the environment's failure")
    void unreachableIsEnvironment() {
        assertThat(ExitCode.of(new QueryExecutionException("refused", new ConnectException("refused"))))
                .isEqualTo(ExitCode.ENVIRONMENT);
        assertThat(ExitCode.of(new QueryExecutionException("refused",
                new SQLException("refused", "08001")))).isEqualTo(ExitCode.ENVIRONMENT);
        assertThat(ExitCode.of(new QueryExecutionException("bad value", new ArithmeticException())))
                .isEqualTo(ExitCode.EXECUTION);
    }

    @Test
    @DisplayName("a query never runnable as written is an analysis failure")
    void neverRunnable() {
        assertThat(ExitCode.of(new RelixException("unknown relation"))).isEqualTo(ExitCode.ANALYSIS);
        assertThat(ExitCode.of(new UnboundedRelationException("endless")))
                .isEqualTo(ExitCode.ANALYSIS);
    }

    @Test
    @DisplayName("anything else that escapes is a defect: 70")
    void defect() {
        assertThat(Main.exitCode(new IllegalStateException("bug"))).isEqualTo(ExitCode.INTERNAL);
        assertThat(Main.exitCode(new CommandFailure(ExitCode.ENVIRONMENT, "x")))
                .isEqualTo(ExitCode.ENVIRONMENT);
    }
}
