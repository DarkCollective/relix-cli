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

import com.darkcollective.relix.cli.command.RelixCommand;
import com.darkcollective.relix.cli.io.Host;
import com.darkcollective.relix.cli.io.Interruption;
import com.darkcollective.relix.embed.Relix;
import com.darkcollective.relix.embed.RelixException;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.lang.module.ModuleDescriptor;
import java.nio.charset.StandardCharsets;

/**
 * The {@code relix} entry point, and the one place an outcome becomes an exit status
 * (design §3.4).
 */
public final class Main {

    /** {@code run}'s option whose file is attached with {@code =} only. */
    private static final String TRACE = "--trace";

    private Main() {
    }

    /**
     * Runs the command and exits with its status.
     *
     * <p>An interrupt ({@code SIGINT}) runs the shutdown hook, which stops the query in
     * flight and closes its session; the JVM then exits 130, the shell's value for it.
     *
     * @param args the command line
     */
    public static void main(String[] args) {
        Interruption interruption = new Interruption();
        Runtime.getRuntime().addShutdownHook(new Thread(interruption::interrupt, "relix-interrupt"));
        System.exit(run(Host.system(), interruption, args));
    }

    /**
     * Runs the command over a host and returns its exit status, without exiting.
     *
     * @param host the process to run in
     * @param args the command line
     * @return the exit status
     */
    public static int run(Host host, String... args) {
        return run(host, new Interruption(), args);
    }

    /**
     * Runs the command over a host and returns its exit status, without exiting.
     *
     * @param host         the process to run in
     * @param interruption what an interrupt stops; {@link Interruption#interrupt()} ends
     *                     the run with 130
     * @param args         the command line
     * @return the exit status
     */
    public static int run(Host host, Interruption interruption, String... args) {
        PrintWriter out = new PrintWriter(host.out(), true, StandardCharsets.UTF_8);
        PrintWriter err = new PrintWriter(host.err(), true, StandardCharsets.UTF_8);
        CommandLine command = new CommandLine(new RelixCommand(host, interruption))
                .setOut(out)
                .setErr(err)
                .setPosixClusteredShortOptionsAllowed(true)
                .setParameterExceptionHandler((e, ignored) -> {
                    err.print("relix: " + e.getMessage() + "\n");
                    err.print("Try 'relix --help' for more information.\n");
                    err.flush();
                    return ExitCode.USAGE.status();
                })
                .setExecutionExceptionHandler((e, failed, parsed) -> {
                    ExitCode code = exitCode(e);
                    if (e instanceof CommandFailure failure && failure.getMessage() != null) {
                        err.print("relix: " + failure.getMessage() + "\n");
                    } else if (code == ExitCode.USAGE) {
                        failed.usage(err);
                    } else if (e instanceof RelixException) {
                        err.print("relix: " + e.getMessage() + "\n");
                    } else if (code == ExitCode.INTERNAL) {
                        err.print("relix: internal error; please report it with what follows\n");
                        e.printStackTrace(err);
                    }
                    err.flush();
                    return code.status();
                });
        int status = command.execute(attachOptionalValues(args));
        out.flush();
        err.flush();
        return status;
    }

    /**
     * The command line with a bare {@code --trace} given an empty value, so that the word
     * after it is never taken for its file: {@code relix --trace q.relix} traces
     * {@code q.relix}, and a file is named only as {@code --trace=FILE}. Nothing after
     * {@code --} is touched.
     *
     * @param args the command line
     * @return the command line to parse
     */
    static String[] attachOptionalValues(String[] args) {
        String[] attached = args.clone();
        for (int i = 0; i < attached.length; i++) {
            if (attached[i].equals("--")) {
                break;
            }
            if (attached[i].equals(TRACE)) {
                attached[i] = TRACE + "=";
            }
        }
        return attached;
    }

    /**
     * The exit code for a failure that escaped a command.
     *
     * @param failure what was thrown
     * @return its exit code
     */
    static ExitCode exitCode(Throwable failure) {
        if (failure instanceof CommandFailure f) {
            return f.exitCode();
        }
        if (failure instanceof RelixException e) {
            return ExitCode.of(e);
        }
        return ExitCode.INTERNAL;
    }

    /**
     * The command's version and the engine's, as {@code relix <version> (engine <version>)}.
     *
     * @return the line {@code relix --version} prints
     */
    public static String versionLine() {
        return "relix " + version(Main.class) + " (engine " + version(Relix.class) + ")";
    }

    /**
     * The version of the module a class is in, from its descriptor, or from its jar's
     * manifest when it runs on a class path.
     */
    private static String version(Class<?> type) {
        Module module = type.getModule();
        if (module.isNamed() && module.getDescriptor().version().isPresent()) {
            return module.getDescriptor().version().map(ModuleDescriptor.Version::toString).orElseThrow();
        }
        String implementation = type.getPackage().getImplementationVersion();
        return implementation != null ? implementation : "unknown";
    }
}
