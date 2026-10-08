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

import picocli.AutoComplete;
import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Completion scripts for the command, generated from its picocli model, so that they
 * cannot drift from the options it parses (design §7.3).
 *
 * <p>bash's is picocli's own, which also runs under zsh through {@code bashcompinit}.
 * fish and PowerShell have none from picocli, so theirs are written here: each completes
 * the commands, then each command's subcommands and options.
 */
final class Completion {

    /** The shells there is a script for. */
    enum Shell {
        BASH, ZSH, FISH, POWERSHELL;

        static Shell of(String name) {
            try {
                return valueOf(name.strip().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    private Completion() {
    }

    /**
     * The completion script for a shell.
     *
     * @param shell the shell
     * @param root  the root command
     * @return the script
     */
    static String script(Shell shell, CommandLine root) {
        String name = root.getCommandName();
        return switch (shell) {
            case BASH, ZSH -> AutoComplete.bash(name, root);
            case FISH -> fish(name, root.getCommandSpec());
            case POWERSHELL -> powershell(name, root.getCommandSpec());
        };
    }

    // -------------------------------------------------------------------------
    // fish
    // -------------------------------------------------------------------------

    private static String fish(String name, CommandSpec root) {
        StringBuilder out = new StringBuilder();
        out.append("# fish completion for ").append(name).append(", generated from its command model.\n");
        out.append("# Install: ").append(name).append(" completion fish > ~/.config/fish/completions/")
                .append(name).append(".fish\n\n");
        out.append("complete -c ").append(name).append(" -f\n");
        fish(out, name, root, List.of());
        return out.toString();
    }

    private static void fish(StringBuilder out, String name, CommandSpec command, List<String> path) {
        String condition = fishCondition(command, path);
        String prefix = "complete -c " + name + " -n " + fishQuote(condition);
        for (Map.Entry<String, CommandSpec> sub : subcommands(command).entrySet()) {
            out.append(prefix).append(" -a ").append(fishQuote(sub.getKey()))
                    .append(" -d ").append(fishQuote(summary(sub.getValue().usageMessage().description())))
                    .append('\n');
        }
        for (OptionSpec option : command.options()) {
            if (option.hidden()) {
                continue;
            }
            StringBuilder line = new StringBuilder(prefix);
            for (String optionName : option.names()) {
                if (optionName.startsWith("--")) {
                    line.append(" -l ").append(fishQuote(optionName.substring(2)));
                } else if (optionName.length() == 2) {
                    line.append(" -s ").append(fishQuote(optionName.substring(1)));
                }
            }
            if (option.arity().max() > 0) {
                line.append(" -r");
            }
            line.append(" -d ").append(fishQuote(summary(option.description())));
            out.append(line).append('\n');
        }
        if (!command.positionalParameters().isEmpty()) {
            out.append(prefix).append(" -F\n");
        }
        for (Map.Entry<String, CommandSpec> sub : subcommands(command).entrySet()) {
            List<String> deeper = new ArrayList<>(path);
            deeper.add(sub.getKey());
            fish(out, name, sub.getValue(), deeper);
        }
    }

    /** Where in the command line a command's completions apply. */
    private static String fishCondition(CommandSpec command, List<String> path) {
        List<String> parts = new ArrayList<>();
        if (path.isEmpty()) {
            parts.add("__fish_use_subcommand");
        } else {
            for (String step : path) {
                parts.add("__fish_seen_subcommand_from " + step);
            }
            if (!command.subcommands().isEmpty()) {
                parts.add("not __fish_seen_subcommand_from " + String.join(" ", subcommands(command).keySet()));
            }
        }
        return String.join("; and ", parts);
    }

    private static String fishQuote(String text) {
        return "'" + text.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }

    // -------------------------------------------------------------------------
    // PowerShell
    // -------------------------------------------------------------------------

    private static String powershell(String name, CommandSpec root) {
        Map<String, List<String>> words = new LinkedHashMap<>();
        powershell(words, root, "");
        StringBuilder out = new StringBuilder();
        out.append("# PowerShell completion for ").append(name).append(", generated from its command model.\n");
        out.append("# Install: ").append(name).append(" completion powershell >> $PROFILE\n\n");
        out.append("Register-ArgumentCompleter -Native -CommandName ").append(psQuote(name))
                .append(" -ScriptBlock {\n");
        out.append("    param($wordToComplete, $commandAst, $cursorPosition)\n");
        out.append("    $words = @{\n");
        for (Map.Entry<String, List<String>> entry : words.entrySet()) {
            out.append("        ").append(psQuote(entry.getKey())).append(" = @(");
            List<String> quoted = entry.getValue().stream().map(Completion::psQuote).toList();
            out.append(String.join(", ", quoted)).append(")\n");
        }
        out.append("    }\n");
        out.append("    $path = ''\n");
        out.append("    foreach ($element in $commandAst.CommandElements | Select-Object -Skip 1) {\n");
        out.append("        if ($element.Extent.StartOffset -ge $cursorPosition) { break }\n");
        out.append("        $candidate = ($path + ' ' + $element.ToString()).Trim()\n");
        out.append("        if ($words.ContainsKey($candidate)) { $path = $candidate }\n");
        out.append("    }\n");
        out.append("    $words[$path] | Where-Object { $_ -like \"$wordToComplete*\" } | ForEach-Object {\n");
        out.append("        [System.Management.Automation.CompletionResult]::new($_, $_, 'ParameterValue', $_)\n");
        out.append("    }\n");
        out.append("}\n");
        return out.toString();
    }

    private static void powershell(Map<String, List<String>> words, CommandSpec command, String path) {
        List<String> here = new ArrayList<>(subcommands(command).keySet());
        for (OptionSpec option : command.options()) {
            if (!option.hidden()) {
                here.addAll(List.of(option.names()));
            }
        }
        words.put(path, here);
        for (Map.Entry<String, CommandSpec> sub : subcommands(command).entrySet()) {
            powershell(words, sub.getValue(), (path + " " + sub.getKey()).strip());
        }
    }

    private static String psQuote(String text) {
        return "'" + text.replace("'", "''") + "'";
    }

    // -------------------------------------------------------------------------

    /** A command's subcommands by their names, aliases left out. */
    private static Map<String, CommandSpec> subcommands(CommandSpec command) {
        Map<String, CommandSpec> named = new LinkedHashMap<>();
        command.subcommands().forEach((key, sub) -> {
            if (key.equals(sub.getCommandName())) {
                named.put(key, sub.getCommandSpec());
            }
        });
        return named;
    }

    /**
     * The first line of a description that has any text, without picocli's line breaks
     * ({@code %n}) and with its variables removed.
     */
    private static String summary(String[] description) {
        for (String line : String.join("%n", description).split("%n")) {
            String text = line.replaceAll("(?<!\\$)\\$\\{[A-Z-]+}", "").replace("$${", "${").strip();
            if (!text.isEmpty()) {
                return text;
            }
        }
        return "";
    }
}
