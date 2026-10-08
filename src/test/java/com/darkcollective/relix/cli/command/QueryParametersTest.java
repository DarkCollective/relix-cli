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
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Query parameters bound with {@code -a NAME=VALUE} (design §9, R3), end to end through
 * {@code Main}.
 */
@DisplayName("Query parameters bound with -a")
class QueryParametersTest {

    private static final String CUSTOMERS = """
            Customers := [
            | name  | city   |
            |-------|--------|
            | Ada   | London |
            | Grace | Paris  |
            ];
            """;

    private static final String ORDERS = """
            Orders := [
            | id | who   |
            |----|-------|
            | 7  | Ada   |
            | 42 | Grace |
            ];
            """;

    private static final String BY_CITY = CUSTOMERS + "query { π name (σ city = $city (Customers)) };\n";

    @TempDir
    Path dir;

    @BeforeEach
    void script() throws IOException {
        Files.writeString(dir.resolve("by-city.relix"), BY_CITY);
    }

    @Test
    @DisplayName("-a binds the parameter the script names")
    void binds() {
        var result = Cli.in(dir).run("-a", "city=Paris", "by-city.relix");

        assertThat(result.status()).as(result.toString()).isZero();
        assertThat(result.out()).contains("Grace").doesNotContain("Ada").contains("(1 row)");
    }

    @Test
    @DisplayName("--arg is the long form, and it may follow the script")
    void longFormAfterScript() {
        var result = Cli.in(dir).run("by-city.relix", "--arg", "city=London");

        assertThat(result.status()).as(result.toString()).isZero();
        assertThat(result.out()).contains("Ada").doesNotContain("Grace");
    }

    @Test
    @DisplayName("each -a binds one parameter")
    void several() {
        var result = Cli.in(dir).run("-a", "city=London", "-a", "who=Ada",
                "-e", CUSTOMERS, "-e", "σ city = $city ∧ name = $who (Customers)");

        assertThat(result.status()).as(result.toString()).isZero();
        assertThat(result.out()).contains("Ada").contains("(1 row)");
    }

    @Test
    @DisplayName("a value takes the type of what its parameter is compared with")
    void typedFromText() {
        var result = Cli.in(dir).run("-a", "id=42", "-e", ORDERS, "-e", "σ id = $id (Orders)");

        assertThat(result.status()).as(result.toString()).isZero();
        assertThat(result.out()).contains("Grace").contains("(1 row)");
    }

    @Test
    @DisplayName("a value that is not of its parameter's type is an error that names both")
    void wrongType() {
        var result = Cli.in(dir).run("-a", "id=abc", "-e", ORDERS, "-e", "σ id = $id (Orders)");

        assertThat(result.status()).as(result.toString()).isEqualTo(3);
        assertThat(result.err()).contains("$id").contains("NUMBER").contains("'abc'");
        assertThat(result.out()).isEmpty();
    }

    @Test
    @DisplayName("a value holding a quote is a value: it cannot change the query")
    void quoteCannotInject() {
        var result = Cli.in(dir).run("-a", "city=Paris' ∨ '1' = '1", "by-city.relix");

        assertThat(result.status()).as(result.toString()).isZero();
        assertThat(result.out()).doesNotContain("Ada").doesNotContain("Grace").contains("(0 rows)");
    }

    @Test
    @DisplayName("an unbound parameter is an analysis error that names it")
    void unbound() {
        var result = Cli.in(dir).run("by-city.relix");

        assertThat(result.status()).as(result.toString()).isEqualTo(3);
        assertThat(result.err()).isEqualTo(
                "relix: by-city.relix: parameter $city is not bound; give it a value with -a city=VALUE\n");
        assertThat(result.out()).isEmpty();
    }

    @Test
    @DisplayName("fmt, bundle and optimize print $name, never the value")
    void reportsPrintTheName() {
        for (String command : new String[] {"fmt", "bundle", "optimize"}) {
            var result = Cli.in(dir).run(command, "-a", "city=Lisbon", "by-city.relix");

            assertThat(result.status()).as(command + ": " + result).isZero();
            assertThat(result.out()).as(command).contains("$city").doesNotContain("Lisbon");
        }
    }
}
