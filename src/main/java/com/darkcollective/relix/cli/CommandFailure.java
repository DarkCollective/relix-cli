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

import java.util.Objects;

/**
 * A run that ends with a non-zero exit status the command chose, rather than one an
 * exception implies.
 *
 * <p>The message, when there is one, is printed as {@code relix: message} on standard
 * error. A failure whose details were already reported, such as a script's diagnostics,
 * carries none.
 */
public final class CommandFailure extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient ExitCode exitCode;

    /**
     * A failure with a message for standard error.
     *
     * @param exitCode the status to exit with; not {@link ExitCode#SUCCESS}
     * @param message  what to tell the user, or {@code null} when it has been said
     */
    public CommandFailure(ExitCode exitCode, String message) {
        super(message, null, false, false);
        this.exitCode = Objects.requireNonNull(exitCode, "exitCode");
        if (exitCode == ExitCode.SUCCESS) {
            throw new IllegalArgumentException("a failure cannot exit with success");
        }
    }

    /**
     * The status to exit with.
     *
     * @return the exit code
     */
    public ExitCode exitCode() {
        return exitCode;
    }
}
