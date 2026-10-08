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

import com.darkcollective.relix.docs.ReferencePage;
import com.darkcollective.relix.docs.RelixDocs;
import com.darkcollective.relix.function.FunctionCatalog;
import com.darkcollective.relix.function.ScalarFunction;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The reference pages {@code relix doc} can show, and how each is found: the language
 * pages {@code relix-docs} carries, then a page per documented function of every installed
 * library.
 *
 * <p>A page is found by any of its keys, case-insensitively: {@code σ}, {@code select}
 * and {@code selection} are one page. A language page keeps a key it shares with a
 * function, so {@code fix} is the recursion operator; the function is still found by
 * asking for a function ({@link #function(String)}).
 *
 * <p>This index belongs in {@code relix-docs}, beside the pages it indexes, and moves
 * there with DarkCollective/relix-core#95; it is the same index the REPL's front end
 * builds.
 */
public final class DocIndex {

    /** The category a function's page is listed under. */
    public static final String FUNCTION = "function";

    /** {@code # Name: Len (string length)}: a function page's own one-line summary. */
    private static final Pattern TITLE =
            Pattern.compile("^#\\s*Name:\\s*(\\S+)\\s*\\(([^)]*)\\)", Pattern.MULTILINE);

    private final FunctionCatalog functions;
    private final List<ReferencePage> pages = new ArrayList<>();
    private final Map<String, ReferencePage> byKey = new HashMap<>();
    private final Map<String, ReferencePage> byFunction = new LinkedHashMap<>();

    private DocIndex(FunctionCatalog functions) {
        this.functions = Objects.requireNonNull(functions, "functions");
        for (ReferencePage page : RelixDocs.referencePages()) {
            add(page);
        }
        for (ScalarFunction function : functions.scalars()) {
            String docKey = function.signature().docKey().orElse(null);
            if (docKey == null) {
                continue;
            }
            Optional<String> markdown = functions.documentation(docKey);
            if (markdown.isEmpty()) {
                continue;
            }
            String name = function.signature().name();
            ReferencePage page = new ReferencePage(docKey, FUNCTION, name, name,
                    summary(markdown.get(), name), List.of(normalise(name)));
            byFunction.putIfAbsent(normalise(name), page);
            add(page);
        }
    }

    /**
     * The index over the language pages and the functions of every installed library.
     *
     * @return the index
     */
    public static DocIndex installed() {
        return of(FunctionCatalog.discover());
    }

    /**
     * The index over the language pages and the functions of {@code functions}.
     *
     * @param functions the libraries whose functions' pages are indexed
     * @return the index
     */
    public static DocIndex of(FunctionCatalog functions) {
        return new DocIndex(functions);
    }

    /**
     * Every page, language pages first, in the reference's own order, then the functions
     * in the order their libraries offer them.
     *
     * @return the pages
     */
    public List<ReferencePage> pages() {
        return List.copyOf(pages);
    }

    /**
     * The page found by a key: a glyph, a keyword or a name.
     *
     * @param key what to look up, in any case
     * @return the page
     */
    public Optional<ReferencePage> lookup(String key) {
        return Optional.ofNullable(byKey.get(normalise(key)));
    }

    /**
     * A function's page, by its name, even where a language page has that key.
     *
     * @param name the function's name, in any case
     * @return its page
     */
    public Optional<ReferencePage> function(String name) {
        return Optional.ofNullable(byFunction.get(normalise(name)));
    }

    /**
     * A page's markdown, from whichever source holds it.
     *
     * @param page a page of this index
     * @return its markdown
     */
    public Optional<String> markdown(ReferencePage page) {
        return page.category().equals(FUNCTION)
                ? functions.documentation(page.path())
                : RelixDocs.referencePage(page.path());
    }

    /** Adds a page under each of its keys that no earlier page has claimed. */
    private void add(ReferencePage page) {
        pages.add(page);
        for (String key : page.keys()) {
            byKey.putIfAbsent(normalise(key), page);
        }
    }

    /**
     * The parenthetical of a page's {@code # Name:} heading, capitalised, or the
     * function's name when it has none.
     */
    private static String summary(String markdown, String name) {
        Matcher m = TITLE.matcher(markdown);
        if (!m.find() || m.group(2).isBlank()) {
            return name;
        }
        String summary = m.group(2).strip();
        return Character.toUpperCase(summary.charAt(0)) + summary.substring(1);
    }

    private static String normalise(String key) {
        return key.strip().toLowerCase(Locale.ROOT);
    }
}
