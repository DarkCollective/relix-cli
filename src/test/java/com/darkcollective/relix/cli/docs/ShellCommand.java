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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An example that is one {@code relix} command and nothing a shell would have to do for
 * it, so that it can run inside the test's JVM rather than through {@code bash}.
 *
 * <p>The forms recognised are the ones the guide's pages write:
 *
 * <pre>
 * [NAME=VALUE ...] relix ARG ... [&lt; FILE | &lt;&lt;'EOF' ... EOF] [2&gt;&amp;1 | 2&gt;/dev/null] [; echo "exit $?"]
 * </pre>
 *
 * <p>An argument may be quoted with single or double quotes or escaped with a backslash,
 * and a line may continue with a trailing backslash. Anything else a shell would act on
 * — a pipe, an expansion, a glob, a {@code ~}, a second command — makes the example not
 * one of these, and it runs through {@code bash}. The test errs that way: an example
 * this class wrongly took for a plain command would run differently from how a reader's
 * shell runs it, while one it wrongly passes to {@code bash} is only slower.
 *
 * @param environment the {@code NAME=VALUE} words before {@code relix}
 * @param arguments   the words after it
 * @param stdinFile   the file standard input is redirected from, or {@code null}
 * @param heredoc     the here-document standard input holds, or {@code null}
 * @param mergeStderr whether {@code 2>&1} sends standard error to standard output
 * @param echoStatus  whether the command is followed by {@code echo "exit $?"}
 */
record ShellCommand(Map<String, String> environment, List<String> arguments, String stdinFile,
                    String heredoc, boolean mergeStderr, boolean echoStatus) {

    /** What prints the exit status after an example, so the page can show it. */
    static final String ECHO_STATUS = "; echo \"exit $?\"";

    private static final Pattern ASSIGNMENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*=.*", Pattern.DOTALL);
    private static final Pattern HEREDOC = Pattern.compile("<<-?'([A-Za-z_]+)'");

    /**
     * Reads an example as a plain {@code relix} command, if it is one.
     *
     * @param text the example, as the page's {@code shell} block holds it
     * @return the command, or empty when a shell has to run it
     */
    static Optional<ShellCommand> parse(String text) {
        String body = text.strip();
        String heredoc = null;
        int newline = body.indexOf('\n');
        Matcher here = HEREDOC.matcher(newline < 0 ? body : body.substring(0, newline));
        if (here.find()) {
            // relix ... <<'EOF' on the first line, the document after it, EOF alone last.
            String delimiter = here.group(1);
            List<String> lines = body.lines().toList();
            if (lines.size() < 2 || !lines.getLast().equals(delimiter)) {
                return Optional.empty();
            }
            heredoc = String.join("\n", lines.subList(1, lines.size() - 1)) + "\n";
            body = lines.getFirst().substring(0, here.start()) + lines.getFirst().substring(here.end());
        } else {
            body = body.replace("\\\n", " ");
            if (body.contains("\n")) {
                return Optional.empty();
            }
        }

        boolean echoStatus = false;
        if (body.endsWith(ECHO_STATUS)) {
            echoStatus = true;
            body = body.substring(0, body.length() - ECHO_STATUS.length());
        }

        List<String> words = words(body);
        if (words == null) {
            return Optional.empty();
        }
        Map<String, String> environment = new LinkedHashMap<>();
        int i = 0;
        while (i < words.size() && ASSIGNMENT.matcher(words.get(i)).matches()) {
            String word = words.get(i++);
            int equals = word.indexOf('=');
            environment.put(word.substring(0, equals), word.substring(equals + 1));
        }
        if (i >= words.size() || !words.get(i).equals("relix")) {
            return Optional.empty();
        }
        i++;

        List<String> arguments = new ArrayList<>();
        String stdinFile = null;
        boolean mergeStderr = false;
        for (; i < words.size(); i++) {
            String word = words.get(i);
            switch (word) {
                case "\u0000<" -> {
                    if (i + 1 >= words.size() || stdinFile != null || heredoc != null) {
                        return Optional.empty();
                    }
                    stdinFile = words.get(++i);
                }
                case "\u00002>&1" -> mergeStderr = true;
                case "\u00002>/dev/null" -> {
                    // Standard error is not compared, so discarding it changes nothing.
                }
                default -> {
                    if (word.startsWith("\u0000")) {
                        return Optional.empty();
                    }
                    arguments.add(word);
                }
            }
        }
        return Optional.of(new ShellCommand(environment, arguments, stdinFile, heredoc, mergeStderr, echoStatus));
    }

    /**
     * Splits a line into words as a shell would, or returns {@code null} when the line
     * holds anything a shell would do more with than quote. The redirections this class
     * knows come back as words of their own, marked with a leading NUL that no word typed
     * on a page can hold.
     */
    private static List<String> words(String line) {
        List<String> words = new ArrayList<>();
        StringBuilder word = null;
        int i = 0;
        while (i < line.length()) {
            char c = line.charAt(i);
            if (c == ' ' || c == '\t') {
                if (word != null) {
                    words.add(word.toString());
                    word = null;
                }
                i++;
                continue;
            }
            if (word == null) {
                if (line.startsWith("2>&1", i) || line.startsWith("2>/dev/null", i)) {
                    String redirect = line.startsWith("2>&1", i) ? "2>&1" : "2>/dev/null";
                    int end = i + redirect.length();
                    if (end == line.length() || line.charAt(end) == ' ') {
                        words.add("\u0000" + redirect);
                        i = end;
                        continue;
                    }
                }
                if (c == '<') {
                    words.add("\u0000<");
                    i++;
                    continue;
                }
                if (c == '~' || c == '#') {
                    return null;
                }
                word = new StringBuilder();
            }
            switch (c) {
                case '\'' -> {
                    int close = line.indexOf('\'', i + 1);
                    if (close < 0) {
                        return null;
                    }
                    word.append(line, i + 1, close);
                    i = close + 1;
                }
                case '"' -> {
                    i++;
                    while (i < line.length() && line.charAt(i) != '"') {
                        char d = line.charAt(i);
                        if (d == '$' || d == '`' || d == '!') {
                            return null;
                        }
                        if (d == '\\' && i + 1 < line.length() && "\"\\".indexOf(line.charAt(i + 1)) >= 0) {
                            d = line.charAt(++i);
                        }
                        word.append(d);
                        i++;
                    }
                    if (i >= line.length()) {
                        return null;
                    }
                    i++;
                }
                case '\\' -> {
                    if (i + 1 >= line.length()) {
                        return null;
                    }
                    word.append(line.charAt(i + 1));
                    i += 2;
                }
                default -> {
                    if ("|&;<>()$`*?[]{}!".indexOf(c) >= 0) {
                        return null;
                    }
                    word.append(c);
                    i++;
                }
            }
        }
        if (word != null) {
            words.add(word.toString());
        }
        return words;
    }
}
