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
import com.darkcollective.relix.cli.io.Reporter;
import com.darkcollective.relix.embed.Diagnostic;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.ObjectWriteContext;
import tools.jackson.core.json.JsonFactory;

import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code relix check}: analyses scripts without running them, as a lint pass (design
 * §3.4, §3.5). Nothing goes to standard output: the diagnostics go to standard error, or,
 * with {@code --json}, to standard output as one document.
 */
@Command(name = "check",
        mixinStandardHelpOptions = true,
        sortOptions = false,
        usageHelpAutoWidth = true,
        description = {
            "Analyses scripts without running them, and reports what is wrong with them",
            "on standard error as FILE:LINE:COL: severity: message.",
            "  find . -name '*.relix' -print0 | xargs -0 relix check"},
        exitCodeListHeading = "%nExit status:%n",
        exitCodeList = {" 0:every script analyses", " 3:a script does not parse or analyse"})
final class CheckCommand extends ScriptCommand {

    private static final JsonFactory JSON = new JsonFactory();

    @Option(names = "--json",
            description = "Write the diagnostics to standard output as one JSON document instead.")
    boolean json;

    @Override
    ScriptRunner.View view(Invocation invocation) {
        return new ScriptRunner.View() {

            private final List<Placed> found = new ArrayList<>();

            @Override
            public ExitCode show(ScriptRunner.Script script) {
                for (Diagnostic diagnostic : script.diagnostics()) {
                    found.add(new Placed(script.source().place(diagnostic.location()), diagnostic));
                }
                return ExitCode.SUCCESS;
            }

            @Override
            public boolean showsBroken() {
                return true;
            }

            @Override
            public boolean reportsDiagnostics() {
                return !json;
            }

            @Override
            public ExitCode finish(ExitCode result) {
                if (json) {
                    stdout(invocation).text(document(found));
                }
                return result;
            }
        };
    }

    /** A diagnostic and where it falls, in the terms the user wrote. */
    private record Placed(Reporter.Place place, Diagnostic diagnostic) {
    }

    /**
     * {@code {"diagnostics": [{"file", "line", "column", "severity", "message"}, …]}}, the
     * line and column 0 when unknown.
     */
    private static String document(List<Placed> found) {
        StringWriter out = new StringWriter();
        try (JsonGenerator w = JSON.createGenerator(ObjectWriteContext.empty(), out)) {
            w.writeStartObject();
            w.writeName("diagnostics").writeStartArray();
            for (Placed placed : found) {
                w.writeStartObject()
                        .writeName("file").writeString(placed.place().file())
                        .writeName("line").writeNumber(placed.place().line())
                        .writeName("column").writeNumber(placed.place().column())
                        .writeName("severity").writeString(
                                placed.diagnostic().severity().name().toLowerCase(Locale.ROOT))
                        .writeName("message").writeString(placed.diagnostic().message())
                        .writeEndObject();
            }
            w.writeEndArray();
            w.writeEndObject();
        }
        return out + "\n";
    }
}
