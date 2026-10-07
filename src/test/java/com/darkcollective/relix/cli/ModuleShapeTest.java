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
package com.darkcollective.relix.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The command is one module and no library.
 *
 * <p>Everything in it is the command's implementation, so it exports nothing for another
 * program to depend on; and the reference-page index is the engine's ({@code relix-docs}),
 * so the earlier console's copy of it is not here. The build publishes nothing either,
 * which {@code checkNoPublication} holds.
 */
class ModuleShapeTest {

    @Test
    void exportsNothing() throws URISyntaxException {
        ModuleDescriptor descriptor = ModuleFinder.of(mainClasses()).findAll().stream()
                .findFirst().orElseThrow(() -> new AssertionError("the main classes are not a module"))
                .descriptor();

        assertThat(descriptor.name()).isEqualTo("com.darkcollective.relix.cli");
        assertThat(descriptor.exports()).as("exported packages").isEmpty();
        assertThat(descriptor.opens()).as("opened packages").isEmpty();
    }

    @Test
    void carriesNoReferenceIndex() throws URISyntaxException, IOException {
        List<String> index;
        try (Stream<Path> walk = Files.walk(mainClasses())) {
            index = walk.map(p -> p.getFileName().toString())
                    .filter(n -> n.matches("(DocRegistry|DocEntry)(\\$.*)?\\.class"))
                    .toList();
        }
        assertThat(index).as("classes of the earlier reference index").isEmpty();
    }

    private static Path mainClasses() throws URISyntaxException {
        return Path.of(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    }
}
