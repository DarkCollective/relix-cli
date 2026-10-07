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

import com.darkcollective.relix.embed.Relix;
import com.darkcollective.relix.lang.ast.Script;
import com.darkcollective.relix.semantic.ScriptLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * A {@link ScriptLoader} that reads {@code .relix} files from the local file
 * system and parses them with the {@code .relix} grammar.
 *
 * <p>This is a <em>frontend</em> loader: it is the seam where concrete
 * text syntax meets the engine, which is why it lives here rather than in
 * {@code relix-semantic}.  The engine itself only ever sees the resulting
 * {@link Script}.
 *
 * <p>Paths supplied to {@link #load(String)} are resolved relative to the
 * {@code root} directory supplied at construction time:
 * <pre>
 *   ScriptLoader loader = new FileSystemScriptLoader(Paths.get("/project/scripts"));
 *   Script s = loader.load("./weather-api.relix");
 *   // reads /project/scripts/weather-api.relix
 * </pre>
 *
 * <p>This class is effectively final and thread-safe (the root path is
 * immutable). The root it is constructed with is the directory from which
 * relative paths are resolved; it must not be null.
 */
public final class FileSystemScriptLoader implements ScriptLoader {

    private final Path root;

    /**
     * Creates a loader that resolves paths relative to {@code root}.
     *
     * @param root the base directory; must not be null
     */
    public FileSystemScriptLoader(Path root) {
        this.root = Objects.requireNonNull(root, "root");
    }

    /**
     * Resolves {@code path} against {@link #root()}, reads the file, and parses
     * it as a {@code .relix} script (UTF-8 encoded).
     *
     * @param path the file path, resolved relative to {@link #root()}
     * @return the parsed script
     * @throws IOException if the file cannot be opened or read
     */
    @Override
    public Script load(String path) throws IOException {
        Objects.requireNonNull(path, "path");
        Path resolved = root.resolve(path).normalize();
        return Relix.parse(Files.readString(resolved), resolved.toString());
    }

    /**
     * Returns the root directory used to resolve relative paths.
     *
     * @return the root path; never null
     */
    public Path root() {
        return root;
    }
}
