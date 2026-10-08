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

import com.darkcollective.relix.cli.io.Host;
import com.darkcollective.relix.cli.io.Interruption;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Runs the whole command through {@link Main} over captured streams, in a working
 * directory and home of the test's choosing, with an environment of its own.
 */
public final class Cli {

    private final Path workingDirectory;
    private Path home;
    private InputStream stdin;
    private boolean stdoutIsTerminal = true;
    private OutputStream stdout;
    private Interruption interruption = new Interruption();
    private final Map<String, String> environment = new HashMap<>();

    private Cli(Path workingDirectory) {
        this.workingDirectory = workingDirectory;
        this.home = workingDirectory;
    }

    /**
     * A command started in {@code workingDirectory}, which is also its home, with a
     * terminal on standard input and standard output and an empty environment.
     *
     * @param workingDirectory where it starts
     * @return the command, to configure and run
     */
    public static Cli in(Path workingDirectory) {
        return new Cli(workingDirectory);
    }

    /**
     * Sets the home directory.
     *
     * @param home the user's home
     * @return this
     */
    public Cli home(Path home) {
        this.home = home;
        return this;
    }

    /**
     * Pipes {@code text} to standard input, which is then not a terminal.
     *
     * @param text what standard input holds
     * @return this
     */
    public Cli stdin(String text) {
        return stdin(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * Connects standard input to {@code in}, which is then not a terminal.
     *
     * @param in what standard input reads
     * @return this
     */
    public Cli stdin(InputStream in) {
        this.stdin = in;
        return this;
    }

    /**
     * Makes standard output a pipe rather than a terminal.
     *
     * @return this
     */
    public Cli piped() {
        this.stdoutIsTerminal = false;
        return this;
    }

    /**
     * Sends standard output to {@code out}, a pipe, as well as capturing it.
     *
     * @param out the reader at the other end; a write it refuses is a closed pipe
     * @return this
     */
    public Cli stdout(OutputStream out) {
        this.stdout = out;
        return piped();
    }

    /**
     * Runs with {@code interruption} standing for the shutdown hook, so a test can
     * interrupt the run as {@code SIGINT} would.
     *
     * @param interruption what an interrupt stops
     * @return this
     */
    public Cli interruption(Interruption interruption) {
        this.interruption = interruption;
        return this;
    }

    /**
     * Sets an environment variable.
     *
     * @param name  its name
     * @param value its value
     * @return this
     */
    public Cli env(String name, String value) {
        environment.put(name, value);
        return this;
    }

    /**
     * Runs the command.
     *
     * @param args the command line
     * @return its exit status and what it wrote
     */
    public Result run(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        InputStream in = stdin == null ? new ByteArrayInputStream(new byte[0]) : stdin;
        OutputStream target = stdout == null ? out : new Tee(stdout, out);
        Host host = new Host(in, target,
                new PrintStream(err, true, StandardCharsets.UTF_8),
                environment, workingDirectory, home, stdin == null, stdoutIsTerminal);
        int status = Main.run(host, interruption, args);
        return new Result(status, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    /** Writes to the reader first, and captures only what it accepted. */
    private static final class Tee extends OutputStream {
        private final OutputStream reader;
        private final OutputStream capture;

        Tee(OutputStream reader, OutputStream capture) {
            this.reader = reader;
            this.capture = capture;
        }

        @Override
        public void write(int b) throws java.io.IOException {
            reader.write(b);
            capture.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws java.io.IOException {
            reader.write(b, off, len);
            capture.write(b, off, len);
        }

        @Override
        public void flush() throws java.io.IOException {
            reader.flush();
        }
    }

    /**
     * What a run did.
     *
     * @param status its exit status
     * @param out    what it wrote to standard output
     * @param err    what it wrote to standard error
     */
    public record Result(int status, String out, String err) {

        @Override
        public String toString() {
            return "exit " + status + "\n── stdout ──\n" + out + "── stderr ──\n" + err;
        }
    }
}
