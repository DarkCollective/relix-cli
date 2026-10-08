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

import com.darkcollective.relix.lang.ast.AssignmentStatement;
import com.darkcollective.relix.lang.ast.ConnectionDeclaration;
import com.darkcollective.relix.lang.ast.DefRelationStatement;
import com.darkcollective.relix.lang.ast.DefStatement;
import com.darkcollective.relix.lang.ast.InlineTableBody;
import com.darkcollective.relix.lang.ast.RelateStatement;
import com.darkcollective.relix.lang.ast.SourceDeclaration;
import com.darkcollective.relix.lang.ast.Statement;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * One named declaration in a catalog file.
 *
 * @param name      the name it declares
 * @param kind      what it declares
 * @param statement the statement itself
 * @param file      the file it is in
 */
public record Declaration(String name, Kind kind, Statement statement, CatalogFile file) {

    /**
     * A declaration, checked.
     */
    public Declaration {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(statement, "statement");
        Objects.requireNonNull(file, "file");
    }

    /** What a declaration declares, and so which names it competes with. */
    public enum Kind {
        /** {@code source NAME from …}: a relation. */
        SOURCE(Space.RELATION),
        /** {@code NAME := { … }}: a relation. */
        VIEW(Space.RELATION),
        /** {@code NAME := [ … ]}: a relation. */
        TABLE(Space.RELATION),
        /** {@code def NAME(…) := RELATION}: a relation. */
        RELATION_FUNCTION(Space.RELATION),
        /** {@code connection NAME …}. */
        CONNECTION(Space.CONNECTION),
        /** {@code def NAME(…) -> TYPE := …}: a scalar function. */
        FUNCTION(Space.FUNCTION),
        /** {@code relate NAME …}: a relationship between relations. */
        RELATIONSHIP(Space.RELATIONSHIP);

        private final Space space;

        Kind(Space space) {
            this.space = space;
        }

        /**
         * The kind as {@code relix catalog} prints it.
         *
         * @return its lower-case name, such as {@code source} or {@code relation-function}
         */
        public String label() {
            return name().toLowerCase(Locale.ROOT).replace('_', '-');
        }
    }

    /**
     * The names that compete: a nearer declaration replaces a farther one with the same
     * name in the same space. A view and a source share one, so a sub-project can point a
     * view's {@code Orders} at a sample file.
     */
    enum Space { RELATION, CONNECTION, FUNCTION, RELATIONSHIP }

    /**
     * What this declaration competes for.
     *
     * @return its space and name
     */
    Key key() {
        return new Key(kind.space, name);
    }

    /** A name in a space. */
    record Key(Space space, String name) {
    }

    /**
     * The named declarations one statement makes: none for an {@code import} or
     * {@code env}, two for a {@code relate} with an inverse, otherwise one.
     *
     * @param statement a statement of a catalog file
     * @param file      the file
     * @return its declarations
     */
    static List<Declaration> of(Statement statement, CatalogFile file) {
        List<Declaration> declared = new ArrayList<>(2);
        switch (statement) {
            case SourceDeclaration s -> declared.add(new Declaration(s.name(), Kind.SOURCE, s, file));
            case AssignmentStatement s -> declared.add(new Declaration(s.name(),
                    s.body() instanceof InlineTableBody ? Kind.TABLE : Kind.VIEW, s, file));
            case DefRelationStatement s -> declared.add(new Declaration(s.name(), Kind.RELATION_FUNCTION, s, file));
            case ConnectionDeclaration s -> declared.add(new Declaration(s.name(), Kind.CONNECTION, s, file));
            case DefStatement s -> declared.add(new Declaration(s.name(), Kind.FUNCTION, s, file));
            case RelateStatement s -> {
                declared.add(new Declaration(s.name(), Kind.RELATIONSHIP, s, file));
                s.inverseName().ifPresent(inverse ->
                        declared.add(new Declaration(inverse, Kind.RELATIONSHIP, s, file)));
            }
            default -> {
                // An import or an env statement names nothing of its own.
            }
        }
        return declared;
    }
}
