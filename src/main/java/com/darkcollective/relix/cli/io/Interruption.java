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

import com.darkcollective.relix.embed.Relix;

import java.util.stream.Stream;

/**
 * What an interrupt ({@code SIGINT}) stops: the session running now, and the row stream
 * being read from it (design §3.6).
 *
 * <p>The run registers each as it opens it. {@link #interrupt()}, called from a shutdown
 * hook on another thread, cancels what the session is running, closes the stream and then
 * the session, so that a database statement in flight is cancelled rather than abandoned.
 * The run, finding its query cancelled, sees {@link #interrupted()} and ends with
 * {@link com.darkcollective.relix.cli.ExitCode#INTERRUPTED}.
 */
public final class Interruption {

    private Relix session;
    private Stream<?> stream;
    private boolean interrupted;

    /**
     * Notes the session now running, or none.
     *
     * @param session the session, or {@code null} once it is closed
     */
    public synchronized void session(Relix session) {
        this.session = session;
    }

    /**
     * Notes the row stream now being read, or none.
     *
     * @param stream the stream, or {@code null} once it is closed
     */
    public synchronized void stream(Stream<?> stream) {
        this.stream = stream;
    }

    /**
     * Whether the run has been interrupted.
     *
     * @return {@code true} once {@link #interrupt()} has been called
     */
    public synchronized boolean interrupted() {
        return interrupted;
    }

    /**
     * Stops the run: cancels the session's queries, then closes the stream and the
     * session. Safe from any thread, and more than once.
     */
    public void interrupt() {
        Relix s;
        Stream<?> r;
        synchronized (this) {
            interrupted = true;
            s = session;
            r = stream;
            session = null;
            stream = null;
        }
        // Each step is attempted whatever the one before it did: an interrupt is the last
        // chance to let go of a database statement.
        if (s != null) {
            quietly(s::cancel);
        }
        if (r != null) {
            quietly(r::close);
        }
        if (s != null) {
            quietly(s::close);
        }
    }

    private static void quietly(Runnable step) {
        try {
            step.run();
        } catch (RuntimeException ignored) {
            // The process is ending; there is no one left to tell.
        }
    }
}
