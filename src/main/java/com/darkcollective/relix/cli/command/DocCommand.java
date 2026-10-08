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
import com.darkcollective.relix.cli.io.Pager;
import com.darkcollective.relix.cli.io.RowSink;
import com.darkcollective.relix.cli.render.DocIndex;
import com.darkcollective.relix.cli.render.DocRenderer;
import com.darkcollective.relix.docs.ReferencePage;
import com.darkcollective.relix.symbol.ScalarType;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * {@code relix doc [TOPIC]}: a page of the language reference (design §3).
 *
 * <p>A topic is found by glyph, keyword or name: {@code σ}, {@code select} and
 * {@code selection} are one page. A language keyword keeps a name it shares with a
 * function, so {@code relix doc fix} is the recursion operator and
 * {@code relix doc --function fix} is the function. The page is rendered for a terminal
 * and, on one, shown through {@code $PAGER}. With no topic, the pages are listed, as rows.
 */
@Command(name = "doc",
        mixinStandardHelpOptions = true,
        sortOptions = false,
        usageHelpAutoWidth = true,
        description = {
            "Shows the reference page for TOPIC, found by glyph, keyword or name (σ, select,",
            "selection), through $PAGER on a terminal. With no TOPIC, lists the pages."},
        exitCodeListHeading = "%nExit status:%n",
        exitCodeList = {" 0:success", " 2:no page has that topic"})
final class DocCommand implements Callable<Integer> {

    @ParentCommand
    RelixCommand root;

    @Parameters(paramLabel = "TOPIC", arity = "0..1",
            description = "A glyph, keyword or name, such as σ, select, join or Len.")
    String topic;

    @Option(names = "--function",
            description = "Look TOPIC up among the functions only, or list only them.")
    boolean function;

    @Option(names = "--markdown", description = "Print the page's markdown as it is, not rendered.")
    boolean markdown;

    @Option(names = "--no-pager", description = "Write the page to standard output even on a terminal.")
    boolean noPager;

    @Mixin
    FormatOptions formatting;

    @Override
    public Integer call() {
        Invocation invocation = root.invocation(false);
        DocIndex index = DocIndex.installed();
        if (topic == null) {
            list(invocation, index);
            return ExitCode.SUCCESS.status();
        }
        Optional<ReferencePage> found = function ? index.function(topic) : index.lookup(topic);
        ReferencePage page = found.orElseThrow(() -> new CommandFailure(ExitCode.USAGE,
                "no reference page for '" + topic + "'" + (function ? " among the functions" : "")
                        + "; relix doc" + (function ? " --function" : "") + " lists them"));
        String text = index.markdown(page).orElseThrow(() -> new CommandFailure(ExitCode.INTERNAL,
                "the reference lists " + page.path() + " but has no page there"));
        RowSink out = new RowSink(invocation.host().out(), false);
        if (!markdown) {
            text = DocRenderer.render(text, page.path());
        }
        if (noPager) {
            out.text(text);
        } else {
            Pager.show(invocation.host(), text, out);
        }
        return ExitCode.SUCCESS.status();
    }

    private void list(Invocation invocation, DocIndex index) {
        Listing listing = new Listing("topic", ScalarType.STRING, "category", ScalarType.STRING,
                "title", ScalarType.STRING, "summary", ScalarType.STRING);
        for (ReferencePage page : index.pages()) {
            if (!function || page.category().equals(DocIndex.FUNCTION)) {
                listing.add(page.symbol(), page.category(), page.title(), page.summary());
            }
        }
        listing.print(invocation, invocation.format(formatting), "reference");
    }
}
