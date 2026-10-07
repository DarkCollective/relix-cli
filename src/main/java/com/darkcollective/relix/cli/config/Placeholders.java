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

import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Where a {@code ${VAR}} placeholder's value comes from (design §5.5): {@code -D}, then the
 * selected profile, then the process environment.
 *
 * <p>The session asks when a query runs rather than the text being rewritten first, so
 * commands that print a script print the placeholder, never the value.
 */
public final class Placeholders {

    private Placeholders() {
    }

    /**
     * The resolver a session is given.
     *
     * @param defines     the {@code -D NAME=VALUE} values
     * @param profile     the selected profile's values; empty when none is selected
     * @param environment the process environment
     * @return a resolver taking each variable from the first of the three that has it
     */
    public static Function<String, Optional<String>> resolver(Map<String, String> defines,
                                                             Map<String, String> profile,
                                                             Map<String, String> environment) {
        Map<String, String> d = Map.copyOf(defines);
        Map<String, String> p = Map.copyOf(profile);
        Map<String, String> e = Map.copyOf(environment);
        return name -> Optional.ofNullable(d.get(name))
                .or(() -> Optional.ofNullable(p.get(name)))
                .or(() -> Optional.ofNullable(e.get(name)));
    }
}
