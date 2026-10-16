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
import picocli.CommandLine.ParentCommand;

import java.util.concurrent.Callable;

/**
 * {@code relix connection}: declares, lists, changes, removes and tests the catalog's
 * connections, what several sources share (a database, an authenticated API), from flags
 * rather than a catalog file written by hand.
 */
@Command(name = "connection",
        mixinStandardHelpOptions = true,
        sortOptions = false,
        usageHelpAutoWidth = true,
        description = {
            "Declares, lists, changes, removes and tests connections: what several sources",
            "share, such as a database and its credentials. Each add writes one declaration",
            "to a file of its own, catalog/00-connection-NAME.relix, as relix fmt prints it."},
        subcommands = {
            ConnectionCommand.Add.class, ConnectionCommand.Ls.class, ConnectionCommand.Edit.class,
            ConnectionCommand.Rm.class, ConnectionCommand.Test.class})
final class ConnectionCommand implements Callable<Integer> {

    @ParentCommand
    RelixCommand root;

    @Override
    public Integer call() {
        throw new CommandFailure(ExitCode.USAGE, null);
    }

    /** {@code relix connection add}. */
    @Command(name = "add",
            mixinStandardHelpOptions = true,
            sortOptions = false,
            usageHelpAutoWidth = true,
            description = {
                "Declares the connection NAME of KIND: database (jdbc), a connector's name such as",
                "mongodb, or a file kind (csv, log, gedcom) whose path is LOCATION. Every option of",
                "the declaration is a flag, --OPTION=VALUE, its value a string. A secret is",
                "written as a $${NAME} placeholder, never its value. A name an outer level",
                "declares is allowed, and warned about. Writes the nearest project's catalog,",
                "or with --global ~/.relix's."},
            exitCodeListHeading = "%nExit status:%n",
            exitCodeList = {
                " 0:success",
                " 2:a required option is missing, a value is a secret, or NAME is declared here already",
                " 5:the file cannot be written"})
    static final class Add extends DeclarationVerbs.Add {
        @ParentCommand
        ConnectionCommand connection;

        @Override
        RelixCommand root() {
            return connection.root;
        }

        @Override
        Noun noun() {
            return Noun.CONNECTION;
        }
    }

    /** {@code relix connection ls}. */
    @Command(name = "ls",
            mixinStandardHelpOptions = true,
            sortOptions = false,
            usageHelpAutoWidth = true,
            description = {
                "Lists the connections the catalog declares, each as the declaration that won:",
                "name, kind (jdbc for a database), location (its url or path) and file."})
    static final class Ls extends DeclarationVerbs.Ls {
        @ParentCommand
        ConnectionCommand connection;

        @Override
        RelixCommand root() {
            return connection.root;
        }

        @Override
        Noun noun() {
            return Noun.CONNECTION;
        }
    }

    /** {@code relix connection edit}. */
    @Command(name = "edit",
            mixinStandardHelpOptions = true,
            sortOptions = false,
            usageHelpAutoWidth = true,
            description = {
                "Changes the connection NAME: sets each --OPTION=VALUE and removes each --unset",
                "OPTION. Only a declaration alone in its file is changed; one beside others is",
                "reported, with --editor to open it at its line."},
            exitCodeListHeading = "%nExit status:%n",
            exitCodeList = {
                " 0:success",
                " 2:no connection NAME, it shares its file with other declarations, or nothing to change",
                " 5:the file cannot be written"})
    static final class Edit extends DeclarationVerbs.Edit {
        @ParentCommand
        ConnectionCommand connection;

        @Override
        RelixCommand root() {
            return connection.root;
        }

        @Override
        Noun noun() {
            return Noun.CONNECTION;
        }
    }

    /** {@code relix connection rm}. */
    @Command(name = "rm",
            mixinStandardHelpOptions = true,
            sortOptions = false,
            usageHelpAutoWidth = true,
            description = {
                "Removes the connection NAME: the file that declares it, at the nearest level that",
                "does. Refused while a source or view reads NAME, unless --force; a",
                "declaration beside others in its file is reported, not touched. Says when a",
                "farther declaration of NAME now shows through."},
            exitCodeListHeading = "%nExit status:%n",
            exitCodeList = {
                " 0:success",
                " 2:no connection NAME, another declaration reads it, or it shares its file"})
    static final class Rm extends DeclarationVerbs.Rm {
        @ParentCommand
        ConnectionCommand connection;

        @Override
        RelixCommand root() {
            return connection.root;
        }

        @Override
        Noun noun() {
            return Noun.CONNECTION;
        }
    }

    /** {@code relix connection test}. */
    @Command(name = "test",
            mixinStandardHelpOptions = true,
            sortOptions = false,
            usageHelpAutoWidth = true,
            description = {
                "Opens and closes each connection NAME (default: every one) under the active",
                "profile, and lists name, status and problem: whether every placeholder has a",
                "value, its driver is installed and the database can be reached and logged into.",
                "Only a database connection is opened; any other is listed as untested."},
            exitCodeListHeading = "%nExit status:%n",
            exitCodeList = {" 0:every connection passed or was untested", " 2:no connection NAME",
                " 5:a connection failed"})
    static final class Test extends DeclarationVerbs.Test {
        @ParentCommand
        ConnectionCommand connection;

        @Override
        RelixCommand root() {
            return connection.root;
        }

        @Override
        Noun noun() {
            return Noun.CONNECTION;
        }
    }
}
