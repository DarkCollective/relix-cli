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

import com.darkcollective.relix.cli.io.RowSink;
import com.darkcollective.relix.cli.io.ScriptSource;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.ParentCommand;

import java.util.List;
import java.util.concurrent.Callable;

/**
 * A command that shows something of each script it reads other than its rows: a plan,
 * a rewrite, a report (design §3). The scripts come and are analysed as {@code run}'s
 * do, with the catalog and the {@code -i} inputs; what is shown is the command's
 * {@link ScriptRunner.View}.
 */
abstract class ScriptCommand implements Callable<Integer> {

    @ParentCommand
    RelixCommand root;

    @Mixin
    ScriptOptions scripts;

    @Mixin
    InputOptions inputs;

    @Override
    public Integer call() {
        Invocation invocation = root.invocation(true);
        InputOptions.Inputs bound = inputs.settle(invocation.directory(), invocation.remote());
        ScriptRunner.View view = view(invocation);
        List<ScriptSource> sources = root.sources(invocation, scripts, bound);
        return root.runner(invocation, bound).run(sources, view).status();
    }

    /**
     * What this command shows of each script. Its options are checked here, before any
     * script is read.
     *
     * @param invocation the run
     * @return the view
     */
    abstract ScriptRunner.View view(Invocation invocation);

    /**
     * Standard output, for a whole text at a time.
     *
     * @param invocation the run
     * @return the sink
     */
    static RowSink stdout(Invocation invocation) {
        return new RowSink(invocation.host().out(), false);
    }
}
