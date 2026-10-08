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
import com.darkcollective.relix.lang.ast.Statement;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The declarations a run starts with, overlaid so that the nearest declaration of each
 * name wins (design §5.2).
 *
 * <p>Files load outermost first: each {@code .relix/} directory's {@code catalog/*.relix},
 * in lexical order within a directory, from {@code ~/.relix} to the working directory's,
 * and then the {@code --catalog} files. A later file is nearer, and its declaration of a
 * name replaces a farther one's — an overlay, not a duplicate-name error. Every file is
 * parsed first, the nearest declaration of each name kept, and the survivors defined into
 * the session together, so a view declared at an outer level reads whichever
 * {@code Orders} is nearest.
 *
 * <p>Defining declares; it opens nothing. No connection is made and no file read until a
 * query references the name.
 */
public final class Catalog {

    /** The directory inside a {@code .relix/} that holds its catalog files. */
    public static final String DIRECTORY = "catalog";

    /** A catalog file's extension. */
    public static final String EXTENSION = ".relix";

    private static final Catalog EMPTY = new Catalog(List.of());

    private final List<CatalogFile> files;
    private final Map<Declaration.Key, Declaration> nearest = new HashMap<>();

    private Catalog(List<CatalogFile> files) {
        this.files = List.copyOf(files);
        for (CatalogFile file : this.files) {
            for (Declaration declaration : file.declarations()) {
                nearest.put(declaration.key(), declaration);
            }
        }
    }

    /**
     * A catalog of nothing, as {@code -N} gives when no {@code --catalog} is named.
     *
     * @return the empty catalog
     */
    public static Catalog empty() {
        return EMPTY;
    }

    /**
     * Reads the catalog of the given directories and files.
     *
     * @param levels the {@code .relix/} directories, outermost first
     * @param named  the {@code --catalog} and {@code $RELIX_CATALOG_PATH} files, farthest
     *               first, absolute
     * @return the catalog
     * @throws CommandFailure with {@link ExitCode#ANALYSIS} when a file does not parse, and
     *                        with {@link ExitCode#ENVIRONMENT} when one cannot be read
     */
    public static Catalog load(List<Path> levels, List<Path> named) {
        List<CatalogFile> files = new ArrayList<>();
        for (Path level : levels) {
            for (Path file : filesOf(level)) {
                files.add(CatalogFile.read(file, level));
            }
        }
        for (Path file : named) {
            files.add(CatalogFile.read(file, null));
        }
        return new Catalog(files);
    }

    /**
     * The catalog files of one {@code .relix/} directory, in lexical order.
     *
     * @param level a {@code .relix/} directory
     * @return its {@code catalog/*.relix} files
     */
    public static List<Path> filesOf(Path level) {
        Path dir = level.resolve(DIRECTORY);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(dir)) {
            return entries
                    .filter(p -> p.getFileName().toString().endsWith(EXTENSION) && Files.isRegularFile(p))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
        } catch (IOException e) {
            throw new CommandFailure(ExitCode.ENVIRONMENT, dir + ": cannot list: " + e.getMessage());
        }
    }

    /**
     * Every file loaded, in load order.
     *
     * @return the files, outermost first
     */
    public List<CatalogFile> files() {
        return files;
    }

    /**
     * Whether this catalog declares nothing at all.
     *
     * @return {@code true} when no file holds a statement
     */
    public boolean isEmpty() {
        return files.stream().allMatch(f -> f.statements().isEmpty());
    }

    /**
     * Every declaration of a name, nearest first: the one that won, then those it shadowed.
     *
     * @param name a name
     * @return its declarations, of any kind; empty when the catalog does not declare it
     */
    public List<Declaration> declarations(String name) {
        List<Declaration> found = new ArrayList<>();
        for (CatalogFile file : files) {
            for (Declaration declaration : file.declarations()) {
                if (declaration.name().equals(name)) {
                    found.add(declaration);
                }
            }
        }
        return found.reversed();
    }

    /**
     * Whether a declaration is the one its name resolves to.
     *
     * @param declaration a declaration of this catalog
     * @return {@code true} when no nearer declaration replaces it
     */
    public boolean wins(Declaration declaration) {
        Declaration winner = nearest.get(declaration.key());
        return winner != null && winner.statement() == declaration.statement();
    }

    /**
     * The relations the catalog declares, each with the declaration that won.
     *
     * @return the winning relation declarations, by name
     */
    public Map<String, Declaration> relations() {
        Map<String, Declaration> relations = new HashMap<>();
        nearest.forEach((key, declaration) -> {
            if (key.space() == Declaration.Space.RELATION) {
                relations.put(key.name(), declaration);
            }
        });
        return relations;
    }

    /**
     * The statements that survive the overlay, in load order: every statement no nearer
     * one replaces, and every statement that names nothing, such as an {@code import}.
     *
     * @return the statements to define
     */
    public List<Statement> survivors() {
        List<Statement> kept = new ArrayList<>();
        for (CatalogFile file : files) {
            for (Statement statement : file.statements()) {
                List<Declaration> declared = Declaration.of(statement, file);
                if (declared.stream().allMatch(this::wins)) {
                    kept.add(statement);
                }
            }
        }
        return kept;
    }

    /**
     * Defines the survivors into a session.
     *
     * @param session the session, before its script
     * @throws com.darkcollective.relix.embed.RelixException when they do not analyse
     */
    public void define(Relix session) {
        List<Statement> kept = survivors();
        if (!kept.isEmpty()) {
            session.define(kept.toArray(Statement[]::new));
        }
    }
}
