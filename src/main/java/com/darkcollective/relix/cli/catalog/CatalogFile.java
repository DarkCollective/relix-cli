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
 * @param trusted    whether it is loaded: it is in {@code ~/.relix} or a trusted directory,
 *                   or was named on the command line. An untrusted file is parsed, so that
 *                   {@code relix catalog} can say what it declares, but never defined.
 */
public record CatalogFile(Path path, Path level, List<Statement> statements, boolean trusted) {

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
     * @param path    the file, absolute
     * @param level   its {@code .relix/} directory, or {@code null}
     * @param trusted whether it is to be loaded
     * @return the parsed file; an untrusted file that does not parse is read as empty,
     *         since nothing of it will be loaded
     * @throws CommandFailure with {@link ExitCode#ANALYSIS} when a trusted file does not
     *                        parse or holds a query, and with {@link ExitCode#ENVIRONMENT} when it cannot
     *                        be read
     */
    static CatalogFile read(Path path, Path level, boolean trusted) {
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
            if (!trusted) {
                return new CatalogFile(path, level, List.of(), false);
            }
            throw new CommandFailure(ExitCode.ANALYSIS, path + ": " + e.getMessage());
        }
        for (Statement statement : trusted ? statements : List.<Statement>of()) {
            if (statement instanceof QueryStatement query) {
                throw new CommandFailure(ExitCode.ANALYSIS, path + ":" + query.location().line()
                        + ": a catalog file declares; a query belongs in a script");
            }
        }
        return new CatalogFile(path, level, statements, trusted);
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
