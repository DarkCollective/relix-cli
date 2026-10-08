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
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

import java.util.concurrent.Callable;

/**
 * {@code relix version}: the command's version and the engine's, printed by the same
 * version help as {@code relix --version}, so the two are the same text, line ending
 * included.
 */
@Command(name = "version",
        mixinStandardHelpOptions = true,
        usageHelpAutoWidth = true,
        description = "Prints the versions of relix and of the Relix engine it runs.")
final class VersionCommand implements Callable<Integer> {

    @Spec
    CommandSpec spec;

    @Override
    public Integer call() {
        CommandLine root = spec.root().commandLine();
        root.printVersionHelp(root.getOut());
        return ExitCode.SUCCESS.status();
    }
}
