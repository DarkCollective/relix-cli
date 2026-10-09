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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The command-line section of the programming guide, {@code docs/guide/command-line}: its
 * pages, and the fenced blocks and links in each.
 *
 * <p>The pages are Markdown in the dialect the site and the engine's manuals share. A
 * fenced block is an <b>example</b> when its language is {@code shell}; the plain fence
 * immediately after it, if there is one, is what the example writes to standard output.
 */
final class GuidePages {

    /** The section's directory, relative to the repository. */
    static final Path DIRECTORY = Path.of("docs/guide/command-line");

    /** The language of a block the examples test runs. */
    static final String EXAMPLE = "shell";

    private static final Pattern FENCE = Pattern.compile("^```\\s*([A-Za-z0-9_-]*)\\s*$");
    private static final Pattern LINK = Pattern.compile("\\[([^\\]]*)]\\(([^)\\s]+)\\)");
    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*$");

    private GuidePages() {
    }

    /**
     * A fenced block.
     *
     * @param lang its language, empty for a plain fence
     * @param body its lines, joined
     * @param line the line of its opening fence, from 1
     */
    record Block(String lang, String body, int line) {
    }

    /**
     * An example and what it prints.
     *
     * @param line     the line of its opening fence
     * @param command  the block's text
     * @param expected the plain block after it, or {@code null} when it prints nothing
     */
    record Example(int line, String command, String expected) {
    }

    /**
     * A link written in a page.
     *
     * @param line the line it is on
     * @param href its target, as written
     */
    record Link(int line, String href) {
    }

    /** {@return the section's directory: {@code relix.cli.guide}, or the repository's} */
    static Path directory() {
        String property = System.getProperty("relix.cli.guide");
        return property != null ? Path.of(property) : DIRECTORY;
    }

    /**
     * Every Markdown file of the section, as paths relative to its directory.
     *
     * @param directory the section's directory
     * @return the files, in path order
     */
    static List<String> files(Path directory) {
        try (Stream<Path> walk = Files.walk(directory)) {
            return walk.filter(p -> p.toString().endsWith(".md"))
                    .map(p -> directory.relativize(p).toString().replace('\\', '/'))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static List<String> lines(Path file) {
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The fenced blocks of a page.
     *
     * @param lines the page
     * @return its blocks, in order
     */
    static List<Block> blocks(List<String> lines) {
        List<Block> blocks = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            Matcher open = FENCE.matcher(lines.get(i));
            if (!open.matches()) {
                continue;
            }
            int start = i;
            List<String> body = new ArrayList<>();
            for (i++; i < lines.size() && !lines.get(i).strip().equals("```"); i++) {
                body.add(lines.get(i));
            }
            blocks.add(new Block(open.group(1), String.join("\n", body), start + 1));
        }
        return blocks;
    }

    /**
     * The examples of a page: each {@code shell} block, paired with the plain block that
     * follows on the very next line.
     *
     * @param lines the page
     * @return its examples, in order
     */
    static List<Example> examples(List<String> lines) {
        List<Block> blocks = blocks(lines);
        List<Example> examples = new ArrayList<>();
        for (int i = 0; i < blocks.size(); i++) {
            Block block = blocks.get(i);
            if (!block.lang().equals(EXAMPLE)) {
                continue;
            }
            String expected = null;
            if (i + 1 < blocks.size()) {
                Block next = blocks.get(i + 1);
                int closing = block.line() + (int) block.body().lines().count() + 1;
                if (next.lang().isEmpty() && next.line() == closing + 1) {
                    expected = next.body();
                    i++;
                }
            }
            examples.add(new Example(block.line(), block.body(), expected));
        }
        return examples;
    }

    /**
     * The links of a page, outside its fenced blocks and code spans.
     *
     * @param lines the page
     * @return its links, in order
     */
    static List<Link> links(List<String> lines) {
        List<Link> links = new ArrayList<>();
        boolean fenced = false;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (FENCE.matcher(line).matches()) {
                fenced = !fenced;
                continue;
            }
            if (fenced) {
                continue;
            }
            Matcher link = LINK.matcher(line.replaceAll("`[^`]*`", ""));
            while (link.find()) {
                links.add(new Link(i + 1, link.group(2)));
            }
        }
        return links;
    }

    /**
     * The anchors a page's headings get on the site: the heading's text, lower-cased, with
     * each run of anything but a letter or digit made a hyphen.
     *
     * @param lines the page
     * @return its anchors
     */
    static List<String> anchors(List<String> lines) {
        List<String> anchors = new ArrayList<>();
        boolean fenced = false;
        for (String line : lines) {
            if (FENCE.matcher(line).matches()) {
                fenced = !fenced;
                continue;
            }
            Matcher heading = HEADING.matcher(line);
            if (!fenced && heading.matches()) {
                anchors.add(slug(heading.group(2).replace("`", "")));
            }
        }
        return anchors;
    }

    static String slug(String text) {
        String slug = text.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        return slug.isEmpty() ? "section" : slug;
    }
}
