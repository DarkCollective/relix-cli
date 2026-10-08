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
import com.darkcollective.relix.cli.catalog.Trust;
import com.darkcollective.relix.cli.config.RelixDirectories;
import com.darkcollective.relix.symbol.ScalarType;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * {@code relix catalog trust}: records that a project's {@code .relix/} may be loaded, as
 * its files are now (design §5.4).
 */
@Command(name = "trust",
        mixinStandardHelpOptions = true,
        sortOptions = false,
        usageHelpAutoWidth = true,
        description = {
            "Trusts the .relix/ directory of DIR (default: the working directory), as its",
            "files are now: a project's catalog, relixrc and profiles.json are loaded only",
            "once it is trusted, and changing, adding or removing a file there means trusting",
            "it again. ~/.relix, --catalog files and the script itself are always trusted;",
            "RELIX_TRUST_ALL=1 trusts everything, for a CI container."})
final class TrustCommand implements Callable<Integer> {

    @ParentCommand
    CatalogCommand catalog;

    @Parameters(paramLabel = "DIR", arity = "0..1",
            description = "The project to trust, or its .relix/ directory.")
    Path directory;

    @Option(names = "--list",
            description = "List the trusted directories and whether each has changed since.")
    boolean list;

    @Option(names = "--revoke", paramLabel = "DIR",
            description = "Stop trusting DIR.")
    Path revoke;

    @Mixin
    FormatOptions formatting;

    @Override
    public Integer call() {
        if ((list ? 1 : 0) + (revoke != null ? 1 : 0) + (directory != null ? 1 : 0) > 1) {
            throw new CommandFailure(ExitCode.USAGE, "give one of DIR, --list and --revoke DIR");
        }
        Invocation invocation = catalog.root.invocation(false);
        Trust trust = invocation.trust();
        if (list) {
            Listing listing = new Listing("directory", ScalarType.STRING, "state", ScalarType.STRING);
            for (Path level : trust.directories()) {
                listing.add(level.getParent().toString(), trust.state(level).label());
            }
            listing.print(invocation, invocation.format(formatting), "trusted");
            return ExitCode.SUCCESS.status();
        }
        if (revoke != null) {
            Path level = level(invocation.directory().resolve(revoke).normalize());
            if (!trust.revoke(level)) {
                throw new CommandFailure(ExitCode.USAGE, revoke + " is not trusted");
            }
            invocation.reporter().notice("no longer trusting " + level);
            return ExitCode.SUCCESS.status();
        }
        Path given = directory == null ? invocation.directory() : invocation.directory().resolve(directory).normalize();
        Path level = level(given);
        if (!Files.isDirectory(level)) {
            throw new CommandFailure(ExitCode.USAGE, (directory == null ? given : directory)
                    + " has no " + RelixDirectories.NAME + " directory to trust");
        }
        if (level.equals(invocation.host().home().resolve(RelixDirectories.NAME))) {
            invocation.reporter().notice(level + " is your own, and always trusted");
            return ExitCode.SUCCESS.status();
        }
        trust.trust(level);
        invocation.reporter().notice("trusting " + level);
        return ExitCode.SUCCESS.status();
    }

    /** The {@code .relix/} directory a path names: itself, or the one inside it. */
    private static Path level(Path path) {
        Path name = path.getFileName();
        return name != null && name.toString().equals(RelixDirectories.NAME) ? path : path.resolve(RelixDirectories.NAME);
    }
}
