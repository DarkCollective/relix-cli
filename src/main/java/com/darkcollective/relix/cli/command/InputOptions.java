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
import com.darkcollective.relix.cli.io.InputBinding;
import com.darkcollective.relix.embed.Input;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The data a run's scripts read besides their own declarations (design §3.2): relations
 * bound to files, URLs or standard input.
 */
final class InputOptions {

    @Option(names = {"-i", "--input"}, paramLabel = "NAME=[FORMAT:]LOCATION",
            description = {
                "Bind the relation NAME to a file, an http(s) URL (with --remote), or - for",
                "standard input. FORMAT is csv, tsv, json or ndjson; without it the",
                "extension decides, and standard input must name one. Repeatable."})
    List<String> inputs = new ArrayList<>();

    @Option(names = "--schema", paramLabel = "NAME={...}",
            description = "The heading of the input NAME, such as NAME='{ id: NUMBER, name: STRING }', instead of inferring one.")
    Map<String, String> schemas = new LinkedHashMap<>();

    @Option(names = "--infer-rows", paramLabel = "N",
            description = "Infer an input's heading from its first N records (default: ${DEFAULT-VALUE}).")
    int inferRows = Input.DEFAULT_SAMPLE;

    @Option(names = "--unbounded", paramLabel = "NAME",
            description = {
                "The input NAME never ends, as tail -f's does: its rows stream as they arrive,",
                "and a query that would have to read all of it first is refused. Repeatable."})
    List<String> unbounded = new ArrayList<>();

    /**
     * The inputs these options bind.
     *
     * @param directory where relative paths start from
     * @param remote    whether {@code --remote} permits a URL
     * @return the bindings
     */
    Inputs settle(Path directory, boolean remote) {
        if (inferRows < 1) {
            throw new CommandFailure(ExitCode.USAGE, "--infer-rows must be a positive number");
        }
        return new Inputs(InputBinding.resolve(inputs, schemas, unbounded, directory, remote),
                inferRows, List.copyOf(unbounded));
    }

    /**
     * A run's inputs, settled.
     *
     * @param bindings  one per {@code -i}, in order
     * @param sample    how many records a heading is inferred from
     * @param unbounded the names of the inputs that never end
     */
    record Inputs(List<InputBinding> bindings, int sample, List<String> unbounded) {

        /**
         * Whether an input reads standard input, which the script then cannot.
         *
         * @return {@code true} when one binding is {@code -}
         */
        boolean readStdin() {
            return bindings.stream().anyMatch(InputBinding::isStdin);
        }
    }
}
