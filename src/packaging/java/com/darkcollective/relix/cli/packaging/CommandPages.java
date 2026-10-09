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

import com.darkcollective.relix.cli.command.RelixCommand;
import com.darkcollective.relix.cli.io.Host;
import com.darkcollective.relix.cli.io.Interruption;
import picocli.CommandLine;
import picocli.CommandLine.Help;
import picocli.CommandLine.Model.ArgSpec;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;
import picocli.CommandLine.Model.PositionalParamSpec;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Writes the command reference of the programming guide's command-line section: a
 * Markdown page per command, from the same picocli model {@code --help} and the man pages
 * are printed from, so the three cannot drift (design §7.4), and {@code index.json}, the
 * section's machine-readable index.
 *
 * <p>A page is the command's description, usage, arguments, options, subcommands and exit
 * status, then the hand-written part from {@code docs/command-examples/<command>.md}: its
 * examples, which the examples test runs like every other page's. The root command's page,
 * {@code relix.md}, holds the global options every command takes; the others link to it
 * rather than repeat them.
 *
 * <p>The pages are checked in, so that a change to the command's options shows up in its
 * documentation in the same diff; {@code CommandPagesTest} fails when they are stale, and
 * {@code ./gradlew commandPages} rewrites them.
 */
public final class CommandPages {

    /** The section's pages, relative to the repository. */
    public static final String GUIDE = "docs/guide/command-line";

    /** The hand-written part of each command page, relative to the repository. */
    public static final String EXAMPLES = "docs/command-examples";

    /** Where the command pages go, relative to the section. */
    public static final String COMMANDS = "commands";

    /** The section's machine-readable index, relative to the section. */
    public static final String INDEX = "index.json";

    /** The width the usage synopsis is wrapped to. */
    private static final int WIDTH = 80;

    private static final Pattern INDEX_CHAPTER = Pattern.compile("^##\\s+(.+?)\\s*$");
    private static final Pattern INDEX_TITLE = Pattern.compile("^#\\s+(.+?)\\s*$");
    private static final Pattern LINK = Pattern.compile("\\[([^\\]]+)]\\(([^)#\\s]+\\.md)\\)");

    private CommandPages() {
    }

    /**
     * Writes the pages and the index.
     *
     * @param args the repository's directory
     * @throws IOException if one cannot be written
     */
    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            throw new IllegalArgumentException("usage: CommandPages REPOSITORY");
        }
        Path repository = Path.of(args[0]);
        Path guide = repository.resolve(GUIDE);
        Map<String, String> files = generate(repository.resolve(EXAMPLES), guide);
        Path commands = guide.resolve(COMMANDS);
        Files.createDirectories(commands);
        try (var stale = Files.list(commands)) {
            for (Path file : stale.toList()) {
                if (!files.containsKey(COMMANDS + "/" + file.getFileName())) {
                    Files.delete(file);
                }
            }
        }
        for (Map.Entry<String, String> file : files.entrySet()) {
            Files.writeString(guide.resolve(file.getKey()), file.getValue(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Every file this writes, by its path relative to the section.
     *
     * @param examples the directory of the command pages' hand-written parts
     * @param guide    the section, whose {@code README.md} the index is read from
     * @return the command pages and {@code index.json}
     */
    public static Map<String, String> generate(Path examples, Path guide) {
        CommandSpec root = new CommandLine(new RelixCommand(Host.system(), new Interruption())).getCommandSpec();
        Map<String, String> files = new LinkedHashMap<>();
        List<CommandSpec> commands = new ArrayList<>();
        commands.add(root);
        commands.addAll(visible(root));
        for (CommandSpec command : commands) {
            String name = fileName(command);
            files.put(COMMANDS + "/" + name, page(command, root, examples.resolve(name)));
        }
        files.put(INDEX, index(guide.resolve("README.md"), commands));
        return files;
    }

    // ── a command's page ─────────────────────────────────────────────────────────────

    private static String page(CommandSpec command, CommandSpec root, Path examples) {
        boolean isRoot = command == root;
        StringBuilder md = new StringBuilder();
        md.append("# ").append(command.qualifiedName()).append("\n\n");
        description(md, command);
        section(md, command, "##", isRoot);
        if (isRoot) {
            md.append("## Global options\n\n");
            md.append("Every command takes these, before or after its name.\n\n");
            options(md, global(root));
            md.append("## Commands\n\n");
            md.append("| Command | What it does |\n|---|---|\n");
            for (CommandSpec sub : visible(root)) {
                md.append("| [`").append(sub.qualifiedName()).append("`](").append(fileName(sub)).append(") | ")
                        .append(cell(firstSentence(sub))).append(" |\n");
            }
            md.append('\n');
            exitStatus(md, root, "##");
        }
        for (CommandSpec sub : visible(command)) {
            if (isRoot) {
                break;
            }
            md.append("## ").append(sub.qualifiedName()).append("\n\n");
            description(md, sub);
            section(md, sub, "###", false);
        }
        String handWritten = read(examples);
        if (handWritten != null) {
            md.append(handWritten.strip()).append('\n');
        }
        return md.toString().replaceAll("\n{3,}", "\n\n").strip() + "\n";
    }

    /** Usage, arguments, options and exit status, under headings of {@code level}. */
    private static void section(StringBuilder md, CommandSpec command, String level, boolean isRoot) {
        md.append(level).append(" Usage").append("\n\n```text\n")
                .append(synopsis(command)).append("```\n\n");
        List<PositionalParamSpec> positionals = command.positionalParameters().stream()
                .filter(p -> !p.hidden()).toList();
        if (!positionals.isEmpty()) {
            md.append(level).append(" Arguments\n\n| Argument | Description |\n|---|---|\n");
            for (PositionalParamSpec p : positionals) {
                md.append("| `").append(p.paramLabel()).append(p.arity().max() > 1 ? "...`" : "`").append(" | ")
                        .append(cell(description(p))).append(" |\n");
            }
            md.append('\n');
        }
        List<OptionSpec> own = command.options().stream()
                .filter(o -> !o.hidden() && !o.inherited() && !o.usageHelp() && !o.versionHelp())
                .filter(o -> !isRoot || !global(command).contains(o))
                .toList();
        if (!own.isEmpty()) {
            md.append(level).append(isRoot ? " Run options" : " Options").append("\n\n");
            if (isRoot) {
                md.append("With no command, `relix` is `relix run`, and takes its options:\n\n");
            }
            options(md, own);
        }
        if (!isRoot) {
            md.append("It also takes the [global options](relix.md#global-options).\n\n");
        }
        if (!isRoot) {
            exitStatus(md, command, level);
        }
    }

    private static void exitStatus(StringBuilder md, CommandSpec command, String level) {
        Map<String, String> exits = command.usageMessage().exitCodeList();
        if (!exits.isEmpty()) {
            md.append(level).append(" Exit status\n\n| Status | Meaning |\n|---|---|\n");
            exits.forEach((code, meaning) -> md.append("| ").append(code.strip()).append(" | ")
                    .append(cell(meaning)).append(" |\n"));
            md.append('\n');
        }
    }

    private static void options(StringBuilder md, List<OptionSpec> options) {
        md.append("| Option | Description |\n|---|---|\n");
        for (OptionSpec o : options) {
            md.append("| ").append(optionNames(o)).append(" | ").append(cell(description(o))).append(" |\n");
        }
        md.append('\n');
    }

    private static String optionNames(OptionSpec option) {
        String label = option.typeInfo().isBoolean() || option.arity().max() == 0 ? "" : option.paramLabel();
        List<String> names = new ArrayList<>();
        for (String name : option.names()) {
            boolean last = name.equals(option.longestName());
            if (label.isEmpty() || !last) {
                names.add("`" + name + "`");
            } else {
                String separator = name.startsWith("--") ? "=" : " ";
                names.add("`" + name + separator + label + "`");
            }
        }
        return String.join(", ", names);
    }

    private static List<OptionSpec> global(CommandSpec root) {
        CommandSpec mixin = root.mixins().get("options");
        Set<String> names = mixin.options().stream().map(OptionSpec::longestName).collect(Collectors.toSet());
        List<OptionSpec> global = new ArrayList<>();
        for (OptionSpec o : root.options()) {
            if (names.contains(o.longestName()) || o.usageHelp() || o.versionHelp()) {
                global.add(o);
            }
        }
        return global;
    }

    private static void description(StringBuilder md, CommandSpec command) {
        List<String> code = new ArrayList<>();
        List<String> prose = new ArrayList<>();
        for (String line : command.usageMessage().description()) {
            for (String part : line.split("%n", -1)) {
                if (part.startsWith("  ") && !part.isBlank()) {
                    flush(md, prose);
                    code.add(part.strip());
                    continue;
                }
                if (!code.isEmpty()) {
                    md.append("```text\n").append(String.join("\n", code)).append("\n```\n\n");
                    code.clear();
                }
                if (part.isBlank()) {
                    flush(md, prose);
                } else {
                    prose.add(part.strip());
                }
            }
        }
        flush(md, prose);
        if (!code.isEmpty()) {
            md.append("```text\n").append(String.join("\n", code)).append("\n```\n\n");
        }
    }

    private static void flush(StringBuilder md, List<String> prose) {
        if (!prose.isEmpty()) {
            md.append(String.join(" ", prose)).append("\n\n");
            prose.clear();
        }
    }

    private static String synopsis(CommandSpec command) {
        command.usageMessage().autoWidth(false).width(WIDTH).abbreviateSynopsis(true);
        Help help = new Help(command, Help.defaultColorScheme(Help.Ansi.OFF));
        return help.synopsis(0);
    }

    private static String description(ArgSpec arg) {
        return String.join(" ", arg.description()).replaceAll("\\s+", " ").strip();
    }

    private static String firstSentence(CommandSpec command) {
        String text = String.join(" ", command.usageMessage().description())
                .replace("%n", " ").replaceAll("\\s+", " ").strip();
        int stop = text.indexOf(". ");
        String sentence = stop < 0 ? text : text.substring(0, stop + 1);
        return sentence.endsWith(".") ? sentence : sentence + ".";
    }

    private static String cell(String text) {
        return text.replace("|", "\\|");
    }

    private static List<CommandSpec> visible(CommandSpec command) {
        return command.subcommands().entrySet().stream()
                .filter(e -> e.getKey().equals(e.getValue().getCommandSpec().name()))
                .map(e -> e.getValue().getCommandSpec())
                .filter(c -> !c.usageMessage().hidden())
                .toList();
    }

    /** {@code relix.md} for the root, {@code run.md} for {@code relix run}. */
    private static String fileName(CommandSpec command) {
        return command.name() + ".md";
    }

    // ── index.json ───────────────────────────────────────────────────────────────────

    /**
     * The section's index as JSON: each page in reading order, with its chapter, its title
     * and what the index says it covers, and for a command page the command and its
     * subcommands, each with the anchor of its section.
     */
    private static String index(Path readme, List<CommandSpec> commands) {
        StringBuilder json = new StringBuilder();
        json.append("{\n  \"title\": ").append(string(title(readme))).append(",\n  \"pages\": [\n");
        List<String> entries = new ArrayList<>();
        Map<String, CommandSpec> byFile = new LinkedHashMap<>();
        for (CommandSpec command : commands) {
            byFile.put(COMMANDS + "/" + fileName(command), command);
        }
        String chapter = null;
        for (String line : lines(readme)) {
            Matcher heading = INDEX_CHAPTER.matcher(line);
            if (heading.matches()) {
                chapter = heading.group(1);
                continue;
            }
            Matcher link = LINK.matcher(line);
            if (chapter == null || !line.startsWith("|") || !link.find()) {
                continue;
            }
            String path = link.group(2);
            String title = link.group(1).replace("`", "");
            String[] cells = line.strip().split("(?<!\\\\)\\|");
            String summary = cells.length > 2 ? cells[2].strip().replace("\\|", "|") : "";
            StringBuilder entry = new StringBuilder();
            entry.append("    {\"path\": ").append(string(path))
                    .append(", \"chapter\": ").append(string(chapter))
                    .append(", \"title\": ").append(string(title))
                    .append(", \"summary\": ").append(string(summary));
            CommandSpec command = byFile.get(path);
            if (command != null) {
                entry.append(", \"command\": ").append(string(command.qualifiedName()));
                List<String> subs = visible(command).stream()
                        .filter(c -> command.parent() != null)
                        .map(c -> "{\"command\": " + string(c.qualifiedName()) + ", \"anchor\": "
                                + string(slug(c.qualifiedName())) + "}")
                        .toList();
                if (!subs.isEmpty()) {
                    entry.append(", \"subcommands\": [").append(String.join(", ", subs)).append(']');
                }
            }
            entry.append('}');
            entries.add(entry.toString());
        }
        json.append(String.join(",\n", entries)).append("\n  ]\n}\n");
        return json.toString();
    }

    private static String title(Path readme) {
        for (String line : lines(readme)) {
            Matcher title = INDEX_TITLE.matcher(line);
            if (title.matches()) {
                return title.group(1);
            }
        }
        throw new IllegalStateException(readme + " has no title");
    }

    private static String slug(String text) {
        return text.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
    }

    private static String string(String text) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : text.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    private static List<String> lines(Path file) {
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
    }

    private static String read(Path file) {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
    }
}
