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
 * Harvests the problem-solving manual's result tables — a local developer tool, not a
 * CI gate.
 *
 * <p>The manual's recipes pace each query with an {@code <!-- output: … -->} placeholder
 * comment. This runs every recipe and case study through the engine and rewrites each
 * placeholder as a fenced table rendered by {@link QueryResultFormatter} — the very
 * renderer the CLI prints with — so the pasted output is what a reader actually sees,
 * and never hand-written.
 *
 * <p>Run it against the manual in a sibling relix-core checkout, on an engine that carries
 * every operator the manual uses (the snapshot pin on {@code develop} does, and
 * {@code -PrelixCore} builds that checkout's own engine):
 *
 * <pre>{@code
 * PSM_DIR=/path/to/relix-core/docs/problem-solving \
 *     ./gradlew test --tests '*ProblemSolvingHarvest' --rerun
 * }</pre>
 *
 * <p>It is skipped when {@code PSM_DIR} is not set (every ordinary build), so it never
 * runs in CI: the permanent, CI-side check is the render guard that reads the
 * manual from the published manuals zip.
 */
@DisplayName("Harvest the problem-solving manual's output tables (local tool)")
final class ProblemSolvingHarvest {

    /** A query statement in a script: {@code query { … }} or {@code query Name;}. */
    private static final Pattern QUERY = Pattern.compile("(?m)(?:^|;|\\})\\s*query\\b");

    /** A placeholder output comment paced after a query block. */
    private static final Pattern OUTPUT_COMMENT = Pattern.compile("^\\s*<!--\\s*output:.*-->\\s*$");

    @Test
    @DisplayName("rewrite every placeholder with its real table")
    void harvest() throws IOException {
        String dir = System.getProperty("psm.dir");
        if (dir == null) {
            dir = System.getenv("PSM_DIR");
        }
        final String location = dir;
        assumeTrue(location != null && Files.isDirectory(Path.of(location)),
                "set PSM_DIR=<relix-core>/docs/problem-solving to harvest");
        Path manual = Path.of(dir);
        List<Path> pages;
        try (Stream<Path> walk = Files.walk(manual)) {
            pages = walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".md"))
                    .filter(p -> {
                        String s = manual.relativize(p).toString();
                        return s.startsWith("recipes") || s.startsWith("case-studies");
                    })
                    .sorted().toList();
        }
        int filled = 0;
        for (Path page : pages) {
            filled += harvestPage(page);
        }
        System.out.println("Harvested " + filled + " output tables across " + pages.size() + " pages.");
    }

    /** Rewrites one page's placeholders; {@return how many it filled}. */
    private int harvestPage(Path page) throws IOException {
        List<String> lines = new ArrayList<>(List.of(read(page).split("\n", -1)));

        // Run the whole page as one session and collect each query's rendered table.
        String script = relixScript(lines);
        if (script.isBlank()) {
            return 0;
        }
        List<String> tables = renderAll(script);

        // Walk the page: each relix block consumes as many tables as it has queries; an
        // output-comment right after a block is replaced by that block's tables.
        List<String> out = new ArrayList<>();
        List<String> blockTables = List.of();
        int next = 0;
        boolean inBlock = false;
        StringBuilder block = new StringBuilder();
        int filled = 0;
        for (String line : lines) {
            String t = line.strip();
            if (!inBlock && t.equals("```relix")) {
                inBlock = true;
                block.setLength(0);
                out.add(line);
            } else if (inBlock && t.equals("```")) {
                inBlock = false;
                int n = countQueries(block.toString());
                blockTables = tables.subList(Math.min(next, tables.size()),
                        Math.min(next + n, tables.size()));
                next += n;
                out.add(line);
            } else if (inBlock) {
                block.append(line).append('\n');
                out.add(line);
            } else if (OUTPUT_COMMENT.matcher(line).matches() && !blockTables.isEmpty()) {
                out.add("```");
                for (String table : blockTables) {
                    out.addAll(List.of(table.split("\n", -1)));
                }
                out.add("```");
                blockTables = List.of();
                filled++;
            } else {
                out.add(line);
            }
        }
        Files.writeString(page, String.join("\n", trimBlankRuns(out)));
        return filled;
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

    /** One result as the CLI prints it, minus the {@code ── label ──} header the pages omit. */
    private static String render(String label, Rows rows) {
        List<Row> rowList = new ArrayList<Row>(rows.rows());
        String table = QueryResultFormatter.format(label, rows.schema(), rowList.stream());
        int firstBreak = table.indexOf('\n');
        String body = firstBreak < 0 ? table : table.substring(firstBreak + 1);
        // Strip trailing whitespace per line — the padding does not survive an editor.
        return Stream.of(body.split("\n", -1))
                .map(String::stripTrailing)
                .reduce((a, b) -> a + "\n" + b).orElse("").stripTrailing();
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

    /** Collapses a run of blank lines to one, tidying where a one-line comment became a fence. */
    private static List<String> trimBlankRuns(List<String> lines) {
        List<String> out = new ArrayList<>();
        boolean prevBlank = false;
        for (String line : lines) {
            boolean blank = line.isBlank();
            if (blank && prevBlank) {
                continue;
            }
            out.add(line);
            prevBlank = blank;
        }
        return out;
    }

    private static String read(Path p) {
        try {
            return Files.readString(p);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
