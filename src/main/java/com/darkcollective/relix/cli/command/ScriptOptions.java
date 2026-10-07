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

import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.ArrayList;
import java.util.List;

/**
 * Where a command that reads Relix gets its script (design §3.1): {@code -e} texts, or
 * script files, or standard input.
 */
final class ScriptOptions {

    @Option(names = {"-e", "--expr"}, paramLabel = "TEXT",
            description = {
                "Relix text to run. Repeatable: the texts are one script, in order.",
                "A bare relational expression runs as a query."})
    List<String> expressions = new ArrayList<>();

    @Parameters(paramLabel = "SCRIPT", arity = "0..*",
            description = {
                "Script files, each run in a session of its own; - is standard input.",
                "With neither -e nor SCRIPT, standard input is read when it is not a terminal."})
    List<String> files = new ArrayList<>();
}
