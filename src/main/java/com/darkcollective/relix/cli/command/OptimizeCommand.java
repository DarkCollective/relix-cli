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
import com.darkcollective.relix.cli.io.ScriptText;
import com.darkcollective.relix.cli.report.OptimizationReport;
import com.darkcollective.relix.embed.Relation;
import com.darkcollective.relix.lang.ast.QueryStatement;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code relix optimize}: the script, with each query rewritten as the engine's optimiser
 * rewrites it, so that it works as a filter: {@code relix optimize slow.relix > fast.relix}
 * (design §3).
 *
 * <p>Only the {@code query} statements change, each to {@code query { … };} around its
 * rewritten expression, in which the views it reads are inlined. Every other statement,
 * and every comment, is left as written. The engine writes every rewrite back as Relix
 * that parses (DarkCollective/relix-core#100), so the output is a script that runs. With
 * {@code --report}, what is printed instead is
 * which rules fired, and each query before and after.
 */
@Command(name = "optimize",
        mixinStandardHelpOptions = true,
        sortOptions = false,
        usageHelpAutoWidth = true,
        description = {
            "Prints the script with each query rewritten by the optimiser, so that it works",
            "as a filter: relix optimize slow.relix > fast.relix"})
final class OptimizeCommand extends ScriptCommand {

    @Option(names = "--report",
            description = "Print the rules that fired and each query before and after, instead.")
    boolean report;

    @Override
    ScriptRunner.View view(Invocation invocation) {
        RowSink out = stdout(invocation);
        return script -> {
            List<Relation> queries = script.queries();
            if (report) {
                out.text(OptimizationReport.generate(queries.isEmpty()
                        ? script.session().model() : queries.getFirst().model(), entries(queries)));
            } else {
                out.text(rewritten(script, queries));
            }
            return ExitCode.SUCCESS;
        };
    }

    private static List<OptimizationReport.Entry> entries(List<Relation> queries) {
        List<OptimizationReport.Entry> entries = new ArrayList<>();
        for (int i = 0; i < queries.size(); i++) {
            Relation written = queries.get(i);
            Relation optimized = written.optimized();
            entries.add(new OptimizationReport.Entry(written.label().orElse("<expression " + (i + 1) + ">"),
                    written.node(), optimized.node(), optimized.rewrites()));
        }
        return entries;
    }

    /** The text with each query statement replaced by its rewritten query, in order. */
    private static String rewritten(ScriptRunner.Script script, List<Relation> queries) {
        String text = script.source().text();
        List<ScriptText.Piece> statements = ScriptText.of(text).statements().stream()
                .filter(piece -> piece.statement() instanceof QueryStatement)
                .toList();
        if (statements.size() != queries.size()) {
            throw new CommandFailure(ExitCode.INTERNAL, "the script has " + statements.size()
                    + " query statements, but the engine returned " + queries.size() + " queries");
        }
        StringBuilder out = new StringBuilder();
        int at = 0;
        for (int i = 0; i < statements.size(); i++) {
            ScriptText.Span span = statements.get(i).span();
            out.append(text, at, span.start());
            out.append("query { ").append(queries.get(i).optimized().render()).append(" };");
            at = span.end();
        }
        out.append(text, at, text.length());
        return out.toString();
    }

}
