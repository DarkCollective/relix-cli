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

import com.darkcollective.relix.cli.CommandFailure;
import com.darkcollective.relix.cli.ExitCode;
import com.darkcollective.relix.cli.render.OutputFormat;
import com.darkcollective.relix.processor.Row;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Where result rows go: standard output, buffered, and watched for the reader going away
 * (design §3.6).
 *
 * <p>Each row is written as soon as it is encoded, and a failed write is noticed after
 * that row: the row stream is closed, which the engine takes as cancellation, so a plan
 * stops pulling and a database cursor is closed, and the run ends with
 * {@link ExitCode#PIPE_CLOSED} and nothing said. {@code relix … | head} therefore ends
 * when {@code head} does.
 *
 * <p>Output is buffered and flushed at the end of each result set, or after every row when
 * line-buffered ({@code --line-buffered}, as {@code grep}'s), for a reader that wants each
 * row the moment it is made. A buffered write reaches the pipe only when the buffer fills,
 * so it is then that a closed pipe is noticed.
 */
public final class RowSink {

    private static final int BUFFER = 1 << 16;

    private final OutputStream out;
    private final boolean lineBuffered;

    /**
     * A sink over standard output.
     *
     * @param out          standard output, unbuffered
     * @param lineBuffered whether to flush after every row
     */
    public RowSink(OutputStream out, boolean lineBuffered) {
        this.out = new BufferedOutputStream(Objects.requireNonNull(out, "out"), BUFFER);
        this.lineBuffered = lineBuffered;
    }

    /**
     * Writes one result set and flushes it.
     *
     * @param rows    the rows, pulled one at a time; closed here if the write fails
     * @param encoder the result set's encoder
     * @return how many rows were written
     * @throws CommandFailure with {@link ExitCode#PIPE_CLOSED} when standard output has
     *                        gone away
     */
    public long write(Stream<? extends Row> rows, OutputFormat.Encoder encoder) {
        long count = 0;
        try {
            write(encoder.begin());
            for (Iterator<? extends Row> it = rows.iterator(); it.hasNext(); ) {
                write(encoder.row(it.next()));
                count++;
                if (lineBuffered) {
                    out.flush();
                }
            }
            write(encoder.end());
            out.flush();
        } catch (IOException e) {
            rows.close();
            throw new CommandFailure(ExitCode.PIPE_CLOSED, null);
        }
        return count;
    }

    /**
     * Writes a whole text, such as a report or a plan, and flushes it.
     *
     * @param text the text
     * @throws CommandFailure with {@link ExitCode#PIPE_CLOSED} when standard output has
     *                        gone away
     */
    public void text(String text) {
        try {
            write(text);
            out.flush();
        } catch (IOException e) {
            throw new CommandFailure(ExitCode.PIPE_CLOSED, null);
        }
    }

    private void write(String text) throws IOException {
        if (!text.isEmpty()) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }
}
