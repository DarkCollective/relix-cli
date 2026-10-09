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
package com.darkcollective.relix.cli.docs;

import com.darkcollective.relix.cli.packaging.CommandPages;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Holds the command-line section of the programming guide together: every command has a
 * page and the pages are what the command model makes of it now, the index lists every
 * page, and no link between pages, or into the engine's manuals, is broken.
 *
 * <p>The section is rendered by the site inside the engine's programming guide, at
 * {@code guide/command-line/}, so a link out of it is written as if it sat in the
 * engine's {@code docs/guide/command-line}: {@code ../secrets.md} is a guide page and
 * {@code ../../reference/README.md} the language reference. Such a link is resolved here
 * against the pinned engine's manuals, which the build unpacks.
 */
@DisplayName("The command-line guide")
class GuideStructureTest {

    private static final Pattern INDEX_ROW_LINK = Pattern.compile("^\\|\\s*\\[[^\\]]+]\\(([^)#]+\\.md)\\)");

    private final Path guide = GuidePages.directory();

    @Test
    @DisplayName("has the command pages and index the command model makes now")
    void commandPagesAreCurrent() {
        Map<String, String> generated = CommandPages.generate(examples(), guide);
        List<String> stale = new ArrayList<>();
        generated.forEach((path, text) -> {
            Path file = guide.resolve(path);
            if (!Files.isRegularFile(file) || !GuideExamplesTest.read(file).equals(text)) {
                stale.add(path);
            }
        });
        for (String page : GuidePages.files(guide)) {
            if (page.startsWith(CommandPages.COMMANDS + "/") && !generated.containsKey(page)) {
                stale.add(page + " (no such command)");
            }
        }
        assertThat(stale).as("stale command pages; run ./gradlew commandPages").isEmpty();
    }

    @Test
    @DisplayName("gives every hand-written part of a command page a command")
    void everyExamplesFileHasACommand() {
        Set<String> commands = new LinkedHashSet<>();
        for (String path : CommandPages.generate(examples(), guide).keySet()) {
            if (path.startsWith(CommandPages.COMMANDS + "/")) {
                commands.add(path.substring(CommandPages.COMMANDS.length() + 1));
            }
        }
        List<String> orphans = new ArrayList<>();
        for (String file : GuidePages.files(examples())) {
            if (!commands.contains(file)) {
                orphans.add(file);
            }
        }
        assertThat(orphans).as("files in docs/command-examples naming no command").isEmpty();
    }

    @Test
    @DisplayName("lists every page in its index, and links nothing that is not there")
    void indexListsEveryPage() {
        Set<String> listed = new LinkedHashSet<>();
        for (String line : GuidePages.lines(guide.resolve("README.md"))) {
            Matcher row = INDEX_ROW_LINK.matcher(line);
            if (row.find()) {
                listed.add(row.group(1));
            }
        }
        Set<String> pages = new LinkedHashSet<>(GuidePages.files(guide));
        pages.remove("README.md");
        assertThat(listed).as("pages the index lists").containsExactlyInAnyOrderElementsOf(pages);
    }

    @Test
    @DisplayName("has no broken link, to a page or to a heading")
    void noBrokenLinks() {
        Path engine = engineDocs();
        List<String> broken = new ArrayList<>();
        for (String page : GuidePages.files(guide)) {
            List<String> lines = GuidePages.lines(guide.resolve(page));
            for (GuidePages.Link link : GuidePages.links(lines)) {
                String problem = problem(page, link.href(), engine);
                if (problem != null) {
                    broken.add(page + ":" + link.line() + ": " + link.href() + ": " + problem);
                }
            }
        }
        assertThat(broken).isEmpty();
    }

    /** What is wrong with a link written on {@code page}, or {@code null} when nothing is. */
    private String problem(String page, String href, Path engine) {
        if (href.startsWith("https://") || href.startsWith("http://") || href.startsWith("mailto:")) {
            return null;
        }
        int hash = href.indexOf('#');
        String target = hash < 0 ? href : href.substring(0, hash);
        String anchor = hash < 0 ? null : href.substring(hash + 1);
        Path file;
        if (target.isEmpty()) {
            file = guide.resolve(page);
        } else {
            if (!target.endsWith(".md")) {
                return "not a page";
            }
            // The section as it sits in the engine's tree: docs/guide/command-line/<page>.
            Path virtual = Path.of("docs/guide/command-line").resolve(page).resolveSibling(target).normalize();
            String path = virtual.toString().replace('\\', '/');
            if (path.equals("docs/guide/command-line/README.md")) {
                return "the index is not a page of its own on the site; link a page";
            }
            if (path.startsWith("docs/guide/command-line/")) {
                file = guide.resolve(path.substring("docs/guide/command-line/".length()));
            } else if (path.startsWith("docs/")) {
                file = engine.resolve(path);
            } else {
                return "outside the manuals";
            }
        }
        if (!Files.isRegularFile(file)) {
            return "no such page";
        }
        if (anchor != null && !GuidePages.anchors(GuidePages.lines(file)).contains(anchor)) {
            return "no such heading";
        }
        return null;
    }

    private static Path examples() {
        String property = System.getProperty("relix.cli.commandExamples");
        return Path.of(property != null ? property : CommandPages.EXAMPLES);
    }

    private static Path engineDocs() {
        String property = System.getProperty("relix.engine.docs");
        assertThat(property).as("relix.engine.docs, the unpacked engine manuals").isNotBlank();
        return Path.of(property);
    }
}
