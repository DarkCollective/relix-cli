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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicContainer;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs every example in the command-line section of the programming guide and compares
 * what it writes to standard output, and its exit status, with what the page shows
 * (design §7.4), so that no page can be published with an example the command does not
 * honour.
 *
 * <p>An example is a {@code shell} block; the plain block on the line after it is its
 * standard output, and an example with none must print nothing. Its exit status must be
 * 0, unless it ends {@code ; echo "exit $?"}, which prints the status for the page to
 * show. Standard error is not compared, except where an example sends it to standard
 * output with {@code 2>&1}.
 *
 * <p>Each page runs in a fresh copy of the fixture ({@link ExampleRunner}), its examples
 * in order, so that one may build on what an earlier one did. The project starts trusted,
 * except on the pages listed in {@link #UNTRUSTED}, which show trusting it.
 *
 * <p>Comparison forgives trailing whitespace on a line, which a table pads with and an
 * editor strips, and the path of the fixture's home directory, written {@code ~}. Nothing
 * else.
 *
 * <p>The examples run where {@code bash} is a POSIX shell, so not on Windows; the pages
 * are written for one. Every other platform the build runs on runs them all.
 */
@DisabledOnOs(OS.WINDOWS)
@DisplayName("The command-line guide's examples print what the pages show")
class GuideExamplesTest {

    /** Pages whose project starts untrusted, because they show trusting it. */
    private static final Set<String> UNTRUSTED = Set.of("catalog.md");

    @TempDir
    Path root;

    @TestFactory
    List<DynamicNode> everyExample() {
        Path directory = GuidePages.directory();
        List<DynamicNode> pages = new ArrayList<>();
        for (String page : GuidePages.files(directory)) {
            List<GuidePages.Example> examples = GuidePages.examples(GuidePages.lines(directory.resolve(page)));
            if (examples.isEmpty()) {
                continue;
            }
            AtomicReference<ExampleRunner> runner = new AtomicReference<>();
            List<DynamicTest> tests = new ArrayList<>();
            for (GuidePages.Example example : examples) {
                tests.add(DynamicTest.dynamicTest(page + ":" + example.line(), () -> {
                    if (runner.get() == null) {
                        Path fixture = Files.createDirectories(root.resolve(page.replace('/', '_')));
                        runner.set(new ExampleRunner(fixture, !UNTRUSTED.contains(page)));
                    }
                    check(page, example, runner.get().run(example.command()));
                }));
            }
            pages.add(DynamicContainer.dynamicContainer(page, tests));
        }
        assertThat(pages).as("pages with examples").isNotEmpty();
        return pages;
    }

    private static void check(String page, GuidePages.Example example, ExampleRunner.Output output) {
        String where = page + ":" + example.line() + "\n" + example.command() + "\n";
        assertThat(output.status())
                .as(where + "exits 0 (end it with ; echo \"exit $?\" to show another status)\n" + output)
                .isZero();
        assertThat(normalise(output.out()))
                .as(where + output)
                .isEqualTo(example.expected() == null ? "" : normalise(example.expected()));
    }

    private static String normalise(String text) {
        return text.lines().map(String::stripTrailing).reduce((a, b) -> a + "\n" + b).orElse("").stripTrailing();
    }

    @Test
    @DisplayName("every command a page shows a reader is a shell example, which runs")
    void noUnrunCommandBlocks() {
        Path directory = GuidePages.directory();
        List<String> found = new ArrayList<>();
        for (String page : GuidePages.files(directory)) {
            for (GuidePages.Block block : GuidePages.blocks(GuidePages.lines(directory.resolve(page)))) {
                if (Set.of("bash", "sh", "console", "zsh", "shell-session").contains(block.lang())) {
                    found.add(page + ":" + block.line() + " (" + block.lang() + ")");
                }
            }
        }
        assertThat(found).as("blocks the examples test would not run; write them as ```shell").isEmpty();
    }

    static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
