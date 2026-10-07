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

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.spi.ToolProvider;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every source set uses the engine only through what the engine publishes.
 *
 * <p>The main source set is a module, so there the compiler already refuses a package the
 * engine does not export. The tests, and anything else on a class path, can read every
 * package of the engine's jars. This test puts the rule back for all of them: {@code jdeps}
 * lists each engine class the compiled classes reference, and its package must be one an
 * engine artifact's own module descriptor exports to everyone. The export lists are read
 * from the jars on the class path rather than written here, so they move with the pin.
 *
 * <p>The classes come from the build ({@code relix.cli.classesDirs}): every source set's
 * output, this one's included.
 */
class EngineBoundaryTest {

    private static final String ENGINE = "com.darkcollective.relix";
    private static final String OWN = "com.darkcollective.relix.cli";

    /** A jdeps {@code -verbose:class} edge: {@code <from class> -> <to class> <where>}. */
    private static final Pattern EDGE = Pattern.compile(
            "^\\s*(\\S+)\\s+->\\s+(com\\.darkcollective\\.relix\\.[\\w.$]+)", Pattern.MULTILINE);

    @Test
    void everyEnginePackageUsedIsExported() throws IOException {
        Set<String> exported = exportedEnginePackages();
        assertThat(exported).as("packages the engine's artifacts export")
                .contains(ENGINE + ".embed", ENGINE + ".docs");

        Map<String, Set<String>> unexported = new TreeMap<>();
        enginePackagesUsed().forEach((pkg, users) -> {
            if (!exported.contains(pkg)) {
                unexported.put(pkg, users);
            }
        });
        assertThat(unexported).as("engine packages used but not exported, with their users")
                .isEmpty();
    }

    /** The unqualified exports of every engine module on the class path. */
    private static Set<String> exportedEnginePackages() {
        Path[] entries = Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                .map(Path::of)
                .filter(p -> p.toString().endsWith(".jar"))
                .toArray(Path[]::new);
        return ModuleFinder.of(entries).findAll().stream()
                .map(reference -> reference.descriptor())
                .filter(d -> d.name().startsWith(ENGINE) && !d.name().equals(OWN))
                .flatMap(d -> d.exports().stream())
                .filter(e -> !e.isQualified())
                .map(ModuleDescriptor.Exports::source)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    /** Each engine package the build's classes reference, with the classes that do. */
    private static Map<String, Set<String>> enginePackagesUsed() throws IOException {
        List<String> args = new ArrayList<>(List.of("-verbose:class", "-filter:none"));
        args.addAll(classFiles());

        ToolProvider jdeps = ToolProvider.findFirst("jdeps")
                .orElseThrow(() -> new AssertionError("jdeps is not available in this JDK"));
        StringWriter out = new StringWriter();
        int status = jdeps.run(new PrintWriter(out), new PrintWriter(out), args.toArray(String[]::new));
        assertThat(status).as(out.toString()).isZero();

        Map<String, Set<String>> used = new TreeMap<>();
        Matcher edge = EDGE.matcher(out.toString());
        while (edge.find()) {
            String target = edge.group(2);
            String pkg = target.substring(0, target.lastIndexOf('.'));
            if (!pkg.equals(OWN) && !pkg.startsWith(OWN + ".")) {
                used.computeIfAbsent(pkg, k -> new TreeSet<>()).add(edge.group(1));
            }
        }
        assertThat(used).as("engine packages referenced; none means jdeps read nothing")
                .containsKey(ENGINE + ".embed");
        return used;
    }

    /**
     * The class files of every source set. Named one by one, not as directories, so that
     * jdeps reads the main source set's classes as classes rather than as a module whose
     * dependencies it would then have to resolve.
     */
    private static List<String> classFiles() throws IOException {
        String dirs = System.getProperty("relix.cli.classesDirs");
        assertThat(dirs).as("relix.cli.classesDirs, set by the build").isNotBlank();
        List<String> files = new ArrayList<>();
        for (String dir : dirs.split(File.pathSeparator)) {
            try (Stream<Path> walk = Files.walk(Path.of(dir))) {
                walk.filter(p -> p.toString().endsWith(".class"))
                        .filter(p -> !p.getFileName().toString().equals("module-info.class"))
                        .map(Path::toString)
                        .forEach(files::add);
            }
        }
        assertThat(files).as("class files in " + dirs).isNotEmpty();
        return files;
    }
}
