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
import com.darkcollective.relix.cli.command.DeclarationSpec.Noun;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

import java.util.concurrent.Callable;

/**
 * {@code relix source}: declares, lists, changes, removes and tests the catalog's sources,
 * the relations it reads from files, databases and APIs, from flags rather than a catalog
 * file written by hand. Views and {@code def}s have no command: they are Relix code, and an
 * editor is the tool for code.
 */
@Command(name = "source",
        mixinStandardHelpOptions = true,
        sortOptions = false,
        usageHelpAutoWidth = true,
        description = {
            "Declares, lists, changes, removes and tests sources: the relations the catalog",
            "reads from files, databases and APIs. Each add writes one declaration to a file",
            "of its own, catalog/00-source-NAME.relix, as relix fmt prints it."},
        subcommands = {
            SourceCommand.Add.class, SourceCommand.Ls.class, SourceCommand.Edit.class,
            SourceCommand.Rm.class, SourceCommand.Test.class})
final class SourceCommand implements Callable<Integer> {

    @ParentCommand
    RelixCommand root;

    @Override
    public Integer call() {
        throw new CommandFailure(ExitCode.USAGE, null);
    }

    /** {@code relix source add}. */
    @Command(name = "add",
            mixinStandardHelpOptions = true,
            sortOptions = false,
            usageHelpAutoWidth = true,
            description = {
                "Declares the source NAME, from KIND, a csv or json file at LOCATION, a database,",
                "an http API or a generator, or from a connection the catalog declares. Every",
                "other option of the declaration is a flag: --OPTION=VALUE, or --OPTION for true.",
                "true, false, a number, and a { … } or [ … ] block are written as they are;",
                "anything else is a string. A secret is written as a $${NAME} placeholder, never",
                "its value. A name an outer level declares is allowed, and warned about.",
                "Writes the nearest project's catalog, or with --global ~/.relix's."},
            exitCodeListHeading = "%nExit status:%n",
            exitCodeList = {
                " 0:success",
                " 2:a required option is missing, a value is a secret, or NAME is declared here already",
                " 5:--infer-schema cannot read the data, or the file cannot be written"})
    static final class Add extends DeclarationVerbs.Add {
        @ParentCommand
        SourceCommand source;

        @Override
        RelixCommand root() {
            return source.root;
        }

        @Override
        Noun noun() {
            return Noun.SOURCE;
        }
    }

    /** {@code relix source ls}. */
    @Command(name = "ls",
            mixinStandardHelpOptions = true,
            sortOptions = false,
            usageHelpAutoWidth = true,
            description = {
                "Lists the sources the catalog declares, each as the declaration that won: name,",
                "kind (csv, json, database, http, generator or the connection's name), location",
                "(the file, table or URL it reads) and file."})
    static final class Ls extends DeclarationVerbs.Ls {
        @ParentCommand
        SourceCommand source;

        @Override
        RelixCommand root() {
            return source.root;
        }

        @Override
        Noun noun() {
            return Noun.SOURCE;
        }
    }

    /** {@code relix source edit}. */
    @Command(name = "edit",
            mixinStandardHelpOptions = true,
            sortOptions = false,
            usageHelpAutoWidth = true,
            description = {
                "Changes the source NAME: sets each --OPTION=VALUE, removes each --unset OPTION,",
                "and with --location reads another file. Only a declaration alone in its file is",
                "changed; one beside others is reported, with --editor to open it at its line."},
            exitCodeListHeading = "%nExit status:%n",
            exitCodeList = {
                " 0:success",
                " 2:no source NAME, it shares its file with other declarations, or nothing to change",
                " 5:--infer-schema cannot read the data, or the file cannot be written"})
    static final class Edit extends DeclarationVerbs.Edit {
        @ParentCommand
        SourceCommand source;

        @Option(names = "--location", paramLabel = "LOCATION",
                description = "Read the file at LOCATION, relative to the working directory.")
        String location;

        @Override
        String location() {
            return location;
        }

        @Override
        RelixCommand root() {
            return source.root;
        }

        @Override
        Noun noun() {
            return Noun.SOURCE;
        }
    }

    /** {@code relix source rm}. */
    @Command(name = "rm",
            mixinStandardHelpOptions = true,
            sortOptions = false,
            usageHelpAutoWidth = true,
            description = {
                "Removes the source NAME: the file that declares it, at the nearest level that",
                "does. Refused while another declaration reads NAME, unless --force; a",
                "declaration beside others in its file is reported, not touched. Says when a",
                "farther declaration of NAME now shows through."},
            exitCodeListHeading = "%nExit status:%n",
            exitCodeList = {
                " 0:success",
                " 2:no source NAME, another declaration reads it, or it shares its file"})
    static final class Rm extends DeclarationVerbs.Rm {
        @ParentCommand
        SourceCommand source;

        @Override
        RelixCommand root() {
            return source.root;
        }

        @Override
        Noun noun() {
            return Noun.SOURCE;
        }
    }

    /** {@code relix source test}. */
    @Command(name = "test",
            mixinStandardHelpOptions = true,
            sortOptions = false,
            usageHelpAutoWidth = true,
            description = {
                "Reads one row of each source NAME (default: every one) under the active",
                "profile, and lists name, status and problem: whether its driver or connector is",
                "installed, every placeholder has a value, its location can be reached, and its",
                "declared schema matches what was read."},
            exitCodeListHeading = "%nExit status:%n",
            exitCodeList = {" 0:every source passed", " 2:no source NAME", " 5:a source failed"})
    static final class Test extends DeclarationVerbs.Test {
        @ParentCommand
        SourceCommand source;

        @Override
        RelixCommand root() {
            return source.root;
        }

        @Override
        Noun noun() {
            return Noun.SOURCE;
        }
    }
}
