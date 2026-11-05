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
package com.darkcollective.relix.cli.io;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** How the process decides that it is on a terminal. */
@DisplayName("The host's terminal test")
class HostTest {

    @Test
    @DisplayName("is false without a console, whatever isTerminal would say")
    void noConsole() {
        assertThat(Host.terminal(false, true)).isFalse();
        assertThat(Host.terminal(false, null)).isFalse();
    }

    @Test
    @DisplayName("is the console's own answer where the JVM has Console.isTerminal (22+), "
            + "so a pipe is not a terminal although it has a console")
    void verdict() {
        assertThat(Host.terminal(true, false)).isFalse();
        assertThat(Host.terminal(true, true)).isTrue();
    }

    @Test
    @DisplayName("is the console's presence on a JVM without isTerminal (21)")
    void noVerdict() {
        assertThat(Host.terminal(true, null)).isTrue();
    }
}
