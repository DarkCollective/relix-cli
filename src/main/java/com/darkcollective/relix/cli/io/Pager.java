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

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Shows a long text a screen at a time, through {@code $PAGER}, when standard output is a
 * terminal, as {@code git} and {@code man} do.
 *
 * <p>The pager is {@code $PAGER}, else {@code less}, run by the shell; {@code less} is given
 * {@code LESS=FRX} unless {@code $LESS} is set, so a page that fits the screen is printed
 * and left there. On Windows there is a pager only when {@code $PAGER} names one. An empty
 * {@code $PAGER} or {@code cat} means none, and so does a pager that will not start: the
 * text is then written to standard output as it is.
 */
public final class Pager {

    /** The variable naming the pager. */
    public static final String PAGER_VARIABLE = "PAGER";

    private Pager() {
    }

    /**
     * Shows a text, through the pager when there is one.
     *
     * @param host the process
     * @param text the text
     * @param sink standard output, for when there is no pager
     */
    public static void show(Host host, String text, RowSink sink) {
        Optional<List<String>> command = paged(host) ? command(host.environment(), isWindows()) : Optional.empty();
        if (command.isEmpty() || !run(command.get(), host.environment(), text)) {
            sink.text(text);
        }
    }

    /**
     * Whether a host's standard output can be paged: it is a terminal, and it is this
     * process's own, which is where a pager writes.
     */
    private static boolean paged(Host host) {
        return host.stdoutIsTerminal() && host.out() instanceof FileOutputStream;
    }

    /**
     * The pager to run, as a command line, if any.
     *
     * @param environment the process environment
     * @param windows     whether this is Windows
     * @return the command, or empty for none
     */
    public static Optional<List<String>> command(Map<String, String> environment, boolean windows) {
        String pager = environment.get(PAGER_VARIABLE);
        if (pager == null) {
            return windows ? Optional.empty() : Optional.of(List.of("sh", "-c", "less"));
        }
        if (pager.isBlank() || pager.strip().equals("cat")) {
            return Optional.empty();
        }
        return Optional.of(windows ? List.of("cmd", "/c", pager) : List.of("sh", "-c", pager));
    }

    private static boolean run(List<String> command, Map<String, String> environment, String text) {
        ProcessBuilder builder = new ProcessBuilder(command)
                .redirectOutput(ProcessBuilder.Redirect.INHERIT)
                .redirectError(ProcessBuilder.Redirect.INHERIT);
        if (!environment.containsKey("LESS")) {
            builder.environment().put("LESS", "FRX");
        }
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            return false;
        }
        try (OutputStream in = process.getOutputStream()) {
            in.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            // The reader quit before the end, as a pager may.
        }
        try {
            process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return true;
    }

    private static boolean isWindows() {
        return File.separatorChar == '\\';
    }
}
