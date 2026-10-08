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

import com.darkcollective.relix.cli.CommandFailure;
import com.darkcollective.relix.cli.ExitCode;
import com.darkcollective.relix.cli.catalog.Catalog;
import com.darkcollective.relix.cli.catalog.CatalogFile;
import com.darkcollective.relix.cli.catalog.Declaration;
import com.darkcollective.relix.embed.Relix;
import com.darkcollective.relix.embed.RelixException;
import com.darkcollective.relix.embed.Tuple;
import com.darkcollective.relix.symbol.ScalarType;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * {@code relix catalog}: the declarations the {@code .relix/} directories supply, and which
 * of those directories are trusted (design §5.3, §5.4).
 *
 * <p>Every listing is rows, written as a run writes its results: a table on a terminal,
 * tsv in a pipe, or whatever {@code -o} names. {@code ls} and {@code schema} are queries
 * over the engine's own introspection relations, {@code relix.relations} and
 * {@code relix.columns}, run in a session holding the catalog and nothing else.
 */
@Command(name = "catalog",
        mixinStandardHelpOptions = true,
        sortOptions = false,
        usageHelpAutoWidth = true,
        description = {
            "Inspects the catalog: the declarations a run takes from the .relix/ directories",
            "from ~/.relix down to the working directory, and from --catalog files.",
            "A project's .relix/ is loaded only once it is trusted (relix catalog trust)."},
        subcommands = {
            CatalogCommand.Files.class, CatalogCommand.Ls.class, CatalogCommand.Schema.class,
            CatalogCommand.Where.class, TrustCommand.class})
final class CatalogCommand implements Callable<Integer> {

    @ParentCommand
    RelixCommand root;

    @Override
    public Integer call() {
        throw new CommandFailure(ExitCode.USAGE, null);
    }

    /**
     * Runs {@code use} over a session holding the catalog and nothing else.
     *
     * @throws CommandFailure with {@link ExitCode#ANALYSIS} when the catalog does not analyse
     */
    private static <T> T inSession(Invocation invocation, Function<Relix, T> use) {
        try (Relix session = invocation.session(invocation.directory()).build()) {
            invocation.catalog().define(session);
            return use.apply(session);
        } catch (RelixException e) {
            throw new CommandFailure(ExitCode.of(e), "the catalog does not analyse: " + e.getMessage());
        }
    }

    private static List<Tuple> rows(Relix session, String expression) {
        try (Stream<Tuple> rows = session.relation(expression).stream()) {
            return rows.toList();
        }
    }

    private static CommandFailure unknown(String name) {
        return new CommandFailure(ExitCode.ANALYSIS, "the catalog declares no '" + name + "'");
    }

    /** {@code relix catalog files}. */
    @Command(name = "files",
            mixinStandardHelpOptions = true,
            sortOptions = false,
            usageHelpAutoWidth = true,
            description = {
                "Lists every catalog file, in the order they load, with the names each declares",
                "and whether it is trusted. An untrusted file is listed but not loaded."})
    static final class Files implements Callable<Integer> {

        @ParentCommand
        CatalogCommand catalog;

        @Mixin
        FormatOptions formatting;

        @Override
        public Integer call() {
            Invocation invocation = catalog.root.invocation(true);
            Listing listing = new Listing("file", ScalarType.STRING, "trusted", ScalarType.BOOLEAN,
                    "declares", ScalarType.STRING);
            for (CatalogFile file : invocation.catalog().files()) {
                listing.add(file.path().toString(), file.trusted(), file.declarations().stream()
                        .map(Declaration::name).distinct().collect(Collectors.joining(" ")));
            }
            listing.print(invocation, invocation.format(formatting), "files");
            return ExitCode.SUCCESS.status();
        }
    }

    /** {@code relix catalog ls}. */
    @Command(name = "ls",
            mixinStandardHelpOptions = true,
            sortOptions = false,
            usageHelpAutoWidth = true,
            description = {
                "Lists the relations the catalog declares: name, kind, arity and the file whose",
                "declaration won. kind is the engine's: SRC a source, INL an inline table,",
                "QR a view, DB a connection's table."})
    static final class Ls implements Callable<Integer> {

        @ParentCommand
        CatalogCommand catalog;

        @Mixin
        FormatOptions formatting;

        @Override
        public Integer call() {
            Invocation invocation = catalog.root.invocation(true);
            Map<String, Declaration> declared = invocation.catalog().relations();
            List<Tuple> relations = inSession(invocation, session -> rows(session,
                    "τ name (π name, kind, arity (relix.relations ⟕ name = relation"
                            + " (γ relation, COUNT(*) → arity (relix.columns))))"));
            Listing listing = new Listing("name", ScalarType.STRING, "kind", ScalarType.STRING,
                    "arity", ScalarType.NUMBER, "file", ScalarType.STRING);
            for (Tuple relation : relations) {
                Declaration declaration = declared.get(relation.string("name"));
                listing.add(relation.string("name"), relation.string("kind"), relation.longValue("arity"),
                        declaration == null ? null : declaration.file().path().toString());
            }
            listing.print(invocation, invocation.format(formatting), "relations");
            return ExitCode.SUCCESS.status();
        }
    }

    /** {@code relix catalog schema NAME}. */
    @Command(name = "schema",
            mixinStandardHelpOptions = true,
            sortOptions = false,
            usageHelpAutoWidth = true,
            description = "Lists the columns of the relation NAME and their types, in order.",
            exitCodeListHeading = "%nExit status:%n",
            exitCodeList = {" 0:success", " 3:the catalog declares no relation NAME, or does not analyse"})
    static final class Schema implements Callable<Integer> {

        @ParentCommand
        CatalogCommand catalog;

        @Parameters(paramLabel = "NAME", description = "A relation the catalog declares.")
        String name;

        @Mixin
        FormatOptions formatting;

        @Override
        public Integer call() {
            Invocation invocation = catalog.root.invocation(true);
            List<Tuple> columns = inSession(invocation, session -> {
                boolean known = rows(session, "π name (relix.relations)").stream()
                        .anyMatch(r -> name.equals(r.string("name")));
                if (!known) {
                    throw unknown(name);
                }
                return rows(session, "τ ordinal (relix.columns)").stream()
                        .filter(c -> name.equals(c.string("relation")))
                        .toList();
            });
            Listing listing = new Listing("column", ScalarType.STRING, "type", ScalarType.STRING);
            for (Tuple column : columns) {
                listing.add(column.string("column"), column.string("type"));
            }
            listing.print(invocation, invocation.format(formatting), name);
            return ExitCode.SUCCESS.status();
        }
    }

    /** {@code relix catalog where NAME}. */
    @Command(name = "where",
            mixinStandardHelpOptions = true,
            sortOptions = false,
            usageHelpAutoWidth = true,
            description = {
                "Lists every declaration of NAME, nearest first: the one that won, then the ones",
                "it shadowed. A declaration in an untrusted directory is listed as untrusted."},
            exitCodeListHeading = "%nExit status:%n",
            exitCodeList = {" 0:success", " 3:the catalog declares no NAME"})
    static final class Where implements Callable<Integer> {

        @ParentCommand
        CatalogCommand catalog;

        @Parameters(paramLabel = "NAME", description = "A name the catalog declares.")
        String name;

        @Mixin
        FormatOptions formatting;

        @Override
        public Integer call() {
            Invocation invocation = catalog.root.invocation(true);
            Catalog declared = invocation.catalog();
            List<Declaration> declarations = declared.declarations(name);
            if (declarations.isEmpty()) {
                throw unknown(name);
            }
            Listing listing = new Listing("file", ScalarType.STRING, "line", ScalarType.NUMBER,
                    "kind", ScalarType.STRING, "state", ScalarType.STRING);
            for (Declaration declaration : declarations) {
                String state = !declaration.file().trusted() ? "untrusted"
                        : declared.wins(declaration) ? "wins" : "shadowed";
                listing.add(declaration.file().path().toString(), declaration.statement().location().line(),
                        declaration.kind().label(), state);
            }
            listing.print(invocation, invocation.format(formatting), name);
            return ExitCode.SUCCESS.status();
        }
    }
}
