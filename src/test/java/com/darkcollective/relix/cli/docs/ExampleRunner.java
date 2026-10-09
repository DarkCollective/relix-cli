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
package com.darkcollective.relix.cli.docs;

import com.darkcollective.relix.cli.Main;
import com.darkcollective.relix.cli.io.Host;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Runs the guide's examples where a reader would: in {@code ~/shop}, a copy of the fixture
 * tree under {@code src/test/resources/docs-fixture/home}, which is the home directory.
 *
 * <p>The fixture is the shop the pages describe: a user-level {@code ~/.relix} with a
 * table and a profile, the project {@code ~/shop} with its catalog, data files, scripts and
 * profiles, and {@code ~/shop/reports}, a sub-project that overlays {@code Orders}. Setting
 * it up does what a fresh copy cannot carry: {@code @HOME@} in a profile becomes the home
 * directory's path, every {@code profiles.json} is made private, and the H2 database the
 * {@code warehouse} connection opens is written, with user {@code shop} and the password
 * {@code ~/.relix/profiles.json} holds.
 *
 * <p>An example that is a plain {@code relix} command ({@link ShellCommand}) runs in this
 * JVM, with standard output a terminal and standard input a terminal unless the example
 * redirects it, as a reader typing it would have them. Anything else runs through
 * {@code bash}, with a {@code relix} on its {@code PATH} that starts the command in a JVM
 * of its own; there standard output is a pipe, as it is for a reader's pipeline. Both get
 * the same environment: the fixture's {@code HOME}, {@code NO_COLOR=1}, {@code TZ=UTC},
 * and nothing of the user running the build.
 */
final class ExampleRunner implements AutoCloseable {

    private static final String FIXTURE = "/docs-fixture/home";

    /** The H2 database's credentials, as the fixture's profiles give them. */
    private static final String WAREHOUSE_USER = "shop";
    private static final String WAREHOUSE_PASSWORD = "s3cret";

    private final Path home;
    private final Path shop;
    private final Path bin;
    private final Map<String, String> environment;
    private final Path stdin;
    private final Path stdout;

    /**
     * A fresh copy of the fixture.
     *
     * @param root    an empty directory to put it in
     * @param trusted whether {@code ~/shop} and {@code ~/shop/reports} start trusted
     */
    ExampleRunner(Path root, boolean trusted) {
        try {
            this.home = Files.createDirectories(root.resolve("home")).toRealPath();
            copy(fixture(), home);
            this.shop = home.resolve("shop");
            for (Path profiles : List.of(home.resolve(".relix/profiles.json"), shop.resolve(".relix/profiles.json"))) {
                Files.writeString(profiles, Files.readString(profiles).replace("@HOME@", home.toString()));
                Files.setPosixFilePermissions(profiles, PosixFilePermissions.fromString("rw-------"));
            }
            warehouse(shop.resolve("data/warehouse"));
            this.bin = Files.createDirectories(root.resolve("bin"));
            this.environment = environment(home, bin);
            this.stdin = root.resolve("stdin.txt");
            this.stdout = root.resolve("stdout.txt");
            shim(bin.resolve("relix"), home);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (trusted) {
            for (Path project : List.of(shop, shop.resolve("reports"))) {
                Output trust = runInProcess(new ShellCommand(Map.of(), List.of("catalog", "trust", project.toString()),
                        null, null, false, false));
                if (trust.status() != 0) {
                    throw new IllegalStateException("cannot trust " + project + ": " + trust);
                }
            }
        }
    }

    /** {@return the fixture's home directory} */
    Path home() {
        return home;
    }

    /**
     * What an example did.
     *
     * @param status its exit status
     * @param out    what it wrote to standard output, with the home directory written {@code ~}
     * @param err    what it wrote to standard error
     * @param shell  whether it ran through {@code bash}
     */
    record Output(int status, String out, String err, boolean shell) {
        @Override
        public String toString() {
            return (shell ? "bash" : "in-process") + ", exit " + status + "\n── stdout ──\n" + out
                    + "── stderr ──\n" + err;
        }
    }

    /**
     * Runs an example.
     *
     * @param command the example's text
     * @return what it did
     */
    Output run(String command) {
        return ShellCommand.parse(command).map(this::runInProcess).orElseGet(() -> runInBash(command));
    }

    private Output runInProcess(ShellCommand command) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        OutputStream errTarget = command.mergeStderr() ? out : err;
        InputStream in;
        try {
            in = command.heredoc() != null
                    ? new ByteArrayInputStream(command.heredoc().getBytes(StandardCharsets.UTF_8))
                    : command.stdinFile() != null ? Files.newInputStream(shop.resolve(command.stdinFile()))
                    : new ByteArrayInputStream(new byte[0]);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Map<String, String> env = new HashMap<>(environment);
        env.putAll(command.environment());
        boolean stdinIsTerminal = command.heredoc() == null && command.stdinFile() == null;
        Host host = new Host(in, out, new PrintStream(errTarget, true, StandardCharsets.UTF_8), env, shop, home,
                stdinIsTerminal, true);
        // The engine finds its driver directory and file cache under the JVM's user.home,
        // not $HOME, so the fixture is made that too for the run, as the bash shim does.
        String userHome = System.getProperty("user.home");
        int status;
        try {
            System.setProperty("user.home", home.toString());
            status = Main.run(host, command.arguments().toArray(String[]::new));
        } finally {
            System.setProperty("user.home", userHome);
        }
        String stdout = out.toString(StandardCharsets.UTF_8);
        if (command.echoStatus()) {
            stdout += "exit " + status + "\n";
            status = 0;
        }
        return new Output(status, tilde(stdout), err.toString(StandardCharsets.UTF_8), false);
    }

    private Output runInBash(String command) {
        // Descriptors 8 and 9 are the example's own standard input and output, which the
        // shim compares a stage's streams with to tell where a reader's terminal would be.
        ProcessBuilder builder = new ProcessBuilder("bash", "-c", "exec 8<&0 9>&1\n" + command)
                .directory(shop.toFile());
        builder.environment().clear();
        builder.environment().putAll(environment);
        try {
            Files.write(stdin, new byte[0]);
            Files.write(stdout, new byte[0]);
            Path err = Files.createTempFile(bin.getParent(), "stderr", ".txt");
            Process process = builder.redirectInput(stdin.toFile()).redirectOutput(stdout.toFile())
                    .redirectError(err.toFile()).start();
            if (!process.waitFor(2, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                throw new IllegalStateException("timed out: " + command);
            }
            return new Output(process.exitValue(), tilde(Files.readString(stdout)), Files.readString(err), true);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** The home directory's path, which differs on every run, written as a reader's shell abbreviates it. */
    private String tilde(String text) {
        return text.replace(home.toString(), "~");
    }

    @Override
    public void close() {
        // Nothing stays open: each example's JVM or session ends with it.
    }

    private static Map<String, String> environment(Path home, Path bin) {
        Map<String, String> env = new HashMap<>();
        // The relix under test first; then where jq, find and xargs are on a build machine.
        env.put("PATH", bin + ":/usr/local/bin:/opt/homebrew/bin:/usr/bin:/bin");
        env.put("HOME", home.toString());
        env.put("NO_COLOR", "1");
        env.put("TZ", "UTC");
        // A UTF-8 locale, as a reader's terminal has: on Linux the JVM decodes its
        // arguments by the locale, and `relix doc σ` would otherwise not say σ.
        env.put("LC_ALL", System.getProperty("os.name").startsWith("Mac") ? "en_US.UTF-8" : "C.UTF-8");
        env.put("PAGER", "cat");
        return env;
    }

    /**
     * A {@code relix} for {@code bash}: this JVM's class path and {@link TerminalMain}, the
     * fixture as the home directory a JDBC driver's {@code ~} means too, and standard input
     * and output taken for terminals when they are the example's own — descriptors 8 and
     * 9 — as they would be a reader's.
     */
    private static void shim(Path file, Path home) throws IOException {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String script = "#!/bin/sh\n"
                + "in=false; [ /dev/fd/0 -ef /dev/fd/8 ] && in=true\n"
                + "out=false; [ /dev/fd/1 -ef /dev/fd/9 ] && out=true\n"
                + "exec " + quote(java) + " -XX:TieredStopAtLevel=1 -Xshare:auto"
                // The launcher's streams are UTF-8 whatever the locale, so a glyph in a
                // message reads as it does on a reader's terminal.
                + " -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8"
                + " -Duser.home=" + quote(home.toString())
                + " -Drelix.docs.stdinIsTerminal=$in -Drelix.docs.stdoutIsTerminal=$out"
                + " -cp " + quote(System.getProperty("java.class.path"))
                + " " + TerminalMain.class.getName() + " \"$@\"\n";
        Files.writeString(file, script);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
    }

    private static String quote(String word) {
        return "'" + word.replace("'", "'\\''") + "'";
    }

    private static Path fixture() {
        URL url = ExampleRunner.class.getResource(FIXTURE);
        if (url == null) {
            throw new IllegalStateException("no " + FIXTURE + " on the test class path");
        }
        try {
            return Path.of(url.toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void copy(Path from, Path to) throws IOException {
        try (Stream<Path> walk = Files.walk(from)) {
            for (Path source : walk.toList()) {
                Path target = to.resolve(from.relativize(source).toString());
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /** The stock database the fixture's {@code warehouse} connection opens. */
    private static void warehouse(Path file) {
        try (Connection connection = DriverManager.getConnection("jdbc:h2:" + file, WAREHOUSE_USER, WAREHOUSE_PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE STOCK (SKU VARCHAR(16) PRIMARY KEY, ON_HAND INT)");
            statement.execute("INSERT INTO STOCK VALUES ('mug', 12), ('tee', 0), ('tote', 31)");
        } catch (SQLException e) {
            throw new IllegalStateException("cannot write the warehouse database: " + e.getMessage(), e);
        }
    }
}
