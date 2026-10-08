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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The index {@code relix doc} looks pages up in: the same keys as the REPL's index in
 * {@code relix-console}, which is {@code relix-docs}' language pages and every documented
 * function, less the REPL's own page about itself.
 */
@DisplayName("The reference index")
class DocIndexTest {

    private final DocIndex index = DocIndex.installed();

    @Test
    @DisplayName("finds a page by every key of every language page")
    void languageKeys() {
        List<String> missing = new ArrayList<>();
        for (ReferencePage page : RelixDocs.referencePages()) {
            for (String key : page.keys()) {
                if (index.lookup(key).flatMap(index::markdown).isEmpty()) {
                    missing.add(page.path() + ": " + key);
                }
            }
        }
        assertThat(missing).isEmpty();
    }

    @Test
    @DisplayName("finds every documented function, by name, in either case")
    void functions() {
        FunctionCatalog catalog = FunctionCatalog.discover();
        List<String> documented = new ArrayList<>();
        for (ScalarFunction function : catalog.scalars()) {
            function.signature().docKey().filter(key -> catalog.documentation(key).isPresent())
                    .ifPresent(key -> documented.add(function.signature().name()));
        }

        assertThat(documented).isNotEmpty().allSatisfy(name -> {
            assertThat(index.function(name)).as(name).isPresent();
            assertThat(index.function(name.toUpperCase()).flatMap(index::markdown)).as(name).isPresent();
            assertThat(index.lookup(name)).as(name).isPresent();
        });
    }

    @Test
    @DisplayName("finds one page by glyph, keyword and name")
    void spellings() {
        assertThat(index.lookup("σ")).isPresent()
                .isEqualTo(index.lookup("select")).isEqualTo(index.lookup("SELECTION"));
    }

    @Test
    @DisplayName("gives a shared name to the language keyword, and still finds the function as a function")
    void collision() {
        assertThat(index.lookup("fix").orElseThrow().category()).isNotEqualTo(DocIndex.FUNCTION);
        assertThat(index.function("fix").orElseThrow().category()).isEqualTo(DocIndex.FUNCTION);
        assertThat(index.function("fix").flatMap(index::markdown).orElseThrow()).contains("Fix");
    }

    @Test
    @DisplayName("summarises a function page by its heading's parenthetical")
    void summary() {
        ReferencePage fix = index.function("fix").orElseThrow();

        assertThat(fix.summary()).isNotBlank().isNotEqualTo(fix.title());
        assertThat(Character.isUpperCase(fix.summary().charAt(0))).isTrue();
    }

    @Test
    @DisplayName("finds nothing for a key no page has")
    void unknown() {
        assertThat(index.lookup("no-such-topic")).isEmpty();
        assertThat(index.function("select")).isEmpty();
    }
}
