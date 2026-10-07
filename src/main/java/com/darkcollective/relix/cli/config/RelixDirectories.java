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
package com.darkcollective.relix.cli.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The {@code .relix/} directories that give a run its context, outermost first (design
 * §5.2).
 *
 * <p>The user's own {@code ~/.relix} comes first. Then, walking up from the working
 * directory and stopping after {@code $HOME}, each directory's {@code .relix/}; a
 * {@code .relix/} holding a {@code root} file ends the walk there, so the projects of a
 * monorepo do not inherit each other. Outside {@code $HOME}, only the working directory's
 * own {@code .relix/} is read.
 */
public final class RelixDirectories {

    /** The directory name. */
    public static final String NAME = ".relix";

    /** The marker that ends the walk at its directory. */
    public static final String ROOT_MARKER = "root";

    private RelixDirectories() {
    }

    /**
     * The {@code .relix/} directories that exist, outermost first.
     *
     * @param workingDirectory where the run starts, absolute
     * @param home             the user's home directory, absolute
     * @return the directories, the user's first and the nearest last
     */
    public static List<Path> discover(Path workingDirectory, Path home) {
        Path user = home.resolve(NAME);
        List<Path> nearestFirst = new ArrayList<>();
        if (workingDirectory.startsWith(home)) {
            for (Path dir = workingDirectory; dir != null && dir.startsWith(home); dir = dir.getParent()) {
                Path relix = dir.resolve(NAME);
                if (!relix.equals(user) && Files.isDirectory(relix)) {
                    nearestFirst.add(relix);
                    if (Files.exists(relix.resolve(ROOT_MARKER))) {
                        break;
                    }
                }
            }
        } else {
            Path relix = workingDirectory.resolve(NAME);
            if (!relix.equals(user) && Files.isDirectory(relix)) {
                nearestFirst.add(relix);
            }
        }
        List<Path> outermostFirst = new ArrayList<>();
        if (Files.isDirectory(user)) {
            outermostFirst.add(user);
        }
        Collections.reverse(nearestFirst);
        outermostFirst.addAll(nearestFirst);
        return List.copyOf(outermostFirst);
    }
}
