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
package com.darkcollective.relix.cli.catalog;

import com.darkcollective.relix.cli.CommandFailure;
import com.darkcollective.relix.cli.ExitCode;
import com.darkcollective.relix.cli.config.RelixDirectories;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Which project {@code .relix/} directories the user has trusted (design §5.4), as
 * {@code direnv} trusts an {@code .envrc}.
 *
 * <p>A project catalog can declare a connection or an HTTP source that sends a
 * {@code ${SECRET}} to a host of its author's choosing, so loading one because of the
 * directory you are standing in is the attack {@code direnv} answers. Here too, a project
 * {@code .relix/} is loaded only once {@code relix catalog trust} has recorded a hash of
 * its files in {@code ~/.relix/trusted}. The hash covers every file under the directory,
 * its catalog, {@code relixrc} and {@code profiles.json} alike, so changing, adding or
 * removing any of them means trusting it again.
 *
 * <p>{@code ~/.relix} is the user's own and always trusted, as is everything named on the
 * command line. {@code RELIX_TRUST_ALL=1} trusts every directory, for a CI container whose
 * checkout is the only thing in it.
 *
 * <p>The record is one line per directory, its hash and then its path, as
 * {@code sha256sum} writes them.
 */
public final class Trust {

    /** The record's name inside {@code ~/.relix}. */
    public static final String FILE_NAME = "trusted";

    /** The variable that trusts everything. */
    public static final String TRUST_ALL_VARIABLE = "RELIX_TRUST_ALL";

    private final Path user;
    private final Path record;
    private final boolean all;
    private final Map<Path, String> trusted;

    private Trust(Path user, boolean all, Map<Path, String> trusted) {
        this.user = user;
        this.record = user.resolve(FILE_NAME);
        this.all = all;
        this.trusted = trusted;
    }

    /**
     * The user's record of trust.
     *
     * @param home the user's home directory, absolute
     * @param all  whether {@code RELIX_TRUST_ALL} trusts everything
     * @return what the user trusts
     * @throws CommandFailure with {@link ExitCode#ENVIRONMENT} when the record cannot be read
     */
    public static Trust load(Path home, boolean all) {
        Path user = home.resolve(RelixDirectories.NAME);
        Path record = user.resolve(FILE_NAME);
        Map<Path, String> trusted = new TreeMap<>();
        if (Files.isRegularFile(record)) {
            try {
                for (String line : Files.readAllLines(record, StandardCharsets.UTF_8)) {
                    int space = line.indexOf("  ");
                    if (space > 0) {
                        trusted.put(Path.of(line.substring(space + 2)), line.substring(0, space));
                    }
                }
            } catch (IOException e) {
                throw new CommandFailure(ExitCode.ENVIRONMENT, record + ": cannot read: " + e.getMessage());
            }
        }
        return new Trust(user, all, trusted);
    }

    /**
     * Whether a {@code .relix/} directory may be loaded: it is the user's own, everything
     * is trusted, or its files are as they were when it was trusted.
     *
     * @param level a {@code .relix/} directory, absolute
     * @return {@code true} when it may be loaded
     */
    public boolean trusts(Path level) {
        return all || level.equals(user) || state(level) == State.TRUSTED;
    }

    /** What the record says of a directory. */
    public enum State {
        /** Trusted, and unchanged since. */
        TRUSTED,
        /** Trusted, but a file has changed, been added or been removed since. */
        CHANGED,
        /** Trusted, but no longer there. */
        GONE,
        /** Never trusted. */
        UNTRUSTED;

        /**
         * The state as {@code relix catalog trust --list} prints it.
         *
         * @return its lower-case name
         */
        public String label() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /**
     * What the record says of a {@code .relix/} directory now.
     *
     * @param level a {@code .relix/} directory, absolute
     * @return its state
     */
    public State state(Path level) {
        String recorded = trusted.get(level);
        if (recorded == null) {
            return State.UNTRUSTED;
        }
        if (!Files.isDirectory(level)) {
            return State.GONE;
        }
        return recorded.equals(hash(level)) ? State.TRUSTED : State.CHANGED;
    }

    /**
     * The directories the record holds, in order.
     *
     * @return the trusted {@code .relix/} directories
     */
    public List<Path> directories() {
        return List.copyOf(trusted.keySet());
    }

    /**
     * Records a directory as trusted, as its files are now.
     *
     * @param level a {@code .relix/} directory, absolute
     * @throws CommandFailure with {@link ExitCode#ENVIRONMENT} when the record cannot be written
     */
    public void trust(Path level) {
        trusted.put(level, hash(level));
        save();
    }

    /**
     * Removes a directory from the record.
     *
     * @param level a {@code .relix/} directory, absolute
     * @return whether it was there
     * @throws CommandFailure with {@link ExitCode#ENVIRONMENT} when the record cannot be written
     */
    public boolean revoke(Path level) {
        if (trusted.remove(level) == null) {
            return false;
        }
        save();
        return true;
    }

    private void save() {
        StringBuilder text = new StringBuilder();
        trusted.forEach((dir, hash) -> text.append(hash).append("  ").append(dir).append('\n'));
        try {
            Files.createDirectories(user);
            Path temporary = Files.createTempFile(user, FILE_NAME, ".tmp");
            Files.writeString(temporary, text, StandardCharsets.UTF_8);
            Files.move(temporary, record, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new CommandFailure(ExitCode.ENVIRONMENT, record + ": cannot write: " + e.getMessage());
        }
    }

    /**
     * A hash of every file under a directory: each one's path relative to it and its
     * bytes, in path order.
     *
     * @param level a directory
     * @return the hash, in hexadecimal
     * @throws CommandFailure with {@link ExitCode#ENVIRONMENT} when a file cannot be read
     */
    static String hash(Path level) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every Java has SHA-256", e);
        }
        List<Path> files;
        try (Stream<Path> walk = Files.walk(level)) {
            files = new ArrayList<>(walk.filter(Files::isRegularFile).toList());
        } catch (IOException e) {
            throw new CommandFailure(ExitCode.ENVIRONMENT, level + ": cannot read: " + e.getMessage());
        }
        // The relative path with / separators, so that the order and the hash are the
        // same on every platform.
        files.sort((a, b) -> relative(level, a).compareTo(relative(level, b)));
        for (Path file : files) {
            digest.update(relative(level, file).getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            try {
                digest.update(Files.readAllBytes(file));
            } catch (IOException e) {
                throw new CommandFailure(ExitCode.ENVIRONMENT, file + ": cannot read: " + e.getMessage());
            }
            digest.update((byte) 0);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String relative(Path level, Path file) {
        return level.relativize(file).toString().replace('\\', '/');
    }
}
