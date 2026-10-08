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
import com.darkcollective.relix.cli.io.RowSink;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

import java.util.concurrent.Callable;

/**
 * {@code relix completion SHELL}: prints a completion script for bash, zsh, fish or
 * PowerShell, generated from the command's own model.
 */
@Command(name = "completion",
        mixinStandardHelpOptions = true,
        sortOptions = false,
        usageHelpAutoWidth = true,
        description = {
            "Prints a completion script for SHELL: bash, zsh, fish or powershell.",
            "  bash:       source <(relix completion bash)",
            "  zsh:        source <(relix completion zsh)",
            "  fish:       relix completion fish > ~/.config/fish/completions/relix.fish",
            "  powershell: relix completion powershell | Out-String | Invoke-Expression"})
final class CompletionCommand implements Callable<Integer> {

    @ParentCommand
    RelixCommand root;

    @Spec
    CommandSpec spec;

    @Parameters(paramLabel = "SHELL", description = "bash, zsh, fish or powershell.")
    String shell;

    @Override
    public Integer call() {
        Completion.Shell chosen = Completion.Shell.of(shell);
        if (chosen == null) {
            throw new CommandFailure(ExitCode.USAGE, "'" + shell + "' is not one of bash, zsh, fish, powershell");
        }
        new RowSink(root.host().out(), false).text(Completion.script(chosen, spec.root().commandLine()));
        return ExitCode.SUCCESS.status();
    }
}
