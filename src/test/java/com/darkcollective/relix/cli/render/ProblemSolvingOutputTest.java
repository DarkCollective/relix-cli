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
package com.darkcollective.relix.cli.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.darkcollective.relix.embed.Relation;
import com.darkcollective.relix.embed.Relix;
import com.darkcollective.relix.embed.Rows;
import com.darkcollective.relix.processor.Row;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The problem-solving manual prints what the engine produces.
 *
 * <p>Every recipe and case study in the manual paces each query with a fenced result
 * table. This runs each page through the engine and diffs each pasted table against what
 * the query actually returns, rendered with the very {@link QueryResultFormatter} the CLI
 * prints with — the same guarantee {@code WorkedExampleOutputTest} gives the reference
 * manual, for the manual whose whole purpose is to be copied.
 *
 * <p>The manual is read from the pinned engine's unpacked manuals zip
 * ({@code docs/problem-solving} under {@code -Drelix.engine.docs}), so a pin that predates
 * the manual simply skips this rather than failing. The pages carry only inline data, so
 * a closed session runs them as a reader would.
 */
@DisplayName("The problem-solving manual prints what the engine produces")
final class ProblemSolvingOutputTest {

    /** A query statement: {@code query { … }} or {@code query Name;}. */
    private static final Pattern QUERY = Pattern.compile("(?m)(?:^|;|\\})\\s*query\\b");

    @Test
    @DisplayName("every pasted result table is what the query returns")
    void everyTableMatches() throws IOException {
        Path manual = manualRoot();
        assumeTrue(Files.isDirectory(manual),
                "the pinned engine's manuals carry no docs/problem-solving");
        List<String> mismatches = new ArrayList<>();
        List<Path> pages;
        try (Stream<Path> walk = Files.walk(manual)) {
            pages = walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".md"))
                    .filter(p -> {
                        String s = manual.relativize(p).toString().replace('\\', '/');
                        return s.startsWith("recipes/") || s.startsWith("case-studies/");
                    })
                    .sorted().toList();
        }
        for (Path page : pages) {
            checkPage(page, mismatches);
        }
        assertThat(pages).as("problem-solving recipes were found").isNotEmpty();
        assertThat(mismatches)
                .as("pasted result tables that are not what the engine produces — rerun "
                        + "the harvester (ProblemSolvingHarvest) and update the page")
                .isEmpty();
    }

    private void checkPage(Path page, List<String> mismatches) {
        List<String> lines = List.of(read(page).split("\n", -1));
        String script = relixScript(lines);
        if (script.isBlank()) {
            return;
        }
        List<String> tables;
        try {
            tables = renderAll(script);
        } catch (RuntimeException e) {
            mismatches.add(page.getFileName() + " :: threw " + e.getClass().getSimpleName()
                    + ": " + e.getMessage());
            return;
        }

        // Walk the page pairing each query block with the fence that follows it, the way
        // the harvester wrote them: a block consumes as many results as it has queries.
        int next = 0;
        boolean inRelix = false;
        boolean inFence = false;
        boolean expectingOutput = false;
        StringBuilder block = new StringBuilder();
        List<String> pending = List.of();
        StringBuilder fence = new StringBuilder();
        int exampleLine = 0;
        int lineNo = 0;
        for (String line : lines) {
            lineNo++;
            String t = line.strip();
            if (!inRelix && !inFence && t.equals("```relix")) {
                inRelix = true;
                block.setLength(0);
                exampleLine = lineNo;
            } else if (inRelix && t.equals("```")) {
                inRelix = false;
                int n = countQueries(block.toString());
                pending = tables.subList(Math.min(next, tables.size()),
                        Math.min(next + n, tables.size()));
                next += n;
            } else if (inRelix) {
                block.append(line).append('\n');
            } else if (!inFence && t.equals("```")) {
                // A plain fence: an output table when a query block just produced results,
                // otherwise an illustrative code block (a connection snippet, a plan) to skip.
                inFence = true;
                expectingOutput = !pending.isEmpty();
                fence.setLength(0);
            } else if (inFence && t.equals("```")) {
                inFence = false;
                if (expectingOutput) {
                    String actual = String.join("", pending);
                    if (!normalise(actual).equals(normalise(fence.toString()))) {
                        mismatches.add(page.getFileName() + ":" + exampleLine
                                + " :: printed result differs\n  ── printed ──\n"
                                + fence + "  ── actual ──\n" + actual);
                    }
                }
                pending = List.of();
                expectingOutput = false;
            } else if (inFence) {
                fence.append(line).append('\n');
            }
        }
    }

    // ── execution and rendering (the CLI's own path) ─────────────────────────────

    private static List<String> renderAll(String script) {
        try (Relix relix = Relix.builder().build()) {
            List<String> tables = new ArrayList<>();
            for (Relation relation : relix.script(script)) {
                Rows rows = relation.run();
                tables.add(render(relation.label().orElse(""), rows));
            }
            return tables;
        }
    }

    private static String render(String label, Rows rows) {
        List<Row> rowList = new ArrayList<Row>(rows.rows());
        String table = QueryResultFormatter.format(label, rows.schema(), rowList.stream());
        int firstBreak = table.indexOf('\n');
        return firstBreak < 0 ? table : table.substring(firstBreak + 1);
    }

    private static String normalise(String text) {
        List<String> lines = new ArrayList<>(Stream.of(text.split("\n", -1))
                .map(String::stripTrailing).toList());
        while (!lines.isEmpty() && lines.get(0).isEmpty()) {
            lines.remove(0);
        }
        while (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        return String.join("\n", lines);
    }

    private static String relixScript(List<String> lines) {
        StringBuilder script = new StringBuilder();
        boolean in = false;
        for (String line : lines) {
            String t = line.strip();
            if (!in && t.equals("```relix")) {
                in = true;
            } else if (in && t.equals("```")) {
                in = false;
                script.append('\n');
            } else if (in) {
                script.append(line).append('\n');
            }
        }
        return script.toString();
    }

    private static int countQueries(String block) {
        int n = 0;
        var m = QUERY.matcher(block);
        while (m.find()) {
            n++;
        }
        return n;
    }

    private static Path manualRoot() {
        String docs = System.getProperty("relix.engine.docs");
        assertThat(docs).as("-Drelix.engine.docs is set by the test task").isNotNull();
        return Path.of(docs, "docs", "problem-solving");
    }

    private static String read(Path p) {
        try {
            return Files.readString(p);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
