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
import picocli.CommandLine.Command;
import picocli.CommandLine.ParentCommand;

import java.util.concurrent.Callable;

/**
 * {@code relix catalog}: the declarations the {@code .relix/} directories supply, and which
 * of those directories are trusted (design §5.3, §5.4).
 */
@Command(name = "catalog",
        mixinStandardHelpOptions = true,
        sortOptions = false,
        usageHelpAutoWidth = true,
        description = {
            "Inspects the catalog: the declarations a run takes from the .relix/ directories",
            "from ~/.relix down to the working directory, and from --catalog files.",
            "A project's .relix/ is loaded only once it is trusted (relix catalog trust)."},
        subcommands = TrustCommand.class)
final class CatalogCommand implements Callable<Integer> {

    @ParentCommand
    RelixCommand root;

    @Override
    public Integer call() {
        throw new CommandFailure(ExitCode.USAGE, null);
    }
}
