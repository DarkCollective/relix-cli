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

import com.darkcollective.relix.cli.Main;
import com.darkcollective.relix.cli.config.Relixrc;
import com.darkcollective.relix.cli.io.Host;
import com.darkcollective.relix.cli.io.Interruption;
import com.darkcollective.relix.cli.io.ScriptSource;
import picocli.CommandLine.Command;
import picocli.CommandLine.IVersionProvider;
import picocli.CommandLine.Mixin;

import java.util.Objects;
import java.util.concurrent.Callable;

/**
 * {@code relix}: the root command, which runs scripts when no other command is named.
 *
 * <p>{@code relix x.relix} and {@code relix run x.relix} are the same run; {@code run} is
 * the default command (design §3).
 */
@Command(name = "relix",
        mixinStandardHelpOptions = true,
        versionProvider = RelixCommand.Version.class,
        sortOptions = false,
        usageHelpAutoWidth = true,
        description = {
            "Runs Relix relational-algebra scripts as a stage of a Unix pipeline.",
            "Rows go to standard output; everything else goes to standard error.",
            ""},
        subcommands = RunCommand.class,
        exitCodeListHeading = "%nExit status:%n",
        exitCodeList = {
            " 0:success",
            " 1:an assertion failed (--fail-empty, --fail-rows)",
            " 2:usage error (bad option, nothing to run)",
            " 3:the script did not parse or analyse",
            " 4:execution failed (a data error, a limit hit)",
            " 5:environment (a missing driver, a loose profiles.json, a connection refused)",
            "70:internal error in relix",
            "130:interrupted (SIGINT)",
            "141:standard output closed (SIGPIPE)"})
public final class RelixCommand implements Callable<Integer> {

    @Mixin
    GlobalOptions options;

    @Mixin
    ScriptOptions scripts;

    @Mixin
    InputOptions inputs;

    @Mixin
    OutputOptions output;

    private final Host host;
    private final Interruption interruption;

    /**
     * The root command over a host.
     *
     * @param host         the process it runs in
     * @param interruption what an interrupt stops
     */
    public RelixCommand(Host host, Interruption interruption) {
        this.host = Objects.requireNonNull(host, "host");
        this.interruption = Objects.requireNonNull(interruption, "interruption");
    }

    @Override
    public Integer call() {
        return run(scripts, inputs, output);
    }

    /**
     * Runs the scripts {@code scripts} names, with this invocation's global options.
     *
     * @param scripts where the scripts come from
     * @param inputs  the data bound to relation names
     * @param output  where their rows go
     * @return the exit status
     */
    int run(ScriptOptions scripts, InputOptions inputs, OutputOptions output) {
        Invocation invocation = new Invocation(host, options);
        OutputOptions.Output settled = output.settle(host, invocation.relixrc().get(Relixrc.OUTPUT));
        InputOptions.Inputs bound = inputs.settle(invocation.directory(), invocation.remote());
        return new ScriptRunner(invocation, bound, settled, interruption)
                .run(ScriptSource.resolve(scripts.expressions, scripts.files, host, invocation.directory(),
                        bound.readStdin()))
                .status();
    }

    /** The command's and the engine's versions, for {@code --version}. */
    static final class Version implements IVersionProvider {
        @Override
        public String[] getVersion() {
            return new String[] {Main.versionLine()};
        }
    }
}
