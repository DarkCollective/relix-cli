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
package com.darkcollective.relix.cli.report;

import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.optimizer.OptimizationCode;
import com.darkcollective.relix.optimizer.TransformationRecord;
import com.darkcollective.relix.semantic.SemanticModel;

import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Produces a human-readable optimization report: what each query was written as, what
 * the rewriter made of it, and which rules did it.
 *
 * <p>The report follows the same layout conventions as
 * the IR report ({@code SemanticModel.ir()}): plain UTF-8 (no BOM),
 * at most {@value #WIDTH} characters per line, ruled section headers, and
 * lines that exceed the width are truncated with a trailing {@code …}.
 *
 * <h2>Report sections</h2>
 * <ol>
 *   <li><strong>Header</strong> — namespace and total transformation count.</li>
 *   <li><strong>Summary</strong> — table of
 *       {@code (code, description, count)} for every rule that fired at least
 *       once, sorted by code.</li>
 *   <li><strong>Trees</strong> — for each query that was rewritten, the
 *       before and after relational algebra expression rendered on a single
 *       line using {@link com.darkcollective.relix.ast.RelNode#prettyPrint()}.
 *       Queries without any transformation are omitted.</li>
 *   <li><strong>Detail</strong> — per-query block listing each
 *       {@link TransformationRecord} with its context string and source
 *       location.</li>
 * </ol>
 *
 * <p>This class is stateless; every method is static.
 */
public final class OptimizationReport {

    /**
     * One query's rewrite, as the report renders it.
     *
     * @param queryName what the script called the query
     * @param original  the tree as written
     * @param optimized the tree the rewriter produced
     * @param applied   the rules that produced it, in the order they fired
     */
    public record Entry(String queryName, RelNode original, RelNode optimized,
                        List<TransformationRecord> applied) {

        /** Checks every component and copies the records. */
        public Entry {
            Objects.requireNonNull(queryName, "queryName");
            if (queryName.isBlank()) {
                throw new IllegalArgumentException("queryName must not be blank");
            }
            Objects.requireNonNull(original, "original");
            Objects.requireNonNull(optimized, "optimized");
            Objects.requireNonNull(applied, "applied");
            applied = List.copyOf(applied);
        }

        /** {@return whether any rule fired} */
        public boolean wasOptimized() {
            return !applied.isEmpty();
        }

        /** {@return how many rules fired} */
        public int transformationCount() {
            return applied.size();
        }
    }

    /** Maximum line width in characters, as for the IR report. */
    public static final int WIDTH = 80;

    private static final String DOUBLE_RULE = "═".repeat(WIDTH);

    private OptimizationReport() {}

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Generates the optimization report, deriving the namespace from the
     * supplied {@link SemanticModel}.
     *
     * <p>This is a convenience overload equivalent to
     * {@code generate(model.namespace(), results)}.
     *
     * @param model   the semantic model whose namespace is used in the header;
     *                must not be null
     * @param results per-query optimization results; must not be null
     * @return the formatted report string; never null or empty
     */
    public static String generate(SemanticModel           model,
                                  List<Entry> results) {
        Objects.requireNonNull(model, "model");
        return generate(model.namespace(), results);
    }

    /**
     * Generates the optimization report for the given results.
     *
     * @param namespace the namespace declared in the source script; used in
     *                  the report header
     * @param results   per-query optimization results; must not be null
     * @return the formatted report string; never null or empty
     */
    public static String generate(String                   namespace,
                                  List<Entry> results) {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(results,   "results");

        var sb = new StringBuilder(2048);

        long total = results.stream()
                .mapToLong(Entry::transformationCount)
                .sum();

        // ── Header ──────────────────────────────────────────────────────────
        sb.append(DOUBLE_RULE).append('\n');
        sb.append(fit("RELIX OPTIMIZER  namespace=" + namespace
                + "  transformations=" + total)).append('\n');
        sb.append(DOUBLE_RULE).append('\n');

        if (results.isEmpty() || total == 0) {
            sb.append("  (no optimizations applied)\n");
            sb.append(DOUBLE_RULE).append('\n');
            return sb.toString();
        }

        // ── Summary ─────────────────────────────────────────────────────────
        appendSummary(sb, results);

        // ── Trees ───────────────────────────────────────────────────────────
        appendTrees(sb, results);

        // ── Detail ──────────────────────────────────────────────────────────
        appendDetail(sb, results);

        sb.append(DOUBLE_RULE).append('\n');
        return sb.toString();
    }

    // =========================================================================
    // Section: SUMMARY
    // =========================================================================

    private static void appendSummary(StringBuilder sb,
                                      List<Entry> results) {
        sb.append('\n').append(sectionBar("SUMMARY")).append('\n');

        // Tally counts per code across all queries.
        Map<OptimizationCode, Long> counts = new EnumMap<>(OptimizationCode.class);
        for (Entry r : results) {
            for (TransformationRecord rec : r.applied()) {
                counts.merge(rec.code(), 1L, Long::sum);
            }
        }

        // Sort by code string for deterministic output.
        counts.entrySet().stream()
                .sorted(Comparator.comparing(e -> e.getKey().code()))
                .forEach(e -> {
                    OptimizationCode code = e.getKey();
                    long count = e.getValue();
                    // " EXPR-001  Constant arithmetic fold ................ ×3"
                    String prefix = " " + code.code() + "  " + code.description() + " ";
                    String suffix = "×" + count;
                    int dots = Math.max(1, WIDTH - prefix.length() - suffix.length());
                    sb.append(trunc(prefix + ".".repeat(dots) + suffix, WIDTH))
                      .append('\n');
                });
    }

    // =========================================================================
    // Section: TREES
    // =========================================================================

    private static void appendTrees(StringBuilder sb,
                                    List<Entry> results) {
        List<Entry> changed = results.stream()
                .filter(Entry::wasOptimized)
                .toList();
        if (changed.isEmpty()) return;

        sb.append('\n').append(sectionBar("TREES")).append('\n');

        for (Entry r : changed) {
            sb.append('\n');
            sb.append(fit("── " + r.queryName())).append('\n');
            sb.append(trunc("  before:  " + r.original().prettyPrint(),  WIDTH)).append('\n');
            sb.append(trunc("  after:   " + r.optimized().prettyPrint(), WIDTH)).append('\n');
        }
    }

    // =========================================================================
    // Section: DETAIL
    // =========================================================================

    private static void appendDetail(StringBuilder sb,
                                     List<Entry> results) {
        sb.append('\n').append(sectionBar("DETAIL")).append('\n');

        for (Entry result : results) {
            if (!result.wasOptimized()) continue;

            sb.append('\n');
            sb.append(fit("── " + result.queryName() + " (" +
                    result.transformationCount() + " transformation" +
                    (result.transformationCount() == 1 ? "" : "s") + ")"))
              .append('\n');

            for (TransformationRecord rec : result.applied()) {
                // "  [CODE]  detail @ location"
                String line = "  [" + rec.code().code() + "]  "
                        + rec.detail() + " @ " + rec.location();
                sb.append(trunc(line, WIDTH)).append('\n');
            }
        }
    }

    // =========================================================================
    // Formatting utilities (mirrors IrReport conventions)
    // =========================================================================

    private static String sectionBar(String title) {
        String prefix = "── " + title + " ";
        int dashes = Math.max(0, WIDTH - prefix.length());
        return prefix + "─".repeat(dashes);
    }

    private static String trunc(String s, int max) {
        if (max <= 0)          return "";
        if (s.length() <= max) return s;
        if (max == 1)          return "…";
        return s.substring(0, max - 1) + "…";
    }

    private static String fit(String s) {
        return trunc(s, WIDTH);
    }
}
