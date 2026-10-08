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

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Which pager a long page goes through. */
@DisplayName("The pager")
class PagerTest {

    @Test
    @DisplayName("is $PAGER, run by the shell")
    void pager() {
        assertThat(Pager.command(Map.of("PAGER", "most -s"), false))
                .contains(List.of("sh", "-c", "most -s"));
        assertThat(Pager.command(Map.of("PAGER", "more"), true))
                .contains(List.of("cmd", "/c", "more"));
    }

    @Test
    @DisplayName("is less when $PAGER is not set, except on Windows")
    void defaults() {
        assertThat(Pager.command(Map.of(), false)).contains(List.of("sh", "-c", "less"));
        assertThat(Pager.command(Map.of(), true)).isEmpty();
    }

    @Test
    @DisplayName("is none when $PAGER is empty or cat")
    void none() {
        assertThat(Pager.command(Map.of("PAGER", ""), false)).isEqualTo(Optional.empty());
        assertThat(Pager.command(Map.of("PAGER", "cat"), false)).isEmpty();
    }
}
