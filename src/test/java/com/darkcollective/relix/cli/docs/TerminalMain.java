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

import com.darkcollective.relix.cli.Main;
import com.darkcollective.relix.cli.io.Host;
import com.darkcollective.relix.cli.io.Interruption;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.nio.file.Path;

/**
 * The {@code relix} an example's {@code bash} runs: the command's own entry point, over a
 * host whose standard streams are terminals where a reader's would be.
 *
 * <p>A reader typing {@code relix -e … | jq} has a terminal on {@code relix}'s standard
 * input, and one on {@code jq}'s standard output; the example's run has files there, which
 * {@link ExampleRunner} gives the shim. The shim compares each stream with them and says
 * which is "the terminal" in two system properties, so that the command chooses the
 * format, and whether to read a script from standard input, as it would for the reader.
 */
public final class TerminalMain {

    private TerminalMain() {
    }

    /**
     * Runs the command and exits with its status.
     *
     * @param args the command line
     */
    public static void main(String[] args) {
        Interruption interruption = new Interruption();
        Runtime.getRuntime().addShutdownHook(new Thread(interruption::interrupt, "relix-interrupt"));
        Host host = new Host(System.in, new FileOutputStream(FileDescriptor.out), System.err, System.getenv(),
                Path.of(System.getProperty("user.dir")), Path.of(System.getProperty("user.home")),
                Boolean.getBoolean("relix.docs.stdinIsTerminal"), Boolean.getBoolean("relix.docs.stdoutIsTerminal"));
        System.exit(Main.run(host, interruption, args));
    }
}
