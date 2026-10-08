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

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The example scripts under {@code src/test/resources/examples}: real scripts, with
 * comments, inline tables, views and several queries each, that need nothing but
 * themselves to run.
 */
public final class Examples {

    /** Every example, by file name. */
    public static final List<String> NAMES =
            List.of("introspection.relix", "league.relix", "library.relix", "pokemon.relix");

    private Examples() {
    }

    /**
     * One example's text.
     *
     * @param name its file name
     * @return the script
     */
    public static String text(String name) {
        try (InputStream in = Examples.class.getResourceAsStream("/examples/" + name)) {
            if (in == null) {
                throw new IllegalArgumentException("no example " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Copies every example into a directory.
     *
     * @param directory where to put them
     * @return their paths, in {@link #NAMES}' order
     */
    public static List<Path> copyTo(Path directory) {
        List<Path> copies = new ArrayList<>();
        for (String name : NAMES) {
            try {
                copies.add(Files.writeString(directory.resolve(name), text(name), StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return copies;
    }
}
