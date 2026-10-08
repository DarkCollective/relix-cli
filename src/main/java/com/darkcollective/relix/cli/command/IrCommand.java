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
import com.darkcollective.relix.cli.io.RowSink;
import com.darkcollective.relix.embed.Relation;
import picocli.CommandLine.Command;

import java.util.List;

/**
 * {@code relix ir}: the IR report of each script, the symbols, views and queries the
 * analyser made of it, with their headings (design §3).
 */
@Command(name = "ir",
        mixinStandardHelpOptions = true,
        sortOptions = false,
        usageHelpAutoWidth = true,
        description = "Prints the IR report: the symbols, views and queries the analyser made of each script.")
final class IrCommand extends ScriptCommand {

    @Override
    ScriptRunner.View view(Invocation invocation) {
        RowSink out = stdout(invocation);
        return script -> {
            // A relation's model is the whole script, its queries included; the session's
            // is its declarations alone.
            List<Relation> queries = script.queries();
            out.text((queries.isEmpty() ? script.session().model() : queries.getFirst().model()).ir());
            return ExitCode.SUCCESS;
        };
    }
}
