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
package com.darkcollective.relix.cli.command;

import com.darkcollective.relix.cli.CommandFailure;
import com.darkcollective.relix.cli.ExitCode;
import com.darkcollective.relix.cli.catalog.Trust;
import com.darkcollective.relix.cli.config.RelixDirectories;

import java.nio.file.Path;
import java.util.List;

/**
 * Where a command that writes under a {@code .relix/} directory writes, and the trust rule
 * every such command follows (design §5.4): a trusted project whose files are as they were
 * trusted stays trusted across the command's own change, and any other stays untrusted.
 * Which of the two happened is said on standard error.
 */
final class ProjectWrite {

    private ProjectWrite() {
    }

    /**
     * The {@code .relix/} directory a command writes: the user's own with {@code --user},
     * otherwise the nearest project's.
     *
     * @param invocation the run
     * @param user       whether {@code --user} was given
     * @return the directory, absolute; it exists, unless it is the user's
     * @throws CommandFailure with {@link ExitCode#USAGE} when there is no project
     */
    static Path level(Invocation invocation, boolean user) {
        Path own = invocation.host().home().resolve(RelixDirectories.NAME);
        if (user) {
            return own;
        }
        List<Path> levels = invocation.levels();
        Path nearest = levels.isEmpty() ? null : levels.getLast();
        if (nearest == null || nearest.equals(own)) {
            throw new CommandFailure(ExitCode.USAGE, "no project " + RelixDirectories.NAME + " directory here or above;"
                    + " make one with relix init, or give --user for " + own);
        }
        return nearest;
    }

    /**
     * Makes a change under a {@code .relix/} directory, keeping its trust as the rule says,
     * and says what became of it.
     *
     * @param invocation the run
     * @param level      the directory
     * @param change     what writes its files
     */
    static void keepingTrust(Invocation invocation, Path level, Runnable change) {
        Trust.Kept kept = invocation.trust().keep(level, change);
        switch (kept) {
            case OWN -> {
                // ~/.relix is the user's own, and always trusted.
            }
            case TRUSTED -> invocation.reporter().info(level + " is still trusted");
            case UNTRUSTED -> invocation.reporter().warning(level + " was not trusted as it was before this"
                    + " change, so it is not trusted now; review it, then: relix catalog trust "
                    + Invocation.shellWord(level.getParent().toString()));
        }
    }
}
