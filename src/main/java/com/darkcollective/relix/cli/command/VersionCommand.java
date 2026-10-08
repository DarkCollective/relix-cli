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

import com.darkcollective.relix.cli.ExitCode;
import com.darkcollective.relix.cli.Main;
import com.darkcollective.relix.cli.io.RowSink;
import picocli.CommandLine.Command;
import picocli.CommandLine.ParentCommand;

import java.util.concurrent.Callable;

/**
 * {@code relix version}: the command's version and the engine's, as {@code relix --version}
 * prints them.
 */
@Command(name = "version",
        mixinStandardHelpOptions = true,
        usageHelpAutoWidth = true,
        description = "Prints the versions of relix and of the Relix engine it runs.")
final class VersionCommand implements Callable<Integer> {

    @ParentCommand
    RelixCommand root;

    @Override
    public Integer call() {
        new RowSink(root.host().out(), false).text(Main.versionLine() + "\n");
        return ExitCode.SUCCESS.status();
    }
}
