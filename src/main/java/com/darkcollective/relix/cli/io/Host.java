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

import java.io.Console;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * What the command reads from the process it runs in: its streams, its environment, where
 * it was started and whose home that is, and whether standard input and standard output
 * are terminals.
 *
 * <p>Everything outside the arguments arrives through here rather than from
 * {@link System}, so that a test runs the whole command over streams and an environment
 * of its own.
 *
 * @param in               standard input
 * @param out              standard output: data only. A raw stream, unbuffered and not a
 *                         {@link PrintStream}, so that a write to a closed pipe fails
 *                         rather than being swallowed
 * @param err              standard error: everything else
 * @param environment      the process environment
 * @param workingDirectory where the command was started, absolute
 * @param home             the user's home directory, absolute
 * @param stdinIsTerminal  whether standard input is a terminal rather than a pipe or file
 * @param stdoutIsTerminal whether standard output is a terminal rather than a pipe or file
 */
public record Host(InputStream in, OutputStream out, PrintStream err,
                   Map<String, String> environment, Path workingDirectory, Path home,
                   boolean stdinIsTerminal, boolean stdoutIsTerminal) {

    /**
     * A host, checked.
     */
    public Host {
        Objects.requireNonNull(in, "in");
        Objects.requireNonNull(out, "out");
        Objects.requireNonNull(err, "err");
        environment = Map.copyOf(environment);
        workingDirectory = workingDirectory.toAbsolutePath().normalize();
        home = home.toAbsolutePath().normalize();
    }

    /**
     * The process this JVM is.
     *
     * @return the real streams, environment and directories
     */
    public static Host system() {
        // Java 21: System.console() is null when standard input or output is redirected, and
        // it cannot tell which, so both are taken for pipes then and `echo … | relix` writes
        // tsv to a terminal; pass -o to choose. Java 22 returns a console for a pipe too and
        // adds Console.isTerminal(), which is asked for when it exists. The command is built
        // on 21, so the method is reached reflectively.
        boolean terminal = terminal(System.console() != null, isTerminal(System.console()));
        return new Host(System.in, new FileOutputStream(FileDescriptor.out), System.err,
                System.getenv(),
                Path.of(System.getProperty("user.dir")), Path.of(System.getProperty("user.home")),
                terminal, terminal);
    }

    /**
     * Whether the process is on a terminal.
     *
     * @param console  the JVM's console, or null when it has none
     * @param verdict  what {@code Console.isTerminal()} says of it, or null on a JVM that
     *                 has no such method (before 22), where a console is all there is to go on
     * @return false without a console; otherwise the verdict, else true
     */
    static boolean terminal(boolean console, Boolean verdict) {
        return console && (verdict == null || verdict);
    }

    /** {@code Console.isTerminal()} of the JVM's console, or null before Java 22. */
    private static Boolean isTerminal(Console console) {
        if (console == null) {
            return null;
        }
        try {
            return (Boolean) Console.class.getMethod("isTerminal").invoke(console);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    /**
     * An environment variable, when it is set and not empty.
     *
     * @param name the variable's name
     * @return its value
     */
    public Optional<String> variable(String name) {
        String value = environment.get(name);
        return value == null || value.isEmpty() ? Optional.empty() : Optional.of(value);
    }
}
