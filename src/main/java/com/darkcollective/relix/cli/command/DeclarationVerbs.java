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

import com.darkcollective.relix.cli.command.DeclarationSpec.Noun;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Unmatched;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * The verbs {@code relix source} and {@code relix connection} share, each declared once
 * here and named under each command by a subclass that says which noun it acts on.
 */
final class DeclarationVerbs {

    private DeclarationVerbs() {
    }

    /** A verb of one of the two commands. */
    abstract static class Verb implements Callable<Integer> {

        /** The root command, for the run's global options. */
        abstract RelixCommand root();

        /** What the verb acts on. */
        abstract Noun noun();
    }

    /** {@code add NAME KIND [LOCATION] [--OPTION=VALUE…]}. */
    abstract static class Add extends Verb {

        @Parameters(index = "0", paramLabel = "NAME", description = "The name to declare.")
        String name;

        @Parameters(index = "1", paramLabel = "KIND",
                description = "What it is from: a kind (csv, json, database, http, generator), or for a source a connection's name.")
        String from;

        @Parameters(index = "2", arity = "0..1", paramLabel = "LOCATION",
                description = "The file it reads, relative to the working directory; for csv and json, and a file connection's path.")
        String location;

        @Option(names = "--global",
                description = "Declare it in ~/.relix, which every directory under your home sees, rather than the nearest project.")
        boolean global;

        @Option(names = "--infer-schema", description = "Read a sample, and write the schema it has.")
        boolean inferSchema;

        @Unmatched
        List<String> flags = new ArrayList<>();

        @Override
        public Integer call() {
            Invocation invocation = inferSchema ? root().invocation(false) : root().writing();
            return Declarations.add(invocation, noun(), name, from, location, flags, global, inferSchema);
        }
    }

    /** {@code ls}. */
    abstract static class Ls extends Verb {

        @Mixin
        FormatOptions formatting;

        @Override
        public Integer call() {
            return Declarations.ls(root().writing(), noun(), formatting);
        }
    }

    /** {@code edit NAME [--OPTION=VALUE…] [--unset=OPTION…] | --editor}. */
    abstract static class Edit extends Verb {

        @Parameters(index = "0", paramLabel = "NAME", description = "The declaration to change.")
        String name;

        @Option(names = "--unset", paramLabel = "OPTION", description = "Remove OPTION. Repeatable.")
        List<String> unset = new ArrayList<>();

        @Option(names = "--infer-schema", description = "Read a sample, and write the schema it has in place of the one there.")
        boolean inferSchema;

        @Option(names = "--editor",
                description = "Open the file in $VISUAL or $EDITOR at the declaration's line instead, wherever it is.")
        boolean editor;

        @Unmatched
        List<String> flags = new ArrayList<>();

        /** The new file a file source reads, for the subclass that offers it. */
        String location() {
            return null;
        }

        @Override
        public Integer call() {
            Invocation invocation = inferSchema ? root().invocation(false) : root().writing();
            return Declarations.edit(invocation, noun(), name, flags, unset, location(), inferSchema, editor);
        }
    }

    /** {@code rm NAME [--force]}. */
    abstract static class Rm extends Verb {

        @Parameters(index = "0", paramLabel = "NAME", description = "The declaration to remove.")
        String name;

        @Option(names = "--force", description = "Remove it though other declarations read it.")
        boolean force;

        @Override
        public Integer call() {
            return Declarations.rm(root().writing(), noun(), name, force);
        }
    }

    /** {@code test [NAME…]}. */
    abstract static class Test extends Verb {

        @Parameters(arity = "0..*", paramLabel = "NAME", description = "The declarations to test (default: all).")
        List<String> names = new ArrayList<>();

        @Mixin
        FormatOptions formatting;

        @Override
        public Integer call() {
            return Declarations.test(root().invocation(true), noun(), names, formatting);
        }
    }
}
