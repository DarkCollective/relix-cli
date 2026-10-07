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
package com.darkcollective.relix.cli.render;

import tools.jackson.core.io.JsonStringEncoder;

/**
 * A string as a JSON string literal, for the renderers here that lay their JSON out by
 * hand so a human can read it as well as a program.
 */
public final class JsonText {

    private JsonText() {
    }

    /** {@code value} quoted and escaped as a JSON string. */
    public static String quote(String value) {
        StringBuilder out = new StringBuilder(value.length() + 2).append('"');
        JsonStringEncoder.getInstance().quoteAsString(value, out);
        return out.append('"').toString();
    }
}
