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

import com.darkcollective.relix.embed.Relation;
import com.darkcollective.relix.embed.Relix;
import com.darkcollective.relix.symbol.Schema;
import com.darkcollective.relix.embed.Rows;
import com.darkcollective.relix.processor.Row;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Executes every worked example in the language reference and diffs the rows it
 * produces against the result table the page prints — the third and last of the
 * {@code docs/reference} guards, and the only one that checks what an example
 * <em>means</em>.
 *
 * <p>The family divides the failure modes cleanly:
 * <ul>
 *   <li>{@code ProjectDocsGuardTest} (relix-lang) — a page that is missing,
 *       unregistered, or off-format;</li>
 *   <li>{@code ReferenceExampleParseTest} (relix-lang) — an example the grammar
 *       rejects;</li>
 *   <li>this test — an example that parses and is <em>wrong</em>: a printed table
 *       that is no longer what the engine produces.</li>
 * </ul>
 * Parsing proves nothing about behaviour. The documented
 * {@code IIf(IsNumeric(raw), CDbl(raw), 0)} guard idiom parsed and then crashed
 * when run; it was found only because the worked examples were executed by hand
 * once, which is exactly the manual step this test replaces.
 *
 * <p>It lives with the command rather than beside its two siblings in the engine
 * because it renders the result with the very {@link QueryResultFormatter} the command
 * prints with — checking the docs against a reimplemented formatter would check them
 * against a renderer nobody ships. A guard follows its renderer.
 *
 * <h2>What is checked</h2>
 * In a {@code # Worked Example:} section, a fenced <code>```relix</code> block
 * containing a {@code query} statement is executed, and its output is compared to
 * the plain <code>```</code> fence that follows it (the convention every page
 * already uses). Earlier {@code relix} blocks of the same section are prepended,
 * so a later block may reference relations an earlier one declared.
 *
 * <p>Only self-contained examples are executable: a block that reaches outside the
 * script — {@code source}, {@code connection}, {@code import} — is skipped, since
 * its output is a property of the external system, not of the engine. The one
 * exception is a file connection over the manual's own fixtures, in
 * {@code docs/reference/.fixtures}: that file is part of the manual, so what an example
 * over it prints is the engine's behaviour and is checked. Such an example runs through
 * a session whose base directory is the fixtures directory and which fetches nothing.
 *
 * <p>Comparison normalises trailing whitespace only. The formatter pads every cell
 * to its column width and those trailing spaces do not survive an editor; nothing
 * else is forgiven. The pasted block also omits the {@code ── label ──} header line
 * the formatter emits, so that one line is dropped before diffing.
 *
 * <p>There is deliberately <em>no</em> regeneration mode: when an example's output
 * legitimately changes, the page should be updated as a considered edit — which is
 * the whole point of pinning it.
 */
@DisplayName("Reference worked examples reproduce their printed output")
final class WorkedExampleOutputTest {

    /**
     * Worked examples whose output cannot be pinned (e.g. it reads the clock or the
     * RNG), keyed by {@code page#ordinal}. REMOVE an entry when it becomes
     * checkable; the test fails on an entry matching no example, so the list cannot
     * go stale.
     */
    private static final Set<String> UNCHECKABLE = Set.of();

    /** A fenced block; {@code lang} is empty for a plain ``` fence. */
    private record Block(String lang, String body, int line) {}

    /**
     * One worked-example query block, with the printed result that follows it.
     *
     * @param script      every earlier {@code relix} block of the section, then this one
     * @param resultCount how many of the script's query results are this block's own
     * @param expected    the printed result, or {@code null} when the page prints none
     */
    private record Example(String page, int ordinal, int line, String script,
                           int resultCount, String expected, boolean fixture) {
        String location() {
            return fileName(page) + ":" + line + " (worked example " + ordinal + ")";
        }
        String key() {
            return fileName(page) + "#" + ordinal;
        }
    }

    /** One query's result, as the formatter is handed it. */
    private record Result(String label, Schema schema, List<Row> rows) {}

    private static String fileName(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    @Test
    @DisplayName("every worked example produces exactly the rows its page prints")
    void workedExamplesReproduceTheirPrintedOutput() {
        List<Example> examples = extractAll().stream()
                .filter(e -> e.expected() != null)
                .toList();
        assertThat(examples)
                .as("executable worked examples were found — an empty list means the "
                        + "extractor has stopped matching the page convention and this "
                        + "guard is checking nothing")
                .isNotEmpty();

        List<String> mismatches = new ArrayList<>();
        Set<String> seenUncheckable = new LinkedHashSet<>();
        for (Example example : examples) {
            if (UNCHECKABLE.contains(example.key())) {
                seenUncheckable.add(example.key());
                continue;
            }
            String failure = check(example);
            if (failure != null) {
                mismatches.add(failure);
            }
        }

        Set<String> deadEntries = new LinkedHashSet<>(UNCHECKABLE);
        deadEntries.removeAll(seenUncheckable);

        assertThat(mismatches)
                .as("worked examples whose printed result is not what the engine "
                        + "produces — rerun the example and update the page (or fix the "
                        + "engine, if the doc is the one that is right)")
                .isEmpty();
        assertThat(deadEntries)
                .as("UNCHECKABLE entries matching no worked example — the page changed; "
                        + "remove the stale entry")
                .isEmpty();
    }

    @Test
    @DisplayName("every worked-example query prints a result to check")
    void everyWorkedExampleQueryPrintsItsResult() {
        List<String> unprinted = extractAll().stream()
                .filter(e -> e.expected() == null)
                .map(e -> fileName(e.page()) + ":" + e.line())
                .toList();
        assertThat(unprinted)
                .as("""
                        `query` blocks in a `# Worked Example:` section with no printed \
                        result after them. A worked example is meant to show what comes \
                        out, and without a result fence nothing checks that it does what \
                        the prose says. Add a plain ``` fence holding the output — run \
                        the example, do not hand-write the table""")
                .isEmpty();
    }

    // ── execution ───────────────────────────────────────────────────────────────

    /** Runs one example; returns {@code null} when it reproduces its printed output. */
    private static String check(Example example) {
        List<Result> results;
        try {
            results = run(example.script(), example.fixture());
        } catch (RuntimeException e) {
            return example.location() + " :: threw " + e.getClass().getSimpleName()
                    + ": " + e.getMessage();
        }
        return compare(example, results);
    }

    /**
     * Runs a script through a session, as a user of the manual would. A self-contained
     * example runs as written, the table being order-sensitive; one reading the manual's
     * fixture files runs as a session runs it.
     */
    private static List<Result> run(String script, boolean fixture) {
        Relix.Builder builder = Relix.builder();
        if (fixture) {
            builder.baseDirectory(referenceRoot().resolve(".fixtures")).remoteFiles(false);
        }
        try (Relix relix = builder.build()) {
            List<Result> out = new ArrayList<>();
            for (Relation relation : relix.script(script)) {
                Rows rows = fixture ? relation.run() : relation.asWritten().run();
                out.add(new Result(relation.label().orElse(""), rows.schema(),
                        new ArrayList<Row>(rows.rows())));
            }
            return out;
        }
    }

    /** {@return null when {@code results} end with what the page prints, else why not} */
    private static String compare(Example example, List<Result> results) {
        // The script carries every earlier block's queries too, so this block's own
        // results are the tail. A count that does not line up means the extractor
        // mis-read the page — fail rather than diff the wrong table against it.
        int total = countQueryStatements(example.script());
        if (results.size() != total) {
            return example.location() + " :: expected " + total + " query result(s), got "
                    + results.size();
        }
        String actual = results.subList(total - example.resultCount(), total).stream()
                .map(WorkedExampleOutputTest::render)
                .reduce("", String::concat);

        return normalise(actual).equals(normalise(example.expected()))
                ? null
                : example.location() + " :: printed result differs\n"
                        + "  ── printed ──\n" + quote(example.expected())
                        + "  ── actual  ──\n" + quote(actual);
    }

    /**
     * Renders one result as the CLI's default table format does, minus the
     * {@code ── label ──} header line the pages do not paste.
     */
    private static String render(Result result) {
        String table = QueryResultFormatter.format(result.label(), result.schema(), result.rows().stream());
        int firstBreak = table.indexOf('\n');
        return firstBreak < 0 ? table : table.substring(firstBreak + 1);
    }

    /**
     * Trailing whitespace is the only difference forgiven — per line (the formatter
     * pads cells to the column width, and those spaces do not survive an editor)
     * and as blank lines around the block (the fence contributes one). Leading
     * spaces are load-bearing: they are the table's left margin and its number
     * alignment.
     */
    private static String normalise(String text) {
        List<String> lines = new ArrayList<>(Stream.of(text.split("\n", -1))
                .map(String::stripTrailing)
                .toList());
        while (!lines.isEmpty() && lines.get(0).isEmpty()) {
            lines.remove(0);
        }
        while (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        return String.join("\n", lines);
    }

    private static String quote(String text) {
        return Stream.of(normalise(text).split("\n", -1))
                .map(line -> "  │ " + line + "\n")
                .reduce("", String::concat);
    }

    // ── extraction ──────────────────────────────────────────────────────────────

    /**
     * Every worked example in the reference the pinned engine's manuals carry: the manual
     * as that engine release ships it, not a copy of the tree.
     */
    private static List<Example> extractAll() {
        Path root = referenceRoot();
        List<Path> pages;
        try (Stream<Path> walk = Files.walk(root)) {
            pages = walk.filter(p -> p.toString().endsWith(".md")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        List<Example> out = new ArrayList<>();
        for (Path page : pages) {
            String path = root.relativize(page).toString().replace('\\', '/');
            String markdown;
            try {
                markdown = Files.readString(page);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            int ordinal = 0;
            for (List<Block> section : workedExampleSections(markdown)) {
                ordinal = extractSection(path, section, ordinal, out);
            }
        }
        return out;
    }

    /** Adds the section's examples; returns the next free page-level ordinal. */
    private static int extractSection(String page, List<Block> blocks, int ordinal,
                                      List<Example> out) {
        StringBuilder prelude = new StringBuilder();
        for (int i = 0; i < blocks.size(); i++) {
            Block block = blocks.get(i);
            if (!block.lang().equals("relix")) {
                continue;
            }
            String script = prelude + block.body();
            prelude.append(block.body()).append('\n');
            int queries = countQueryStatements(block.body());
            boolean fixture = !isSelfContained(script) && readsOnlyFixtures(script);
            if (queries == 0 || !(isSelfContained(script) || fixture)) {
                continue;
            }
            Block next = i + 1 < blocks.size() ? blocks.get(i + 1) : null;
            boolean printed = next != null && next.lang().isEmpty() && isPrintedResult(next.body());
            out.add(new Example(page, ++ordinal, block.line(), script, queries,
                    printed ? next.body() : null, fixture));
        }
        return ordinal;
    }

    /** Statement-leading {@code query} keywords — how many results a script yields. */
    private static final Pattern QUERY_STATEMENT = Pattern.compile("(?m)^[ \t]*query\\b");

    private static int countQueryStatements(String script) {
        Matcher m = QUERY_STATEMENT.matcher(script);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }

    /** A script reaching outside itself is not the engine's own behaviour. */
    private static boolean isSelfContained(String script) {
        return !Pattern.compile("(?m)^[ \t]*(source|connection|import)\\b")
                .matcher(script).find();
    }

    private static final Pattern CONNECTION =
            Pattern.compile("(?m)^[ \\t]*connection\\s+(\\w+)\\s+from\\s+(\\w+)");
    private static final Pattern SOURCE =
            Pattern.compile("(?m)^[ \\t]*source\\s+\\w+\\s+from\\s+(\\w+)");

    /**
     * Whether everything {@code script} reaches outside itself is a file connection
     * naming a local file: no {@code import}, no {@code url}, each connection of a file
     * type, and each source bound to one of them.
     */
    private static boolean readsOnlyFixtures(String script) {
        if (Pattern.compile("(?m)^[ \t]*import\b").matcher(script).find()
                || script.contains("url:")) {
            return false;
        }
        Set<String> connections = new LinkedHashSet<>();
        Matcher c = CONNECTION.matcher(script);
        while (c.find()) {
            if (!Set.of("log", "clf", "csv", "gedcom").contains(c.group(2))) {
                return false;
            }
            connections.add(c.group(1));
        }
        Matcher s = SOURCE.matcher(script);
        while (s.find()) {
            if (!connections.contains(s.group(1))) {
                return false;
            }
        }
        return !connections.isEmpty();
    }

    /** Formatter output ends in its row-count footer — the mark of a pasted result. */
    private static boolean isPrintedResult(String body) {
        return Pattern.compile("(?m)^\\(\\d+ rows?\\)[ \t]*$").matcher(body).find();
    }

    /**
     * The fenced blocks of each {@code # Worked Example:} section of {@code page},
     * in document order. A section ends at the next {@code # Label:} heading, the
     * page format {@code ProjectDocsGuardTest} holds every page to.
     */
    private static List<List<Block>> workedExampleSections(String markdown) {
        List<List<Block>> sections = new ArrayList<>();
        List<Block> current = null;
        String[] lines = markdown.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String stripped = line.strip();
            if (stripped.startsWith("```")) {
                String lang = stripped.substring(3).strip();
                int start = i;
                StringBuilder body = new StringBuilder();
                i++;
                while (i < lines.length && !lines[i].strip().equals("```")) {
                    body.append(lines[i]).append('\n');
                    i++;
                }
                if (current != null) {
                    current.add(new Block(lang, body.toString(), start + 1));
                }
                continue;
            }
            if (line.startsWith("# ")) {
                current = line.startsWith("# Worked Example") ? new ArrayList<>() : null;
                if (current != null) {
                    sections.add(current);
                }
            }
        }
        return sections;
    }

    /**
     * The pinned engine's language reference, unpacked by the build from the engine's
     * {@code manuals} artifact: its pages, and the fixture files a worked example reading
     * a file connection opens.
     */
    private static Path referenceRoot() {
        String docs = System.getProperty("relix.engine.docs");
        assertThat(docs).as("the pinned engine's manuals, unpacked by the build").isNotBlank();
        return Path.of(docs, "docs", "reference");
    }
}
