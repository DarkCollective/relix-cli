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

import com.darkcollective.relix.cli.CommandFailure;
import com.darkcollective.relix.cli.ExitCode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The defaults a {@code .relix/relixrc} file sets (design §5.1): {@code key = value} lines,
 * with {@code #} starting a comment line.
 *
 * <pre>{@code
 * # acme's defaults
 * output  = csv
 * profile = staging
 * }</pre>
 *
 * <p>The keys are {@value #OUTPUT} and {@value #PROFILE}. The nearest file's value for a key
 * wins, and an option or environment variable on the command line beats them all. A key
 * the command does not know, or a line that is not {@code key = value}, is reported as a
 * warning and ignored, so a file written for a later version still works.
 */
public final class Relixrc {

    /** The file's name inside a {@code .relix/} directory. */
    public static final String FILE_NAME = "relixrc";

    /** The default output format, as {@code -o} names it. */
    public static final String OUTPUT = "output";

    /** The default profile, as {@code -P} names it. */
    public static final String PROFILE = "profile";

    private static final Set<String> KEYS = Set.of(OUTPUT, PROFILE);

    private static final Relixrc NONE = new Relixrc(Map.of());

    private final Map<String, String> values;

    private Relixrc(Map<String, String> values) {
        this.values = Map.copyOf(values);
    }

    /**
     * No defaults at all.
     *
     * @return the empty defaults
     */
    public static Relixrc none() {
        return NONE;
    }

    /**
     * The defaults of the given directories, the nearest file's value winning.
     *
     * @param directories the {@code .relix/} directories, outermost first
     * @param warn        where a line that is ignored is reported
     * @return the merged defaults
     * @throws CommandFailure with {@link ExitCode#ENVIRONMENT} when a file cannot be read
     */
    public static Relixrc read(List<Path> directories, Consumer<String> warn) {
        Map<String, String> values = new HashMap<>();
        for (Path dir : directories) {
            Path file = dir.resolve(FILE_NAME);
            if (Files.isRegularFile(file)) {
                values.putAll(parse(file, warn));
            }
        }
        return new Relixrc(values);
    }

    private static Map<String, String> parse(Path file, Consumer<String> warn) {
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new CommandFailure(ExitCode.ENVIRONMENT, file + ": cannot read: " + e.getMessage());
        }
        Map<String, String> values = new HashMap<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int equals = line.indexOf('=');
            String key = equals < 0 ? "" : line.substring(0, equals).strip();
            String value = equals < 0 ? "" : line.substring(equals + 1).strip();
            if (key.isEmpty() || value.isEmpty()) {
                warn.accept(file + ":" + (i + 1) + ": ignored: expected key = value");
            } else if (!KEYS.contains(key)) {
                warn.accept(file + ":" + (i + 1) + ": ignored: unknown key '" + key
                        + "' (known: " + OUTPUT + ", " + PROFILE + ")");
            } else {
                values.put(key, value);
            }
        }
        return values;
    }

    /**
     * A default, when a file sets it.
     *
     * @param key {@value #OUTPUT} or {@value #PROFILE}
     * @return its value
     */
    public Optional<String> get(String key) {
        return Optional.ofNullable(values.get(key));
    }
}
