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
package com.darkcollective.relix.cli.catalog;

import com.darkcollective.relix.cli.CommandFailure;
import com.darkcollective.relix.cli.ExitCode;
import com.darkcollective.relix.embed.Relix;
import com.darkcollective.relix.lang.ast.QueryStatement;
import com.darkcollective.relix.lang.ast.ScriptParseException;
import com.darkcollective.relix.lang.ast.Statement;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One file of the catalog, parsed but not yet defined.
 *
 * <p>Parsing opens nothing and resolves nothing, so a file is parsed whether or not it
 * will be loaded. It is parsed under its absolute path, which is what makes a relative
 * path in one of its declarations resolve against the file's own directory.
 *
 * @param path       the file, absolute
 * @param level      the {@code .relix/} directory it belongs to, or {@code null} for a
 *                   {@code --catalog} or {@code $RELIX_CATALOG_PATH} file
 * @param statements its statements, in order
 */
public record CatalogFile(Path path, Path level, List<Statement> statements) {

    /**
     * A catalog file, checked.
     */
    public CatalogFile {
        Objects.requireNonNull(path, "path");
        statements = List.copyOf(statements);
    }

    /**
     * Reads and parses a catalog file.
     *
     * @param path  the file, absolute
     * @param level its {@code .relix/} directory, or {@code null}
     * @return the parsed file
     * @throws CommandFailure with {@link ExitCode#ANALYSIS} when it does not parse or holds
     *                        a query, and with {@link ExitCode#ENVIRONMENT} when it cannot
     *                        be read
     */
    static CatalogFile read(Path path, Path level) {
        String text;
        try {
            text = Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new CommandFailure(ExitCode.ENVIRONMENT, path + ": cannot read: " + e.getMessage());
        }
        List<Statement> statements;
        try {
            statements = Relix.parse(text, path.toString()).statements();
        } catch (ScriptParseException e) {
            throw new CommandFailure(ExitCode.ANALYSIS, path + ": " + e.getMessage());
        }
        for (Statement statement : statements) {
            if (statement instanceof QueryStatement query) {
                throw new CommandFailure(ExitCode.ANALYSIS, path + ":" + query.location().line()
                        + ": a catalog file declares; a query belongs in a script");
            }
        }
        return new CatalogFile(path, level, statements);
    }

    /**
     * The named declarations this file makes, in order.
     *
     * @return its declarations
     */
    public List<Declaration> declarations() {
        List<Declaration> declared = new ArrayList<>();
        for (Statement statement : statements) {
            declared.addAll(Declaration.of(statement, this));
        }
        return declared;
    }
}
