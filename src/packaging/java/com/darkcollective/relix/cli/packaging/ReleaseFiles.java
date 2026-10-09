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
package com.darkcollective.relix.cli.packaging;

import com.darkcollective.relix.cli.Main;
import com.darkcollective.relix.cli.command.RelixCommand;
import com.darkcollective.relix.cli.io.Host;
import com.darkcollective.relix.cli.io.Interruption;
import org.asciidoctor.Asciidoctor;
import org.asciidoctor.Options;
import org.asciidoctor.SafeMode;
import picocli.CommandLine;
import picocli.codegen.docgen.manpage.ManPageGenerator;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Writes what the release archive carries beside the image: a man page per command and a
 * completion script per shell, both from the command's picocli model, so that neither can
 * drift from what {@code --help} prints (design §7.3). It runs in the build and is not
 * shipped.
 *
 * <p>The man pages are picocli's {@code ManPageGenerator} AsciiDoc, converted to troff by
 * Asciidoctor's {@code manpage} backend.
 */
public final class ReleaseFiles {

    /** Each shell's script, and the name its file has in the archive. */
    private static final Map<String, String> COMPLETIONS = Map.of(
            "bash", "relix.bash",
            "zsh", "_relix",
            "fish", "relix.fish",
            "powershell", "relix.ps1");

    private ReleaseFiles() {
    }

    /**
     * Writes the man pages and the completion scripts.
     *
     * @param args the directory for the man pages' AsciiDoc, the directory for the pages
     *             themselves ({@code man1}), and the directory for the completion scripts
     * @throws IOException if one cannot be written
     */
    public static void main(String[] args) throws IOException {
        if (args.length != 3) {
            throw new IllegalArgumentException("usage: ReleaseFiles ADOC_DIR MAN1_DIR COMPLETIONS_DIR");
        }
        manPages(Path.of(args[0]), Path.of(args[1]));
        completions(Path.of(args[2]));
    }

    private static void manPages(Path adoc, Path man1) throws IOException {
        Files.createDirectories(adoc);
        Files.createDirectories(man1);
        CommandLine command = new CommandLine(new RelixCommand(Host.system(), new Interruption()));
        int status = ManPageGenerator.generateManPage(adoc.toFile(), null, new boolean[0], true,
                command.getCommandSpec());
        if (status != 0) {
            throw new IOException("ManPageGenerator exited " + status);
        }
        List<Path> pages;
        try (Stream<Path> files = Files.list(adoc)) {
            pages = files.filter(file -> file.toString().endsWith(".adoc")).sorted().toList();
        }
        try (Asciidoctor asciidoctor = Asciidoctor.Factory.create()) {
            for (Path page : pages) {
                asciidoctor.convertFile(page.toFile(), Options.builder()
                        .backend("manpage")
                        .safe(SafeMode.UNSAFE)
                        .toDir(man1.toFile())
                        .mkDirs(true)
                        .build());
            }
        }
    }

    private static void completions(Path directory) throws IOException {
        Files.createDirectories(directory);
        for (Map.Entry<String, String> shell : COMPLETIONS.entrySet()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            Host host = new Host(new ByteArrayInputStream(new byte[0]), out,
                    new PrintStream(err, true, StandardCharsets.UTF_8), Map.of(),
                    Path.of("").toAbsolutePath(), Path.of(System.getProperty("user.home")), false, false);
            int status = Main.run(host, "completion", shell.getKey());
            if (status != 0) {
                throw new IOException("relix completion " + shell.getKey() + " exited " + status + ": "
                        + err.toString(StandardCharsets.UTF_8));
            }
            Files.write(directory.resolve(shell.getValue()), out.toByteArray());
        }
    }
}
