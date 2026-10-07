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
package com.darkcollective.relix.cli.io;

import com.darkcollective.relix.lang.ast.ScriptParseException;
import com.darkcollective.relix.lang.ast.Script;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("FileSystemScriptLoader — file resolution, parsing, error cases")
final class FileSystemScriptLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Loads and parses a valid .relix file")
    void loadsValidFile(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("test.relix");
        Files.writeString(file, "namespace demo;", StandardCharsets.UTF_8);

        var loader = new FileSystemScriptLoader(dir);
        Script script = loader.load("test.relix");

        assertThat(script).isNotNull();
        assertThat(script.namespace()).contains("demo");
    }

    @Test
    @DisplayName("Parses an empty script (no statements)")
    void loadsEmptyScript(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("empty.relix");
        Files.writeString(file, "", StandardCharsets.UTF_8);

        var loader = new FileSystemScriptLoader(dir);
        Script script = loader.load("empty.relix");

        assertThat(script.statements()).isEmpty();
    }

    @Test
    @DisplayName("Resolves path relative to loader root")
    void resolvesRelativePath(@TempDir Path dir) throws IOException {
        Path sub = dir.resolve("sub");
        Files.createDirectory(sub);
        Files.writeString(sub.resolve("api.relix"), "namespace api;", StandardCharsets.UTF_8);

        var loader = new FileSystemScriptLoader(dir);
        Script script = loader.load("sub/api.relix");
        assertThat(script.namespace()).contains("api");
    }

    @Test
    @DisplayName("Throws IOException for non-existent file")
    void throwsForMissingFile(@TempDir Path dir) {
        var loader = new FileSystemScriptLoader(dir);
        assertThatThrownBy(() -> loader.load("missing.relix"))
                .isInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("Throws LangParseException for syntactically invalid script")
    void throwsForInvalidSyntax(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("bad.relix"), "!!! not valid !!!");
        var loader = new FileSystemScriptLoader(dir);
        assertThatThrownBy(() -> loader.load("bad.relix"))
                .isInstanceOf(ScriptParseException.class);
    }

    @Test
    @DisplayName("root() returns the configured root directory")
    void rootAccessor(@TempDir Path dir) {
        var loader = new FileSystemScriptLoader(dir);
        assertThat(loader.root()).isEqualTo(dir);
    }

    @Test
    @DisplayName("Null path argument throws NullPointerException")
    void nullPathThrows(@TempDir Path dir) {
        var loader = new FileSystemScriptLoader(dir);
        assertThatThrownBy(() -> loader.load(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Null root throws NullPointerException at construction")
    void nullRootThrows() {
        assertThatThrownBy(() -> new FileSystemScriptLoader(null))
                .isInstanceOf(NullPointerException.class);
    }
}
