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
import com.darkcollective.relix.symbol.ColumnDefinition;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.Schema;
import com.darkcollective.relix.value.NumberValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The row sink's end-of-pipe discipline (design §3.6, §7.5): counts, not times.
 */
@DisplayName("RowSink")
class RowSinkTest {

    private static final Schema SCHEMA = new Schema(List.of(new ColumnDefinition("n", ScalarType.NUMBER)));

    private final AtomicLong pulled = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();

    /** An endless row stream that counts what is pulled from it and notes its closing. */
    private Stream<Row> naturals() {
        return Stream.iterate(0L, n -> n + 1)
                .map(n -> {
                    pulled.incrementAndGet();
                    return Row.of(SCHEMA, NumberValue.of(Long.toString(n)));
                })
                .onClose(() -> closed.set(true));
    }

    private static OutputFormat.Encoder ndjson() {
        return OutputFormat.NDJSON.encoder("R", SCHEMA, OutputFormat.Options.DEFAULTS);
    }

    @ParameterizedTest(name = "fails after {0} rows")
    @ValueSource(ints = {0, 1, 7, 100})
    @DisplayName("a sink that fails after N rows closes the stream, having pulled at most N + 1")
    void failsAfterNRows(int n) {
        RowSink sink = new RowSink(new FailsAfterLines(n), true);

        assertThatThrownBy(() -> sink.write(naturals(), ndjson()))
                .isInstanceOfSatisfying(CommandFailure.class, f -> {
                    assertThat(f.exitCode()).isEqualTo(ExitCode.PIPE_CLOSED);
                    assertThat(f.getMessage()).isNull();
                });
        assertThat(closed).as("the stream was closed").isTrue();
        assertThat(pulled.get()).as("rows pulled").isLessThanOrEqualTo(n + 1L);
    }

    @Test
    @DisplayName("buffered, a closed pipe is noticed when the buffer is written, and the stream is closed")
    void bufferedFailure() {
        RowSink sink = new RowSink(new FailsAfterLines(3), false);

        assertThatThrownBy(() -> sink.write(naturals(), ndjson())).isInstanceOf(CommandFailure.class);
        assertThat(closed).isTrue();
    }

    @Test
    @DisplayName("line-buffered, each row reaches the reader before the next is pulled")
    void lineBuffered() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        RowSink sink = new RowSink(out, true);
        Stream<Row> rows = Stream.iterate(0, i -> i < 3, i -> i + 1).map(i -> {
            assertThat(out.toString(StandardCharsets.UTF_8).lines()).as("before row " + i).hasSize(i);
            return Row.of(SCHEMA, NumberValue.of(Integer.toString(i)));
        });

        assertThat(sink.write(rows, ndjson())).isEqualTo(3);
        assertThat(out.toString(StandardCharsets.UTF_8)).isEqualTo("{\"n\":0}\n{\"n\":1}\n{\"n\":2}\n");
    }

    @Test
    @DisplayName("buffered, a result set reaches the reader at its end")
    void flushedPerResultSet() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        RowSink sink = new RowSink(out, false);
        Stream<Row> rows = Stream.iterate(0, i -> i < 3, i -> i + 1).map(i -> {
            assertThat(out.size()).as("nothing yet, before row " + i).isZero();
            return Row.of(SCHEMA, NumberValue.of(Integer.toString(i)));
        });

        sink.write(rows, ndjson());

        assertThat(out.toString(StandardCharsets.UTF_8).lines()).hasSize(3);
    }

    /** A reader that takes {@code lines} lines, then refuses every write. */
    private static final class FailsAfterLines extends OutputStream {
        private int lines;

        FailsAfterLines(int lines) {
            this.lines = lines;
        }

        @Override
        public void write(int b) throws IOException {
            if (lines <= 0) {
                throw new IOException("Broken pipe");
            }
            if (b == '\n') {
                lines--;
            }
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            for (int i = 0; i < len; i++) {
                write(b[off + i]);
            }
        }
    }
}
