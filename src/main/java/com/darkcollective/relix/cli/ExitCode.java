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

import com.darkcollective.relix.embed.QueryExecutionException;
import com.darkcollective.relix.embed.RelixException;

import java.net.ConnectException;
import java.net.UnknownHostException;
import java.sql.SQLException;

/**
 * The command's exit status (design §3.4): one code per class of failure, so that a script
 * or a CI step can tell a typo from a dead database.
 */
public enum ExitCode {

    /** Success. */
    SUCCESS(0),
    /** An assertion asked for failed ({@code --fail-empty}, {@code --fail-rows}). */
    ASSERTION(1),
    /** A usage error: a bad option, a missing argument, nothing to run. */
    USAGE(2),
    /** The script did not parse or analyse. */
    ANALYSIS(3),
    /** Execution failed: a data error, a source that could not be read, a limit hit. */
    EXECUTION(4),
    /** The environment: a missing driver, an unreadable profile, a connection refused. */
    ENVIRONMENT(5),
    /** A defect in relix ({@code EX_SOFTWARE}). */
    INTERNAL(70),
    /** Interrupted by {@code SIGINT}. */
    INTERRUPTED(130),
    /** The reader of standard output went away ({@code SIGPIPE}). */
    PIPE_CLOSED(141);

    private final int status;

    ExitCode(int status) {
        this.status = status;
    }

    /**
     * The process exit status.
     *
     * @return the number the shell sees
     */
    public int status() {
        return status;
    }

    /**
     * The exit code for a failure the engine raised.
     *
     * <p>A query that started and did not finish is an execution failure, unless what gave
     * way was the way to the data — a host that is not there, a connection refused — which
     * is the environment's. Anything else the engine raises means the query was never
     * runnable as written: it did not parse, did not analyse, would need to buffer an
     * endless input, or asked for what the sandbox forbids.
     *
     * @param failure what the engine threw
     * @return its exit code
     */
    public static ExitCode of(RelixException failure) {
        if (failure instanceof QueryExecutionException) {
            return unreachable(failure) ? ENVIRONMENT : EXECUTION;
        }
        return ANALYSIS;
    }

    private static boolean unreachable(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof ConnectException || t instanceof UnknownHostException) {
                return true;
            }
            // SQLSTATE class 08: connection exception.
            if (t instanceof SQLException sql && sql.getSQLState() != null
                    && sql.getSQLState().startsWith("08")) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }
}
