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
import com.darkcollective.relix.embed.Relix;
import com.darkcollective.relix.lang.ast.ScriptParseException;
import com.darkcollective.relix.lang.ast.ScriptPrinter;
import com.darkcollective.relix.lang.ast.Statement;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One {@code source} or {@code connection} declaration as {@code relix source} and
 * {@code relix connection} build it from flags and change it: its name, what it is
 * {@code from}, a location for a file kind, and its options, each held as the Relix text
 * of its value.
 *
 * <p>The text it makes is parsed by the engine before it is written anywhere, so the
 * engine, not this class, decides what a declaration needs: a missing option is the
 * parser's "missing 'url'", reported as the flag {@code --url}.
 */
final class DeclarationSpec {

    /** The source kinds that read one file, named in parentheses: {@code csv("…")}. */
    static final Set<String> FILE_KINDS = Set.of("csv", "json");

    /** The source kinds the language has besides a connection's name. */
    static final Set<String> SOURCE_KINDS = Set.of("csv", "json", "database", "http", "generator");

    private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern NUMBER = Pattern.compile("-?[0-9]+(\\.[0-9]+)?");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{[A-Za-z_][A-Za-z0-9_]*}");
    private static final Pattern SECRET_KEY =
            Pattern.compile("(?i).*(password|passwd|secret|token|api_?key|credential).*");
    private static final Pattern MISSING = Pattern.compile("missing '([A-Za-z_]+)'");
    private static final Pattern PRINTED = Pattern.compile(
            "(source|connection)\\s+([A-Za-z_][A-Za-z0-9_]*)\\s+from\\s+(.*?)\\s*\\{(.*)}\\s*;\\s*", Pattern.DOTALL);
    private static final Pattern FILE_HEAD = Pattern.compile("([A-Za-z_]+)\\(\\s*(\".*\")\\s*\\)", Pattern.DOTALL);

    /** What is declared. */
    enum Noun {
        SOURCE, CONNECTION;

        /** The keyword, as a command and a file name write it. */
        String word() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private final Noun noun;
    private final String name;
    private String from;
    private String location;
    private final LinkedHashMap<String, String> options;

    private DeclarationSpec(Noun noun, String name, String from, String location, Map<String, String> options) {
        this.noun = noun;
        this.name = name;
        this.from = from;
        this.location = location;
        this.options = new LinkedHashMap<>(options);
    }

    /**
     * A new declaration.
     *
     * @param noun what it declares
     * @param name its name
     * @param from its kind, or for a source the connection it reads
     * @return the declaration, with no location and no options
     * @throws CommandFailure with {@link ExitCode#USAGE} when a name is not a plain name
     */
    static DeclarationSpec of(Noun noun, String name, String from) {
        requireName(noun.word() + " name", name);
        requireName("KIND", from);
        return new DeclarationSpec(noun, name, noun == Noun.CONNECTION ? from.toLowerCase(Locale.ROOT) : from,
                null, Map.of());
    }

    /**
     * The declaration a parsed statement makes, read from the engine's canonical printing
     * of it.
     *
     * @param statement a {@code source} or {@code connection} declaration
     * @return it, as a spec to change
     * @throws IllegalArgumentException when the printing is not a declaration of that shape
     */
    static DeclarationSpec read(Statement statement) {
        String printed = ScriptPrinter.print(statement);
        Matcher m = PRINTED.matcher(printed.strip());
        if (!m.matches()) {
            throw new IllegalArgumentException("not a source or connection declaration: " + printed);
        }
        Noun noun = m.group(1).equals("source") ? Noun.SOURCE : Noun.CONNECTION;
        String head = m.group(3).strip();
        String from = head;
        String location = null;
        Matcher file = FILE_HEAD.matcher(head);
        if (file.matches()) {
            from = file.group(1);
            location = unquote(file.group(2));
        }
        Map<String, String> options = new LinkedHashMap<>();
        for (String entry : splitTopLevel(m.group(4), ',')) {
            if (entry.isBlank()) {
                continue;
            }
            int colon = topLevelIndex(entry, ':');
            options.put(entry.substring(0, colon).strip(), entry.substring(colon + 1).strip());
        }
        return new DeclarationSpec(noun, m.group(2), from, location, options);
    }

    /** An independent copy, to change without changing this. */
    DeclarationSpec copy() {
        return new DeclarationSpec(noun, name, from, location, options);
    }

    Noun noun() {
        return noun;
    }

    String name() {
        return name;
    }

    /** Its kind, or the connection a source reads. */
    String from() {
        return from;
    }

    /** Whether it is a source of one of the file kinds. */
    boolean isFile() {
        return noun == Noun.SOURCE && FILE_KINDS.contains(from);
    }

    /** The file a file source reads, as written. */
    Optional<String> location() {
        return Optional.ofNullable(location);
    }

    void location(String location) {
        this.location = location;
    }

    /** Each option's value, as Relix text. */
    Map<String, String> options() {
        return options;
    }

    /** An option's value, unquoted when it is a string. */
    Optional<String> option(String key) {
        String value = options.get(key);
        return value == null ? Optional.empty() : Optional.of(value.startsWith("\"") ? unquote(value) : value);
    }

    /**
     * Sets an option from a flag's value: for a source, {@code true}, {@code false}, a
     * number, or a {@code {…}} or {@code […]} block as written, and anything else as a
     * string; for a connection, always a string, as its properties are.
     *
     * @param key   the option, as the flag names it
     * @param value the flag's value
     * @throws CommandFailure with {@link ExitCode#USAGE} when the key is not a plain name,
     *                        or a secret is given as a value rather than a placeholder
     */
    void set(String key, String value) {
        requireName("--" + key, key);
        requirePlaceholderSecret(key, value);
        boolean raw = noun == Noun.SOURCE && (value.equals("true") || value.equals("false")
                || NUMBER.matcher(value).matches() || value.startsWith("{") || value.startsWith("["));
        options.put(key, raw ? value : quote(value));
    }

    /** Sets an option to Relix text as it stands. */
    void setText(String key, String text) {
        options.put(key, text);
    }

    /** Removes an option, saying whether it was there. */
    boolean unset(String key) {
        return options.remove(key) != null;
    }

    /**
     * The declaration's text: one statement, as the parser reads it.
     *
     * @return the text
     */
    String text() {
        StringBuilder text = new StringBuilder(noun.word()).append(' ').append(name).append(" from ").append(from);
        if (location != null) {
            text.append('(').append(quote(location)).append(')');
        }
        text.append(" {");
        String separator = " ";
        for (Map.Entry<String, String> option : options.entrySet()) {
            text.append(separator).append(option.getKey()).append(": ").append(option.getValue());
            separator = ", ";
        }
        return text.append(options.isEmpty() ? "};" : " };").toString();
    }

    /**
     * Parses the declaration as it would be read from {@code file}.
     *
     * @param file the path to parse it under, so a relative path resolves against its directory
     * @return the statement
     * @throws CommandFailure with {@link ExitCode#USAGE} when it does not parse, naming the
     *                        flag a missing option is given with
     */
    Statement parse(String file) {
        try {
            List<Statement> statements = Relix.parse(text(), file).statements();
            return statements.getFirst();
        } catch (ScriptParseException e) {
            Matcher missing = MISSING.matcher(e.getMessage());
            if (missing.find()) {
                String flag = "--" + missing.group(1);
                throw new CommandFailure(ExitCode.USAGE, flag + " is required: " + e.getMessage()
                        + (missing.group(1).equals("schema") ? " (give --schema='{ … }', or --infer-schema)" : ""));
            }
            throw new CommandFailure(ExitCode.USAGE, "the declaration does not parse: " + e.getMessage()
                    + "\n  " + text());
        }
    }

    /**
     * Refuses a secret given as its value: an option whose name says it is a credential,
     * or a URL carrying a password, must be a {@code ${NAME}} placeholder, so that the
     * value stays out of the catalog, the shell's history and {@code ps}.
     */
    private static void requirePlaceholderSecret(String key, String value) {
        boolean secretKey = SECRET_KEY.matcher(key).matches();
        boolean passwordInUrl = value.toLowerCase(Locale.ROOT).matches(".*[?&;:]password=.*");
        if ((secretKey && !PLACEHOLDER.matcher(value).matches())
                || (passwordInUrl && !value.matches("(?i).*password=\\$\\{[A-Za-z_][A-Za-z0-9_]*}.*"))) {
            throw new CommandFailure(ExitCode.USAGE, "--" + key + ": a secret is written as a ${NAME}"
                    + " placeholder, never its value, which would stay in the catalog, your shell's history"
                    + " and ps; give --" + key + "='${NAME}' and set NAME with relix profiles set");
        }
    }

    static void requireName(String what, String name) {
        if (!NAME.matcher(name).matches()) {
            throw new CommandFailure(ExitCode.USAGE, what + ": '" + name
                    + "' is not a plain name: letters, digits and _, not starting with a digit");
        }
    }

    /** A value as a Relix string literal, as the engine's printer writes one. */
    static String quote(String value) {
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    /** A Relix string literal's value. */
    static String unquote(String literal) {
        String body = literal.substring(1, literal.length() - 1);
        StringBuilder value = new StringBuilder(body.length());
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '\\' && i + 1 < body.length()) {
                c = body.charAt(++i);
            }
            value.append(c);
        }
        return value.toString();
    }

    /** {@code text} split at each {@code separator} outside a string, braces, brackets or parentheses. */
    private static List<String> splitTopLevel(String text, char separator) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        char quote = 0;
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (c == '\\' && quote == '"') {
                    i++;
                } else if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '`') {
                quote = c;
            } else if (c == '{' || c == '[' || c == '(') {
                depth++;
            } else if (c == '}' || c == ']' || c == ')') {
                depth--;
            } else if (c == separator && depth == 0) {
                parts.add(text.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(text.substring(start));
        return parts;
    }

    private static int topLevelIndex(String text, char c) {
        int index = splitTopLevel(text, c).getFirst().length();
        if (index >= text.length()) {
            throw new IllegalArgumentException("no '" + c + "' in: " + text);
        }
        return index;
    }
}
