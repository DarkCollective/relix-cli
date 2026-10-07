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

import com.darkcollective.relix.embed.Relix;

import java.lang.module.ModuleDescriptor;

/**
 * The {@code relix} entry point.
 *
 * <p>For now it only says which command and which engine it is; the commands arrive with
 * the picocli entry that replaces this class's body.
 */
public final class Main {

    private Main() {
    }

    /**
     * Prints the command's version and the engine's.
     *
     * @param args ignored
     */
    public static void main(String[] args) {
        System.out.println(versionLine());
    }

    /**
     * The command's version and the engine's, as {@code relix <version> (engine <version>)}.
     *
     * @return the line {@code relix version} would print
     */
    static String versionLine() {
        return "relix " + version(Main.class) + " (engine " + version(Relix.class) + ")";
    }

    /**
     * The version of the module a class is in, from its descriptor, or from its jar's
     * manifest when it runs on a class path.
     */
    private static String version(Class<?> type) {
        Module module = type.getModule();
        if (module.isNamed() && module.getDescriptor().version().isPresent()) {
            return module.getDescriptor().version().map(ModuleDescriptor.Version::toString).orElseThrow();
        }
        String implementation = type.getPackage().getImplementationVersion();
        return implementation != null ? implementation : "unknown";
    }
}
