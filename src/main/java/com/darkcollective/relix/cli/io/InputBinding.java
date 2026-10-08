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
import com.darkcollective.relix.embed.Input;
import com.darkcollective.relix.embed.InputFormat;
import com.darkcollective.relix.embed.Relix;
import com.darkcollective.relix.lang.ast.ScriptParseException;
import com.darkcollective.relix.lang.ast.SourceDeclaration;
import com.darkcollective.relix.lang.ast.Statement;
import com.darkcollective.relix.lang.ast.source.ColumnSpec;
import com.darkcollective.relix.lang.ast.source.CsvFileSourceConfig;
import com.darkcollective.relix.symbol.ColumnDefinition;
import com.darkcollective.relix.symbol.Schema;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * A relation bound to data for one run: {@code -i NAME=[FORMAT:]LOCATION} (design §3.2).
 *
 * <p>The location is a path, {@code -} for standard input, or an {@code http(s)} URL,
 * which {@code --remote} must permit. The format is {@code csv}, {@code tsv}, {@code json}
 * or {@code ndjson}; without one the extension decides, and standard input, which has
 * none, must name one. A file or URL is opened afresh for each read of the relation;
 * standard input is read once, by the one session that binds it.
 *
 * <p>The heading is inferred from the first records ({@code --infer-rows}) or given with
 * {@code --schema NAME='{ id: NUMBER, … }'}. An input marked {@code --unbounded} never
 * ends: it streams, and the engine refuses a blocking operator over it before it starts.
 *
 * @param name     the relation's name
 * @param format   how its text is written
 * @param location what {@code -i} gave: a path, {@code -} or a URL
 * @param open     opens a file or URL afresh; {@code null} for standard input
 * @param schema   the declared heading, or {@code null} to infer one
 */
public record InputBinding(String name, InputFormat format, String location,
                           Supplier<InputStream> open, Schema schema) {

    /** The location that is standard input. */
    public static final String STDIN = "-";

    /**
     * A binding, checked.
     */
    public InputBinding {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(format, "format");
        Objects.requireNonNull(location, "location");
    }

    /**
     * Whether this input is standard input.
     *
     * @return {@code true} for {@code -}
     */
    public boolean isStdin() {
        return open == null;
    }

    /**
     * Declares this input on a session.
     *
     * @param session   the session
     * @param stdin     standard input, for a binding of {@code -}
     * @param sample    how many records a heading is inferred from
     * @param unbounded whether the input never ends
     * @throws com.darkcollective.relix.embed.RelixException when the text cannot be read as
     *         its format, or its heading cannot be inferred
     */
    public void declare(Relix session, InputStream stdin, int sample, boolean unbounded) {
        Input input = isStdin() ? Input.of(format, stdin) : Input.of(format, open);
        input = input.sample(sample);
        if (schema != null) {
            input = input.schema(schema);
        }
        if (unbounded) {
            input = input.unbounded();
        }
        session.input(name, input);
    }

    /**
     * The inputs a command line binds.
     *
     * @param specs     the {@code -i} values, in order
     * @param schemas   the {@code --schema} values, by input name
     * @param unbounded the {@code --unbounded} names
     * @param directory where relative paths start from
     * @param remote    whether {@code --remote} permits a URL
     * @return one binding per {@code -i}, in order
     * @throws CommandFailure with {@link ExitCode#USAGE} for a malformed binding, a format
     *         that cannot be told, a file that is not there, a URL without {@code --remote},
     *         a name bound twice, standard input bound twice, or a {@code --schema} or
     *         {@code --unbounded} for a name no {@code -i} binds
     */
    public static List<InputBinding> resolve(List<String> specs, Map<String, String> schemas,
                                             List<String> unbounded, Path directory, boolean remote) {
        List<InputBinding> bindings = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (String spec : specs) {
            InputBinding binding = parse(spec, schemas, directory, remote);
            if (!names.add(binding.name())) {
                throw usage("-i " + spec + ": '" + binding.name() + "' is bound twice");
            }
            bindings.add(binding);
        }
        if (bindings.stream().filter(InputBinding::isStdin).count() > 1) {
            throw usage("standard input (-) can be bound to one input only");
        }
        for (String name : schemas.keySet()) {
            if (!names.contains(name)) {
                throw usage("--schema " + name + ": no input of that name is bound with -i");
            }
        }
        for (String name : unbounded) {
            InputBinding binding = bindings.stream().filter(b -> b.name().equals(name)).findFirst()
                    .orElseThrow(() -> usage("--unbounded " + name + ": no input of that name is bound with -i"));
            if (binding.format() == InputFormat.JSON) {
                throw usage("--unbounded " + name + ": a JSON array is read whole, so it cannot be "
                        + "unbounded; write one object per line and bind it as ndjson");
            }
        }
        return List.copyOf(bindings);
    }

    private static InputBinding parse(String spec, Map<String, String> schemas, Path directory,
                                      boolean remote) {
        int eq = spec.indexOf('=');
        if (eq <= 0 || eq == spec.length() - 1) {
            throw usage("-i " + spec + ": expected NAME=[FORMAT:]LOCATION");
        }
        String name = spec.substring(0, eq).strip();
        String rest = spec.substring(eq + 1);
        InputFormat format = null;
        int colon = rest.indexOf(':');
        if (colon > 0) {
            format = format(rest.substring(0, colon));
            if (format != null) {
                rest = rest.substring(colon + 1);
            }
        }
        if (rest.isEmpty()) {
            throw usage("-i " + spec + ": expected NAME=[FORMAT:]LOCATION");
        }
        Schema schema = schemas.containsKey(name) ? schema(name, schemas.get(name)) : null;
        if (rest.equals(STDIN)) {
            if (format == null) {
                throw usage("-i " + spec + ": standard input has no extension to tell its format by; "
                        + "name one, as " + name + "=ndjson:-");
            }
            return new InputBinding(name, format, rest, null, schema);
        }
        String lower = rest.toLowerCase(Locale.ROOT);
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            if (!remote) {
                throw usage("-i " + spec + ": reading a URL needs --remote");
            }
            URI uri;
            try {
                uri = new URI(rest);
            } catch (URISyntaxException e) {
                throw usage("-i " + spec + ": " + e.getMessage());
            }
            InputFormat chosen = format != null ? format : byExtension(spec, uri.getPath());
            return new InputBinding(name, chosen, rest, () -> open(uri), schema);
        }
        Path path;
        try {
            path = directory.resolve(rest).normalize();
        } catch (InvalidPathException e) {
            throw usage("-i " + spec + ": " + e.getMessage());
        }
        if (!Files.isRegularFile(path)) {
            throw usage("-i " + spec + ": " + rest + ": no such file");
        }
        InputFormat chosen = format != null ? format : byExtension(spec, path.getFileName().toString());
        return new InputBinding(name, chosen, rest, () -> open(path), schema);
    }

    /** The format a prefix names, or {@code null} when it names none, as {@code C} in {@code C:\…}. */
    private static InputFormat format(String prefix) {
        for (InputFormat format : InputFormat.values()) {
            if (format.name().equalsIgnoreCase(prefix)) {
                return format;
            }
        }
        return null;
    }

    private static InputFormat byExtension(String spec, String file) {
        String lower = file == null ? "" : file.toLowerCase(Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        String extension = dot < 0 ? "" : lower.substring(dot + 1);
        return switch (extension) {
            case "csv" -> InputFormat.CSV;
            case "tsv", "tab" -> InputFormat.TSV;
            case "json" -> InputFormat.JSON;
            case "ndjson", "jsonl" -> InputFormat.NDJSON;
            default -> throw usage("-i " + spec + ": cannot tell the format from "
                    + (extension.isEmpty() ? "a name with no extension" : "the extension ." + extension)
                    + "; name it, as NAME=csv:LOCATION (csv, tsv, json or ndjson)");
        };
    }

    /**
     * A heading as {@code --schema} writes it: a source's {@code schema:} block, such as
     * {@code { id: NUMBER, name: STRING }}, read by the engine's own parser.
     */
    private static Schema schema(String name, String text) {
        List<Statement> statements;
        try {
            statements = Relix.parse("source " + name + " from csv(\"-\") { schema: " + text + " };")
                    .statements();
        } catch (ScriptParseException e) {
            throw usage("--schema " + name + ": expected a heading such as '{ id: NUMBER, name: STRING }': "
                    + e.getMessage());
        }
        if (statements.size() == 1 && statements.getFirst() instanceof SourceDeclaration source
                && source.config() instanceof CsvFileSourceConfig csv && !csv.columns().isEmpty()) {
            List<ColumnDefinition> columns = new ArrayList<>();
            for (ColumnSpec column : csv.columns()) {
                columns.add(new ColumnDefinition(column.name(), column.type()));
            }
            return new Schema(columns);
        }
        throw usage("--schema " + name + ": expected a heading such as '{ id: NUMBER, name: STRING }'");
    }

    private static InputStream open(Path path) {
        try {
            return Files.newInputStream(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static InputStream open(URI uri) {
        try {
            return uri.toURL().openStream();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static CommandFailure usage(String message) {
        return new CommandFailure(ExitCode.USAGE, message);
    }
}
