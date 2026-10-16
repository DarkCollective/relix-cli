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

import com.darkcollective.relix.ast.Spelling;
import com.darkcollective.relix.cli.CommandFailure;
import com.darkcollective.relix.cli.ExitCode;
import com.darkcollective.relix.cli.catalog.Catalog;
import com.darkcollective.relix.cli.catalog.CatalogFile;
import com.darkcollective.relix.cli.catalog.Declaration;
import com.darkcollective.relix.cli.command.DeclarationSpec.Noun;
import com.darkcollective.relix.cli.render.ScriptFormatter;
import com.darkcollective.relix.embed.Relix;
import com.darkcollective.relix.embed.RelixException;
import com.darkcollective.relix.embed.Tuple;
import com.darkcollective.relix.lang.ast.ConnectionDeclaration;
import com.darkcollective.relix.lang.ast.ScriptPrinter;
import com.darkcollective.relix.lang.ast.SourceDeclaration;
import com.darkcollective.relix.lang.ast.Statement;
import com.darkcollective.relix.lang.ast.source.ConnectionTableSourceConfig;
import com.darkcollective.relix.lang.ast.source.CsvFileSourceConfig;
import com.darkcollective.relix.lang.ast.source.DatabaseSourceConfig;
import com.darkcollective.relix.lang.ast.source.GeneratorSourceConfig;
import com.darkcollective.relix.lang.ast.source.HttpSourceConfig;
import com.darkcollective.relix.lang.ast.source.JsonFileSourceConfig;
import com.darkcollective.relix.symbol.ColumnDefinition;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.Schema;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * What {@code relix source} and {@code relix connection} do: declare, list, change, remove
 * and test the sources and connections of the catalog, without a catalog file written by
 * hand.
 *
 * <p>Each {@code add} writes one declaration to a file of its own,
 * {@code catalog/00-source-<name>.relix} or {@code catalog/00-connection-<name>.relix}, as
 * {@code relix fmt} prints it. One declaration to a file is what makes {@code edit} and
 * {@code rm} safe: they change or remove a file that holds the one declaration and nothing
 * else, and report a declaration that shares its file with others rather than touch it.
 * Every write follows the trust rule of {@link ProjectWrite}.
 */
final class Declarations {

    /** The table a connection test reads, which no database should have. */
    private static final String PROBE_TABLE = "RELIX_CONNECTION_TEST_PROBE";

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}");
    private static final Pattern FLAG = Pattern.compile("--([^=]+)(=(.*))?", Pattern.DOTALL);
    private static final Pattern PLAIN = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private Declarations() {
    }

    // ── add ──────────────────────────────────────────────────────────────────────────

    /**
     * Declares a source or a connection in a file of its own.
     *
     * @param invocation  the run
     * @param noun        what is declared
     * @param name        its name
     * @param from        its kind, or the connection a source reads
     * @param location    the file a file kind reads, or {@code null}
     * @param flags       the declaration's options, as {@code --key=value} or {@code --key}
     * @param global      whether to write {@code ~/.relix} rather than the nearest project
     * @param inferSchema whether to read a sample and write the schema it has
     * @return the exit status
     */
    static int add(Invocation invocation, Noun noun, String name, String from, String location,
                   List<String> flags, boolean global, boolean inferSchema) {
        Path level = ProjectWrite.level(invocation, global, "--global");
        Path catalogDir = level.resolve(Catalog.DIRECTORY);
        Path file = catalogDir.resolve("00-" + noun.word() + "-" + name + Catalog.EXTENSION);
        DeclarationSpec spec = DeclarationSpec.of(noun, name, from);
        Catalog catalog = invocation.catalog();
        if (noun == Noun.SOURCE) {
            requireKind(catalog, from);
            if (DeclarationSpec.FILE_KINDS.contains(from) && location == null) {
                throw new CommandFailure(ExitCode.USAGE, "LOCATION is required: a " + from
                        + " source names the file it reads");
            }
            if (!DeclarationSpec.FILE_KINDS.contains(from) && location != null) {
                throw new CommandFailure(ExitCode.USAGE, "a " + from + " source takes no LOCATION;"
                        + " give its options as flags, such as --table=NAME");
            }
            if (location != null) {
                spec.location(relocated(invocation, catalogDir, location));
            }
        } else if (location != null) {
            spec.set("path", relocated(invocation, catalogDir, location));
        }
        applyFlags(invocation, catalogDir, spec, flags);
        if (inferSchema) {
            inferInto(invocation, catalog, spec, file);
        }
        Statement statement = spec.parse(file.toString());
        for (Declaration existing : declarationsOf(catalog, noun, name)) {
            if (level.equals(existing.file().level())) {
                throw new CommandFailure(ExitCode.USAGE, name + " is already declared here, at "
                        + where(existing) + "; change it with relix " + noun.word() + " edit " + name);
            }
        }
        if (Files.exists(file)) {
            throw new CommandFailure(ExitCode.USAGE, file + " already exists");
        }
        for (Declaration existing : declarationsOf(catalog, noun, name)) {
            if (existing.file().trusted() && catalog.wins(existing)) {
                invocation.reporter().warning(name + " is declared at " + where(existing)
                        + " too; this declaration is nearer, and replaces it below " + level.getParent());
            }
        }
        String text = format(spec, statement, file);
        ProjectWrite.keepingTrust(invocation, level, () -> write(file, text));
        invocation.reporter().info("declared " + noun.word() + " " + name + " in " + file);
        return ExitCode.SUCCESS.status();
    }

    /**
     * Refuses a source kind that is neither one the language has nor a declared connection.
     */
    private static void requireKind(Catalog catalog, String from) {
        if (DeclarationSpec.SOURCE_KINDS.contains(from)) {
            return;
        }
        boolean connection = catalog.declarations(from).stream()
                .anyMatch(d -> d.kind() == Declaration.Kind.CONNECTION && d.file().trusted() && catalog.wins(d));
        if (!connection) {
            throw new CommandFailure(ExitCode.USAGE, "'" + from + "' is neither a source kind ("
                    + String.join(", ", new TreeSet<>(DeclarationSpec.SOURCE_KINDS))
                    + ") nor a connection the catalog declares; declare it with relix connection add");
        }
    }

    /** Sets a declaration's options from {@code --key=value} and {@code --key} flags. */
    private static void applyFlags(Invocation invocation, Path catalogDir, DeclarationSpec spec, List<String> flags) {
        for (String flag : flags) {
            Matcher m = FLAG.matcher(flag);
            if (!m.matches()) {
                throw new CommandFailure(ExitCode.USAGE, "'" + flag + "': expected --OPTION=VALUE, or --OPTION"
                        + " for true; a value is joined to its option with =");
            }
            String key = m.group(1);
            String value = m.group(2) == null ? "true" : m.group(3);
            if (key.equals("path") && spec.noun() == Noun.CONNECTION) {
                value = relocated(invocation, catalogDir, value);
            }
            spec.set(key, value);
        }
    }

    /**
     * A relative file path as the catalog file must write it: the user names it from where
     * they stand, and the declaration resolves it against the catalog file's directory.
     */
    static String relocated(Invocation invocation, Path catalogDir, String location) {
        if (location.contains("://") || location.startsWith("${") || location.startsWith("file:")
                || Path.of(location).isAbsolute()) {
            return location;
        }
        Path target = invocation.directory().resolve(location).normalize();
        return catalogDir.relativize(target).toString().replace('\\', '/');
    }

    private static String format(DeclarationSpec spec, Statement statement, Path file) {
        String printed = ScriptPrinter.print(statement);
        try {
            return ScriptFormatter.format(printed, file.toString(), Spelling.GLYPHS);
        } catch (RuntimeException e) {
            // The printed form always parses; this is the engine's to explain if not.
            throw new IllegalStateException("cannot format " + spec.text() + ": " + e.getMessage(), e);
        }
    }

    private static void write(Path file, String text) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new CommandFailure(ExitCode.ENVIRONMENT, file + ": cannot write: " + e.getMessage());
        }
    }

    // ── ls ───────────────────────────────────────────────────────────────────────────

    /**
     * Lists the sources or connections the catalog declares, each as the declaration
     * that won.
     *
     * @param invocation the run
     * @param noun       which
     * @param formatting how the rows are written
     * @return the exit status
     */
    static int ls(Invocation invocation, Noun noun, FormatOptions formatting) {
        Catalog catalog = invocation.catalog();
        Listing listing = new Listing("name", ScalarType.STRING, "kind", ScalarType.STRING,
                "location", ScalarType.STRING, "file", ScalarType.STRING);
        for (Declaration declaration : winners(catalog, noun)) {
            String[] described = describe(declaration.statement());
            listing.add(declaration.name(), described[0], described[1], declaration.file().path().toString());
        }
        listing.print(invocation, invocation.format(formatting), noun.word() + "s");
        return ExitCode.SUCCESS.status();
    }

    /** A declaration's kind and location, as {@code ls} lists them. */
    private static String[] describe(Statement statement) {
        if (statement instanceof ConnectionDeclaration c) {
            Map<String, String> p = c.properties();
            return new String[] {c.connectorType(), p.getOrDefault("url", p.get("path"))};
        }
        SourceDeclaration s = (SourceDeclaration) statement;
        return switch (s.config()) {
            case CsvFileSourceConfig csv -> new String[] {"csv", csv.path()};
            case JsonFileSourceConfig json -> new String[] {"json", json.path()};
            case DatabaseSourceConfig db -> new String[] {"database", db.table()};
            case ConnectionTableSourceConfig table -> new String[] {table.connection(), table.table()};
            case HttpSourceConfig http -> new String[] {"http", http.url()};
            case GeneratorSourceConfig generator -> new String[] {"generator", generator.generatorName()};
        };
    }

    // ── edit ─────────────────────────────────────────────────────────────────────────

    /**
     * Changes a declaration's options, or opens it in {@code $EDITOR}.
     *
     * @param invocation  the run
     * @param noun        what it declares
     * @param name        its name
     * @param flags       options to set, as {@code --key=value}
     * @param unset       options to remove
     * @param location    a new file for a file kind, or {@code null}
     * @param inferSchema whether to read a sample and write the schema it has
     * @param editor      whether to open the declaration in the user's editor instead
     * @return the exit status
     */
    static int edit(Invocation invocation, Noun noun, String name, List<String> flags, List<String> unset,
                    String location, boolean inferSchema, boolean editor) {
        Catalog catalog = invocation.catalog();
        Declaration declaration = winner(catalog, noun, name);
        Path level = levelOf(declaration);
        if (editor) {
            if (!flags.isEmpty() || !unset.isEmpty() || location != null || inferSchema) {
                throw new CommandFailure(ExitCode.USAGE, "--editor changes nothing else; give it alone");
            }
            Path file = declaration.file().path();
            int line = declaration.statement().location().line();
            ProjectWrite.keepingTrust(invocation, level, () -> openEditor(invocation, file, line));
            return ExitCode.SUCCESS.status();
        }
        requireAlone(declaration, noun, "edit", "relix " + noun.word() + " edit " + name + " --editor");
        if (flags.isEmpty() && unset.isEmpty() && location == null && !inferSchema) {
            throw new CommandFailure(ExitCode.USAGE, "nothing to change: give --OPTION=VALUE, --unset=OPTION,"
                    + " --location, --infer-schema or --editor");
        }
        Path file = declaration.file().path();
        Path catalogDir = file.getParent();
        DeclarationSpec spec = DeclarationSpec.read(declaration.statement());
        for (String key : unset) {
            if (!spec.unset(key)) {
                throw new CommandFailure(ExitCode.USAGE, "--unset=" + key + ": " + name + " sets no " + key);
            }
        }
        if (location != null) {
            if (!spec.isFile()) {
                throw new CommandFailure(ExitCode.USAGE, "--location: " + name + " reads no file; it is from "
                        + spec.from());
            }
            spec.location(relocated(invocation, catalogDir, location));
        }
        applyFlags(invocation, catalogDir, spec, flags);
        if (inferSchema) {
            spec.unset("schema");
            inferInto(invocation, catalog, spec, file);
        }
        Statement statement = spec.parse(file.toString());
        String text = format(spec, statement, file);
        ProjectWrite.keepingTrust(invocation, level, () -> write(file, text));
        invocation.reporter().info("changed " + noun.word() + " " + name + " in " + file);
        return ExitCode.SUCCESS.status();
    }

    private static void openEditor(Invocation invocation, Path file, int line) {
        String editor = invocation.host().variable("VISUAL").or(() -> invocation.host().variable("EDITOR"))
                .orElseThrow(() -> new CommandFailure(ExitCode.USAGE,
                        "--editor: set $VISUAL or $EDITOR to the editor to open " + file + " in"));
        List<String> command = new ArrayList<>(List.of(editor.strip().split("\\s+")));
        command.add("+" + Math.max(line, 1));
        command.add(file.toString());
        try {
            int status = new ProcessBuilder(command).inheritIO().start().waitFor();
            if (status != 0) {
                throw new CommandFailure(ExitCode.ENVIRONMENT, "the editor exited " + status);
            }
        } catch (IOException e) {
            throw new CommandFailure(ExitCode.ENVIRONMENT, "cannot start " + editor + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CommandFailure(ExitCode.INTERRUPTED, null);
        }
    }

    // ── rm ───────────────────────────────────────────────────────────────────────────

    /**
     * Removes a declaration's file.
     *
     * @param invocation the run
     * @param noun       what it declares
     * @param name       its name
     * @param force      whether to remove it though other declarations read it
     * @return the exit status
     */
    static int rm(Invocation invocation, Noun noun, String name, boolean force) {
        Catalog catalog = invocation.catalog();
        Declaration declaration = winner(catalog, noun, name);
        Path level = levelOf(declaration);
        List<Declaration> readers = readers(catalog, declaration);
        if (!readers.isEmpty() && !force) {
            StringBuilder message = new StringBuilder(name + " is read by");
            for (Declaration reader : readers) {
                message.append("\n  ").append(reader.name()).append(" (").append(where(reader)).append(')');
            }
            throw new CommandFailure(ExitCode.USAGE, message.append("\nremove those first, or give --force").toString());
        }
        requireAlone(declaration, noun, "remove", "edit it by hand: relix " + noun.word() + " edit "
                + name + " --editor");
        Path file = declaration.file().path();
        ProjectWrite.keepingTrust(invocation, level, () -> {
            try {
                Files.delete(file);
            } catch (IOException e) {
                throw new CommandFailure(ExitCode.ENVIRONMENT, file + ": cannot remove: " + e.getMessage());
            }
        });
        invocation.reporter().info("removed " + noun.word() + " " + name + " (" + file + ")");
        declarationsOf(catalog, noun, name).stream()
                .filter(d -> d.statement() != declaration.statement() && d.file().trusted())
                .findFirst()
                .ifPresent(next -> invocation.reporter().info(name + " now comes from " + where(next)));
        return ExitCode.SUCCESS.status();
    }

    /**
     * The winning declarations that read a declaration's name: a view or source that names
     * a relation, a source or dotted reference that names a connection. Read from the
     * engine's printing of each, with its strings left out, so a name in a string or a
     * comment is not taken for a reference.
     */
    private static List<Declaration> readers(Catalog catalog, Declaration target) {
        Pattern reference = Pattern.compile("(?<![A-Za-z0-9_.])" + Pattern.quote(target.name()) + "(?![A-Za-z0-9_])");
        List<Declaration> readers = new ArrayList<>();
        Set<Statement> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (CatalogFile file : catalog.files()) {
            if (!file.trusted()) {
                continue;
            }
            for (Declaration declaration : file.declarations()) {
                Statement statement = declaration.statement();
                if (statement == target.statement() || !catalog.wins(declaration) || !seen.add(statement)) {
                    continue;
                }
                String text = ScriptPrinter.print(statement).replaceAll("\"(\\\\.|[^\"\\\\])*\"", "\"\"")
                        .replaceAll("`[^`]*`", "``");
                // A declaration's own name, after its keyword, is not a reference.
                text = text.replaceFirst("^\\s*(source|connection)\\s+[A-Za-z_][A-Za-z0-9_]*", "");
                if (reference.matcher(text).find()) {
                    readers.add(declaration);
                }
            }
        }
        return readers;
    }

    // ── test ─────────────────────────────────────────────────────────────────────────

    /** One declaration's test: whether it passed, and what is wrong when not. */
    private record Outcome(String status, String problem) {
        static final Outcome OK = new Outcome("ok", null);

        static Outcome failed(String problem) {
            return new Outcome("failed", problem);
        }
    }

    /**
     * Tests sources, by reading a row of each, or connections, by opening each.
     *
     * @param invocation the run, with its profile
     * @param noun       which
     * @param names      the declarations to test; all of them when empty
     * @param formatting how the rows are written
     * @return 0 when every one passed, else 5
     */
    static int test(Invocation invocation, Noun noun, List<String> names, FormatOptions formatting) {
        Catalog catalog = invocation.catalog();
        List<Declaration> tested = new ArrayList<>();
        if (names.isEmpty()) {
            tested.addAll(winners(catalog, noun));
        } else {
            for (String name : names) {
                tested.add(winner(catalog, noun, name));
            }
        }
        invocation.loadInstalledDrivers();
        Listing listing = new Listing("name", ScalarType.STRING, "status", ScalarType.STRING,
                "problem", ScalarType.STRING);
        boolean passed = true;
        try (Relix session = invocation.session(invocation.directory()).build()) {
            catalog.define(session);
            for (Declaration declaration : tested) {
                Outcome outcome = noun == Noun.SOURCE
                        ? testSource(invocation, catalog, session, declaration)
                        : testConnection(invocation, declaration);
                passed &= !outcome.status().equals("failed");
                listing.add(declaration.name(), outcome.status(), outcome.problem());
            }
        } catch (RelixException e) {
            throw new CommandFailure(ExitCode.of(e), "the catalog does not analyse: " + e.getMessage());
        }
        listing.print(invocation, invocation.format(formatting), "tests");
        return passed ? ExitCode.SUCCESS.status() : ExitCode.ENVIRONMENT.status();
    }

    private static Outcome testSource(Invocation invocation, Catalog catalog, Relix session, Declaration declaration) {
        SourceDeclaration source = (SourceDeclaration) declaration.statement();
        List<Statement> involved = new ArrayList<>(List.of(source));
        if (source.config() instanceof ConnectionTableSourceConfig table) {
            connection(catalog, table.connection()).ifPresent(c -> involved.addFirst(c.statement()));
        }
        Optional<String> unresolved = unresolved(invocation, involved);
        if (unresolved.isPresent()) {
            return Outcome.failed(unresolved.get());
        }
        try (Stream<Tuple> rows = session.relation(declaration.name()).limit(1).stream()) {
            rows.forEach(row -> { });
        } catch (RelixException e) {
            return Outcome.failed(oneLine(e.getMessage()));
        }
        List<ColumnDefinition> declared = session.relation(declaration.name()).schema().columns();
        if (declared.isEmpty() || !hasSchema(source)) {
            return Outcome.OK;
        }
        Optional<List<ColumnDefinition>> read;
        try {
            read = observed(invocation, catalog, DeclarationSpec.read(source), declaration.file().path());
        } catch (CommandFailure | RelixException e) {
            return Outcome.OK;
        }
        return read.map(columns -> mismatch(declared, columns)).orElse(Outcome.OK);
    }

    private static boolean hasSchema(SourceDeclaration source) {
        return DeclarationSpec.read(source).options().containsKey("schema");
    }

    /** What a declared schema gets wrong about the columns read. */
    private static Outcome mismatch(List<ColumnDefinition> declared, List<ColumnDefinition> read) {
        Map<String, ColumnDefinition> byName = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        read.forEach(c -> byName.put(c.name(), c));
        List<String> problems = new ArrayList<>();
        for (ColumnDefinition column : declared) {
            ColumnDefinition found = byName.get(column.name());
            if (found == null) {
                problems.add("no column " + column.name() + " in what was read");
            } else if (column.type() instanceof ScalarType want && found.type() instanceof ScalarType got
                    && want != ScalarType.ANY && want != ScalarType.STRING && got != ScalarType.ANY && want != got) {
                problems.add(column.name() + " is declared " + want + " but reads as " + got);
            }
        }
        return problems.isEmpty() ? Outcome.OK : Outcome.failed(String.join("; ", problems));
    }

    private static Outcome testConnection(Invocation invocation, Declaration declaration) {
        ConnectionDeclaration connection = (ConnectionDeclaration) declaration.statement();
        Optional<String> unresolved = unresolved(invocation, List.of(connection));
        if (unresolved.isPresent()) {
            return Outcome.failed(unresolved.get());
        }
        if (!connection.connectorType().equals("jdbc")) {
            return new Outcome("untested", "only a database connection can be opened to test it;"
                    + " relix source test reads its sources");
        }
        // A probe over a table no database has: the connection opened if the database
        // answers that the table is not there, and did not if anything else gave way.
        String probe = "source " + declaration.name() + "_relix_probe from " + declaration.name()
                + " { table: \"" + PROBE_TABLE + "\", schema: { probe: ANY } };";
        try (Relix session = invocation.session(declaration.file().path().getParent()).build()) {
            session.define(connection);
            session.define(probe);
            try (Stream<Tuple> rows = session.relation(declaration.name() + "_relix_probe").limit(1).stream()) {
                rows.forEach(row -> { });
            }
            return Outcome.OK;
        } catch (RelixException e) {
            return answeredByDatabase(e) ? Outcome.OK : Outcome.failed(oneLine(e.getMessage()));
        }
    }

    /** Whether a failure is the database's answer to a query, rather than no database at all. */
    private static boolean answeredByDatabase(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && sql.getSQLState() != null && sql.getSQLState().startsWith("42")) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    /** The first placeholder in the statements that has no value, said as a problem. */
    private static Optional<String> unresolved(Invocation invocation, List<Statement> statements) {
        for (Statement statement : statements) {
            Matcher m = PLACEHOLDER.matcher(ScriptPrinter.print(statement));
            while (m.find()) {
                if (invocation.placeholder(m.group(1)).isEmpty()) {
                    return Optional.of("${" + m.group(1) + "} has no value: set it in a profile (-P),"
                            + " the environment or -D");
                }
            }
        }
        return Optional.empty();
    }

    private static String oneLine(String message) {
        String line = message == null ? "failed" : message.strip();
        int end = line.indexOf('\n');
        return end < 0 ? line : line.substring(0, end).strip();
    }

    // ── schema inference ─────────────────────────────────────────────────────────────

    /**
     * Reads a sample of what a source reads and writes the schema it has into it.
     *
     * @throws CommandFailure with {@link ExitCode#USAGE} for a kind that cannot be read
     *                        this way, and with {@link ExitCode#ENVIRONMENT} when the
     *                        sample cannot be read
     */
    private static void inferInto(Invocation invocation, Catalog catalog, DeclarationSpec spec, Path file) {
        if (spec.noun() != Noun.SOURCE) {
            throw new CommandFailure(ExitCode.USAGE, "--infer-schema: a connection has no schema; its sources do");
        }
        if (spec.options().containsKey("schema")) {
            throw new CommandFailure(ExitCode.USAGE, "--infer-schema writes the schema; give it or --schema, not both");
        }
        if (spec.from().equals("json")) {
            throw new CommandFailure(ExitCode.USAGE, "--infer-schema: a json source declares no schema;"
                    + " its rows are read as they come (schema-on-read)");
        }
        if (spec.options().containsKey("columns")) {
            throw new CommandFailure(ExitCode.USAGE, "--infer-schema writes a schema, and a source declares"
                    + " a schema or its columns, not both");
        }
        List<ColumnDefinition> columns;
        try {
            columns = observed(invocation, catalog, spec, file).orElseThrow(() -> new CommandFailure(ExitCode.USAGE,
                    "--infer-schema reads csv files and database tables, not a " + spec.from() + " source"));
        } catch (RelixException e) {
            throw new CommandFailure(ExitCode.ENVIRONMENT, "--infer-schema: cannot read " + spec.name() + ": "
                    + oneLine(e.getMessage()));
        }
        if (columns.isEmpty()) {
            throw new CommandFailure(ExitCode.ENVIRONMENT, "--infer-schema: read no columns for " + spec.name());
        }
        StringBuilder schema = new StringBuilder("{ ");
        String separator = "";
        for (ColumnDefinition column : columns) {
            String type = column.type() instanceof ScalarType scalar ? scalar.name() : ScalarType.ANY.name();
            schema.append(separator).append(columnName(column.name())).append(": ").append(type);
            separator = ", ";
        }
        spec.setText("schema", schema.append(" }").toString());
    }

    private static String columnName(String name) {
        return PLAIN.matcher(name).matches() ? name : "`" + name.replace("`", "``") + "`";
    }

    /**
     * The columns a source reads, as the data has them rather than as it declares them:
     * a CSV file's heading inferred from its records, a table's as its database describes
     * it.
     *
     * @return the columns, or empty for a kind that cannot be read this way
     * @throws RelixException when the data cannot be read
     * @throws CommandFailure when the source's connection is not declared
     */
    private static Optional<List<ColumnDefinition>> observed(Invocation invocation, Catalog catalog,
                                                             DeclarationSpec source, Path file) {
        DeclarationSpec bare = source.copy();
        bare.unset("schema");
        Path base = file.getParent();
        switch (source.from()) {
            case "csv" -> {
                Statement statement = bare.parse(file.toString());
                try (Relix session = invocation.session(base).build()) {
                    session.define(statement);
                    return Optional.of(session.relation(source.name()).schema().columns());
                }
            }
            case "database" -> {
                String table = table(source);
                DeclarationSpec connection = DeclarationSpec.of(Noun.CONNECTION, "relix_infer", "database");
                for (String key : List.of("url", "user", "password", "dialect")) {
                    String value = source.options().get(key);
                    if (value != null) {
                        connection.setText(key, value);
                    }
                }
                try (Relix session = invocation.session(base).build()) {
                    session.define(connection.parse(file.toString()));
                    return Optional.of(tableColumns(session, "relix_infer", table));
                }
            }
            case "json", "http", "generator" -> {
                return Optional.empty();
            }
            default -> {
                String table = table(source);
                Declaration connection = connection(catalog, source.from()).orElseThrow(() ->
                        new CommandFailure(ExitCode.USAGE, "the catalog declares no connection " + source.from()));
                try (Relix session = invocation.session(connection.file().path().getParent()).build()) {
                    session.define(connection.statement());
                    return Optional.of(tableColumns(session, source.from(), table));
                }
            }
        }
    }

    private static String table(DeclarationSpec source) {
        String table = source.option("table").orElseThrow(() -> new CommandFailure(ExitCode.USAGE,
                "--table is required: a " + source.from() + " source names the table it reads"));
        if (!PLAIN.matcher(table).matches()) {
            throw new CommandFailure(ExitCode.USAGE, "--infer-schema reads a table whose name is a plain name,"
                    + " not '" + table + "'; give --schema instead");
        }
        return table;
    }

    private static List<ColumnDefinition> tableColumns(Relix session, String connection, String table) {
        Schema schema = session.relation(connection + "." + table).schema();
        return schema.columns();
    }

    // ── finding declarations ─────────────────────────────────────────────────────────

    /** Every declaration of a name that competes with a source or a connection, nearest first. */
    private static List<Declaration> declarationsOf(Catalog catalog, Noun noun, String name) {
        return catalog.declarations(name).stream()
                .filter(d -> noun == Noun.CONNECTION
                        ? d.kind() == Declaration.Kind.CONNECTION
                        : d.kind().sameSpace(Declaration.Kind.SOURCE))
                .toList();
    }

    /** The winning sources or connections, by name. */
    private static List<Declaration> winners(Catalog catalog, Noun noun) {
        Declaration.Kind kind = noun == Noun.SOURCE ? Declaration.Kind.SOURCE : Declaration.Kind.CONNECTION;
        Map<String, Declaration> byName = new TreeMap<>();
        for (CatalogFile file : catalog.files()) {
            for (Declaration declaration : file.declarations()) {
                if (declaration.kind() == kind && file.trusted() && catalog.wins(declaration)) {
                    byName.put(declaration.name(), declaration);
                }
            }
        }
        return List.copyOf(byName.values());
    }

    /**
     * The declaration of a name that won, which must declare what {@code noun} says.
     *
     * @throws CommandFailure with {@link ExitCode#USAGE} when there is none, or it is
     *                        something else
     */
    private static Declaration winner(Catalog catalog, Noun noun, String name) {
        Declaration winner = declarationsOf(catalog, noun, name).stream()
                .filter(d -> d.file().trusted() && catalog.wins(d))
                .findFirst()
                .orElseThrow(() -> new CommandFailure(ExitCode.USAGE, "the catalog declares no " + noun.word()
                        + " " + name));
        Declaration.Kind kind = noun == Noun.SOURCE ? Declaration.Kind.SOURCE : Declaration.Kind.CONNECTION;
        if (winner.kind() != kind) {
            throw new CommandFailure(ExitCode.USAGE, name + " is a " + winner.kind().label() + ", not a "
                    + noun.word() + ", at " + where(winner));
        }
        return winner;
    }

    private static Optional<Declaration> connection(Catalog catalog, String name) {
        return catalog.declarations(name).stream()
                .filter(d -> d.kind() == Declaration.Kind.CONNECTION && d.file().trusted() && catalog.wins(d))
                .findFirst();
    }

    /** The {@code .relix/} directory a declaration's file is in; a {@code --catalog} file is not changed. */
    private static Path levelOf(Declaration declaration) {
        Path level = declaration.file().level();
        if (level == null) {
            throw new CommandFailure(ExitCode.USAGE, declaration.name() + " is declared at " + where(declaration)
                    + ", a file named with --catalog or $RELIX_CATALOG_PATH; change it there");
        }
        return level;
    }

    /** Refuses a declaration that shares its file with others. */
    private static void requireAlone(Declaration declaration, Noun noun, String verb, String instead) {
        if (declaration.file().statements().size() > 1) {
            throw new CommandFailure(ExitCode.USAGE, where(declaration) + " declares " + declaration.name()
                    + " beside other declarations, which relix " + noun.word() + " does not " + verb
                    + " for you; " + instead);
        }
    }

    /** {@code FILE:LINE}, as {@code relix catalog where} gives a declaration's place. */
    private static String where(Declaration declaration) {
        return declaration.file().path() + ":" + declaration.statement().location().line();
    }
}
