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
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.ObjectReadContext;
import tools.jackson.core.json.JsonFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Named profiles of {@code ${VAR}} values, from the {@code profiles.json} files of the
 * run's {@code .relix/} directories (design §5.1, §5.5).
 *
 * <p>A file is one JSON object of profiles, each an object of string values:
 *
 * <pre>{@code
 * {
 *   "production": { "DB_URL": "jdbc:postgresql://db/app", "DB_USER": "app" },
 *   "staging":    { "DB_URL": "jdbc:postgresql://staging/app" }
 * }
 * }</pre>
 *
 * <p>A profile is merged across the files, outermost first, so a nearer file's value for a
 * variable replaces a farther one's. Since a profile may hold credentials, a file that its
 * group or others can read is refused, as {@code ssh} refuses a loose private key, with the
 * {@code chmod} that fixes it. Where the file system has no POSIX permissions, as on
 * Windows, that check is skipped.
 */
public final class Profiles {

    /** The file's name inside a {@code .relix/} directory. */
    public static final String FILE_NAME = "profiles.json";

    private static final JsonFactory JSON = new JsonFactory();

    private static final Set<PosixFilePermission> LOOSE = Set.of(
            PosixFilePermission.GROUP_READ, PosixFilePermission.OTHERS_READ);

    private Profiles() {
    }

    /**
     * The named profile's values, merged across {@code directories}.
     *
     * @param name        the profile to select
     * @param directories the {@code .relix/} directories, outermost first
     * @return the profile's variables
     * @throws CommandFailure with {@link ExitCode#ENVIRONMENT} when a file is readable by
     *                        group or others or is not a profiles file, and with
     *                        {@link ExitCode#USAGE} when no file defines the profile
     */
    public static Map<String, String> select(String name, List<Path> directories) {
        Map<String, String> merged = new LinkedHashMap<>();
        Set<String> known = new TreeSet<>();
        boolean found = false;
        List<Path> files = new ArrayList<>();
        for (Path dir : directories) {
            Path file = dir.resolve(FILE_NAME);
            if (Files.isRegularFile(file)) {
                files.add(file);
            }
        }
        for (Path file : files) {
            requirePrivate(file);
            Map<String, Map<String, String>> profiles = read(file);
            known.addAll(profiles.keySet());
            Map<String, String> profile = profiles.get(name);
            if (profile != null) {
                found = true;
                merged.putAll(profile);
            }
        }
        if (!found) {
            throw new CommandFailure(ExitCode.USAGE, "no profile '" + name + "'"
                    + (known.isEmpty()
                            ? " (no " + FILE_NAME + " defines any)"
                            : " (defined: " + String.join(", ", known) + ")"));
        }
        return Map.copyOf(merged);
    }

    /**
     * Refuses a file its group or others can read.
     *
     * @param file a profiles file
     * @throws CommandFailure with {@link ExitCode#ENVIRONMENT} when it is readable by more
     *                        than its owner
     */
    static void requirePrivate(Path file) {
        PosixFileAttributeView posix = Files.getFileAttributeView(file, PosixFileAttributeView.class);
        if (posix == null) {
            return;
        }
        Set<PosixFilePermission> permissions;
        try {
            permissions = posix.readAttributes().permissions();
        } catch (IOException e) {
            throw new CommandFailure(ExitCode.ENVIRONMENT, file + ": " + e.getMessage());
        }
        if (permissions.stream().anyMatch(LOOSE::contains)) {
            throw new CommandFailure(ExitCode.ENVIRONMENT, file
                    + " is readable by group or others, and a profile may hold credentials;"
                    + " make it private with: chmod 600 " + file);
        }
    }

    /**
     * The profiles one file defines.
     *
     * @param file a profiles file
     * @return each profile's variables, by profile name
     * @throws CommandFailure with {@link ExitCode#ENVIRONMENT} when it cannot be read or is
     *                        not an object of objects of strings
     */
    static Map<String, Map<String, String>> read(Path file) {
        try (JsonParser json = JSON.createParser(ObjectReadContext.empty(), Files.newBufferedReader(file))) {
            Map<String, Map<String, String>> profiles = new LinkedHashMap<>();
            expect(json.nextToken(), JsonToken.START_OBJECT, "an object of profiles");
            for (JsonToken t = json.nextToken(); t != JsonToken.END_OBJECT; t = json.nextToken()) {
                String profile = json.currentName();
                expect(json.nextToken(), JsonToken.START_OBJECT, "profile '" + profile + "' to be an object");
                Map<String, String> values = new LinkedHashMap<>();
                for (JsonToken v = json.nextToken(); v != JsonToken.END_OBJECT; v = json.nextToken()) {
                    String variable = json.currentName();
                    expect(json.nextToken(), JsonToken.VALUE_STRING,
                            "'" + variable + "' in profile '" + profile + "' to be a string");
                    values.put(variable, json.getString());
                }
                profiles.put(profile, values);
            }
            if (json.nextToken() != null) {
                throw new NotAProfilesFile("content after the object of profiles");
            }
            return profiles;
        } catch (NotAProfilesFile e) {
            throw new CommandFailure(ExitCode.ENVIRONMENT, file + ": expected " + e.getMessage());
        } catch (JacksonException e) {
            throw new CommandFailure(ExitCode.ENVIRONMENT, file + ": " + e.getOriginalMessage());
        } catch (IOException e) {
            throw new CommandFailure(ExitCode.ENVIRONMENT, file + ": " + e.getMessage());
        }
    }

    private static void expect(JsonToken actual, JsonToken expected, String what) {
        if (actual != expected) {
            throw new NotAProfilesFile(what);
        }
    }

    private static final class NotAProfilesFile extends RuntimeException {
        private static final long serialVersionUID = 1L;

        NotAProfilesFile(String message) {
            super(message, null, false, false);
        }
    }
}
