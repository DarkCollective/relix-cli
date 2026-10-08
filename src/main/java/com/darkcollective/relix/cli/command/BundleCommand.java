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
import com.darkcollective.relix.cli.io.RowSink;
import com.darkcollective.relix.cli.report.QueryBundleJson;
import com.darkcollective.relix.embed.Diagnostic;
import com.darkcollective.relix.embed.Relation;
import com.darkcollective.relix.embed.RelixException;
import com.darkcollective.relix.semantic.SemanticError;
import picocli.CommandLine.Command;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code relix bundle}: the playground's JSON bundle of each script, its diagnostics and,
 * per query, the logical plan, the rewrites, the physical plan and the planner's events
 * (design §3).
 *
 * <p>A script that does not analyse still has a bundle, holding its diagnostics: a tool
 * reading it wants to know what is wrong, and the exit status is still 3. A query whose
 * physical plan cannot be made is bundled without one, and the reason goes to standard
 * error.
 */
@Command(name = "bundle",
        mixinStandardHelpOptions = true,
        sortOptions = false,
        usageHelpAutoWidth = true,
        description = {
            "Prints each script's JSON bundle: diagnostics and, per query, the logical plan,",
            "the rewrites, the physical plan and the planner's events. A script that does",
            "not analyse still has one, holding its diagnostics."})
final class BundleCommand extends ScriptCommand {

    @Override
    ScriptRunner.View view(Invocation invocation) {
        RowSink out = stdout(invocation);
        Reporter reporter = invocation.reporter();
        return new ScriptRunner.View() {
            @Override
            public ExitCode show(ScriptRunner.Script script) {
                List<Relation> queries = queries(script);
                List<QueryBundleJson.Query> bundled = new ArrayList<>();
                for (Relation relation : queries) {
                    Relation optimized = relation.optimized();
                    String label = relation.label().orElse("<expression>");
                    String physical;
                    try {
                        physical = optimized.explainJson();
                    } catch (RelixException e) {
                        reporter.error(script.source().name() + ": " + label + ": no physical plan: "
                                + e.getMessage());
                        physical = null;
                    }
                    bundled.add(new QueryBundleJson.Query(label, relation.renderJson(),
                            optimized.rewrites(), physical, optimized.events()));
                }
                List<SemanticError> diagnostics = script.diagnostics().stream()
                        .map(d -> error(script, d))
                        .toList();
                out.text(QueryBundleJson.generate(queries.isEmpty()
                        ? script.session().model() : queries.getFirst().model(), diagnostics, bundled) + "\n");
                return ExitCode.SUCCESS;
            }

            @Override
            public boolean showsBroken() {
                return true;
            }
        };
    }

    /** A broken script's queries cannot all be built; its bundle then holds none. */
    private static List<Relation> queries(ScriptRunner.Script script) {
        if (!script.broken()) {
            return script.queries();
        }
        try {
            return script.queries();
        } catch (RelixException e) {
            return List.of();
        }
    }

    /** A diagnostic back in the analyser's own shape, placed where the user wrote it. */
    private static SemanticError error(ScriptRunner.Script script, Diagnostic diagnostic) {
        Reporter.Place place = script.source().place(diagnostic.location());
        return new SemanticError(place.file(), place.line(), place.column(), diagnostic.message(),
                diagnostic.severity());
    }
}
