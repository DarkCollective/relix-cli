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
package com.darkcollective.relix.cli.command;

import com.darkcollective.relix.cli.io.RowSink;
import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.symbol.ColumnDefinition;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.Schema;
import com.darkcollective.relix.value.BooleanValue;
import com.darkcollective.relix.value.NullValue;
import com.darkcollective.relix.value.NumberValue;
import com.darkcollective.relix.value.StringValue;
import com.darkcollective.relix.value.Value;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Rows a command makes itself, such as a catalog listing, written through the same
 * renderers as a query's, so that they come out as a table on a terminal and as tsv or
 * ndjson in a pipe.
 */
final class Listing {

    private final Schema schema;
    private final List<Row> rows = new ArrayList<>();

    /**
     * A listing with the given heading.
     *
     * @param columns each column's name and type, in turn
     */
    Listing(Object... columns) {
        List<ColumnDefinition> definitions = new ArrayList<>();
        for (int i = 0; i < columns.length; i += 2) {
            definitions.add(new ColumnDefinition((String) columns[i], (ScalarType) columns[i + 1]));
        }
        this.schema = new Schema(definitions);
    }

    /**
     * Adds a row: a {@link String}, a {@link Number}, a {@link Boolean} or {@code null} per
     * column.
     *
     * @param values the row's values, in column order
     * @return this
     */
    Listing add(Object... values) {
        List<Value> row = new ArrayList<>(values.length);
        for (Object value : values) {
            row.add(switch (value) {
                case null -> NullValue.INSTANCE;
                case String s -> new StringValue(s);
                case Boolean b -> new BooleanValue(b);
                case Number n -> new NumberValue(new BigDecimal(n.toString()));
                default -> throw new IllegalArgumentException("not a listing value: " + value);
            });
        }
        rows.add(Row.of(schema, row));
        return this;
    }

    /**
     * Writes the listing to standard output.
     *
     * @param invocation the run, for its standard output
     * @param format     the format to write it in
     * @param label      what the listing is, for a format that names its result
     */
    void print(Invocation invocation, FormatOptions.Format format, String label) {
        new RowSink(invocation.host().out(), false)
                .write(rows.stream(), format.format().encoder(label, schema, format.options()));
    }
}
