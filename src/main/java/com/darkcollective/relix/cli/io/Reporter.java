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

import com.darkcollective.relix.embed.Diagnostic;
import com.darkcollective.relix.semantic.Severity;

import java.io.PrintStream;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;

/**
 * Everything the command says on standard error (design §3.5).
 *
 * <p>A diagnostic is printed as {@code FILE:LINE:COL: severity: message}, the shape
 * {@code gcc}, {@code grep -n}, editors' quickfix lists and CI annotators already read.
 * How much is said depends on the verbosity: {@code -q} silences warnings, {@code -v} adds
 * notices such as the drivers loaded, and {@code -vv} adds timings. Errors are always
 * printed.
 */
public final class Reporter {

    /** How much to say. */
    public enum Verbosity {
        /** Errors only ({@code -q}). */
        QUIET,
        /** Errors and warnings. */
        NORMAL,
        /** Notices too ({@code -v}). */
        VERBOSE,
        /** Timings too ({@code -vv}). */
        TIMINGS
    }

    private final PrintStream err;
    private final Verbosity verbosity;

    /**
     * A reporter writing to {@code err}.
     *
     * @param err       standard error
     * @param verbosity how much to say
     */
    public Reporter(PrintStream err, Verbosity verbosity) {
        this.err = Objects.requireNonNull(err, "err");
        this.verbosity = Objects.requireNonNull(verbosity, "verbosity");
    }

    /**
     * Prints one diagnostic, unless it is a warning and warnings are silenced.
     *
     * @param where     where the diagnostic points, already placed in its script
     * @param diagnostic the diagnostic
     */
    public void diagnostic(Place where, Diagnostic diagnostic) {
        if (!diagnostic.isError() && verbosity == Verbosity.QUIET) {
            return;
        }
        err.print(where + ": " + severity(diagnostic.severity()) + ": " + diagnostic.message() + "\n");
    }

    /**
     * Prints an error that has no place in a script.
     *
     * @param message what went wrong
     */
    public void error(String message) {
        err.print("relix: " + message + "\n");
    }

    /**
     * Prints a warning that has no place in a script, unless warnings are silenced.
     *
     * @param message what is amiss
     */
    public void warning(String message) {
        if (verbosity != Verbosity.QUIET) {
            err.print("relix: warning: " + message + "\n");
        }
    }

    /**
     * Prints a notice when {@code -v} is given.
     *
     * @param message what the command did
     */
    public void notice(String message) {
        if (verbosity.compareTo(Verbosity.VERBOSE) >= 0) {
            err.print("relix: " + message + "\n");
        }
    }

    /**
     * Prints how long something took when {@code -vv} is given.
     *
     * @param what    what was timed
     * @param elapsed how long it took
     */
    public void timing(String what, Duration elapsed) {
        if (verbosity == Verbosity.TIMINGS) {
            err.print("relix: " + what + ": " + elapsed.toMillis() + " ms\n");
        }
    }

    private static String severity(Severity severity) {
        return severity.name().toLowerCase(Locale.ROOT);
    }

    /**
     * A point in a script: {@code FILE:LINE:COL}, or less when less is known.
     *
     * @param file   the script's name: a path, {@code -e} or {@code <stdin>}
     * @param line   the 1-based line, or 0 when unknown
     * @param column the 1-based column, or 0 when unknown
     */
    public record Place(String file, int line, int column) {

        @Override
        public String toString() {
            if (line <= 0) {
                return file;
            }
            return column <= 0 ? file + ":" + line : file + ":" + line + ":" + column;
        }
    }
}
