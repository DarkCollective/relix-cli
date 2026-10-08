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
import com.darkcollective.relix.cli.catalog.Declaration;
import com.darkcollective.relix.cli.io.InputBinding;
import com.darkcollective.relix.cli.io.Interruption;
import com.darkcollective.relix.cli.io.Reporter;
import com.darkcollective.relix.cli.io.ScriptSource;
import com.darkcollective.relix.embed.Diagnostic;
import com.darkcollective.relix.embed.Relation;
import com.darkcollective.relix.embed.Relix;
import com.darkcollective.relix.embed.RelixException;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Takes scripts through what every command that reads Relix does before it shows anything:
 * each in a session of its own, with the catalog, then the inputs, then the script
 * analysed. What is then shown of a script is a {@link View}'s: rows for {@code run}, a
 * plan for {@code explain}, and so on.
 *
 * <p>Each session first defines the catalog (design §5), then declares the inputs
 * {@code -i} binds, then analyses the script, so that each of these can replace a name the
 * one before it declared. An input that cannot be read fails the script as execution does.
 *
 * <p>Every diagnostic is reported, so a script with three mistakes reports three; one with
 * an error is not shown, unless the view shows broken scripts too, as the bundle does. Each
 * script is independent: a failure in one does not stop the next, and the run exits with
 * the first failure's code.
 *
 * <p>A script that fails for want of a name only an untrusted {@code .relix/} declares
 * exits 5, the environment's code, with the command that trusts it.
 *
 * <p>Two things end the whole run at once, whatever script it is in: standard output
 * closing (exit 141) and an interrupt (exit 130). Neither says anything.
 */
final class ScriptRunner {

    /** What a command shows of each script. */
    interface View {

        /**
         * Shows one analysed script.
         *
         * @param script the script, its session and its diagnostics
         * @return the script's exit code
         */
        ExitCode show(Script script);

        /**
         * Whether a script with errors is still shown. The script's exit code is then 3
         * whatever {@link #show} returns.
         *
         * @return {@code true} for a view that describes a broken script too
         */
        default boolean showsBroken() {
            return false;
        }

        /**
         * Whether diagnostics are reported on standard error; not by a view that writes
         * them to standard output itself.
         *
         * @return {@code true} to report them
         */
        default boolean reportsDiagnostics() {
            return true;
        }

        /**
         * Ends the run, once every script has been shown.
         *
         * @param result the exit code of the scripts, the first failure's
         * @return the run's exit code
         */
        default ExitCode finish(ExitCode result) {
            return result;
        }
    }

    /**
     * One script, analysed: its session holds the catalog and the inputs.
     *
     * @param source      where it came from
     * @param session     its session
     * @param diagnostics what analysing it found
     */
    record Script(ScriptSource source, Relix session, List<Diagnostic> diagnostics) {

        /**
         * Whether analysing it found an error.
         *
         * @return {@code true} when a diagnostic is an error
         */
        boolean broken() {
            return diagnostics.stream().anyMatch(Diagnostic::isError);
        }

        /**
         * Its queries, in order.
         *
         * @return one relation per {@code query} statement
         */
        List<Relation> queries() {
            return session.script(source.text());
        }

        /**
         * The queries a view shows: the one {@code --query} names, or every one.
         *
         * @param name the query to show, or {@code null} for all
         * @return the queries
         * @throws CommandFailure with {@link ExitCode#USAGE} when no query has that name
         */
        List<Relation> queries(String name) {
            List<Relation> queries = queries();
            if (name == null) {
                return queries;
            }
            List<Relation> named = queries.stream()
                    .filter(q -> q.label().filter(name::equals).isPresent())
                    .toList();
            if (named.isEmpty()) {
                throw new CommandFailure(ExitCode.USAGE, source.name() + ": no query named '" + name + "'");
            }
            return named;
        }
    }

    private final Invocation invocation;
    private final InputOptions.Inputs inputs;
    private final Interruption interruption;

    ScriptRunner(Invocation invocation, InputOptions.Inputs inputs, Interruption interruption) {
        this.invocation = invocation;
        this.inputs = inputs;
        this.interruption = interruption;
    }

    /**
     * Shows every script through a view.
     *
     * @param sources the scripts, in order
     * @param view    what to show of each
     * @return the run's exit code
     */
    ExitCode run(List<ScriptSource> sources, View view) {
        invocation.loadInstalledDrivers();
        ExitCode result = ExitCode.SUCCESS;
        for (ScriptSource source : sources) {
            ExitCode code = run(source, view);
            if (result == ExitCode.SUCCESS) {
                result = code;
            }
        }
        return view.finish(result);
    }

    private ExitCode run(ScriptSource source, View view) {
        Reporter reporter = invocation.reporter();
        try (Relix session = invocation.session(source.directory()).build()) {
            interruption.session(session);
            if (!defineCatalog(source, session)) {
                return ExitCode.ANALYSIS;
            }
            if (!declareInputs(source, session)) {
                return ExitCode.EXECUTION;
            }
            long start = System.nanoTime();
            List<Diagnostic> diagnostics = session.validate(source.text());
            reporter.timing(source.name() + ": analysed", Duration.ofNanos(System.nanoTime() - start));
            if (view.reportsDiagnostics()) {
                for (Diagnostic diagnostic : diagnostics) {
                    reporter.diagnostic(source.place(diagnostic.location()), diagnostic);
                }
            }
            Script script = new Script(source, session, diagnostics);
            if (script.broken()) {
                ExitCode code = withheld(diagnostics) ? ExitCode.ENVIRONMENT : ExitCode.ANALYSIS;
                if (view.showsBroken()) {
                    view.show(script);
                }
                return code;
            }
            return view.show(script);
        } catch (RuntimeException e) {
            // Closing the session under a running query fails it in whatever way it fails;
            // once interrupted, every such failure is the interrupt's.
            if (interruption.interrupted()) {
                throw new CommandFailure(ExitCode.INTERRUPTED, null);
            }
            if (e instanceof CommandFailure failure && failure.exitCode() == ExitCode.USAGE
                    && failure.getMessage() != null) {
                reporter.error(failure.getMessage());
                return ExitCode.USAGE;
            }
            if (e instanceof RelixException failure) {
                reporter.error(source.name() + ": " + failure.getMessage());
                return ExitCode.of(failure);
            }
            throw e;
        } finally {
            interruption.session(null);
        }
    }

    /**
     * Whether a script failed for a name only an untrusted directory declares, saying so:
     * then the script is not wrong, the environment is.
     *
     * @param diagnostics the script's diagnostics, with an error among them
     * @return {@code true} when an error names a withheld declaration
     */
    private boolean withheld(List<Diagnostic> diagnostics) {
        boolean found = false;
        for (Declaration declaration : invocation.catalog().withheld()) {
            String quoted = "'" + declaration.name() + "'";
            if (diagnostics.stream().anyMatch(d -> d.isError() && d.message().contains(quoted))) {
                Path project = declaration.file().level().getParent();
                invocation.reporter().error(declaration.name() + " is declared in "
                        + declaration.file().path() + ", which is not trusted; to load it: relix catalog trust "
                        + Invocation.shellWord(project.toString()));
                found = true;
            }
        }
        return found;
    }

    /**
     * Defines the catalog's declarations on a session, before its inputs and its script,
     * reporting why when they do not analyse.
     *
     * @return whether they were defined
     */
    private boolean defineCatalog(ScriptSource source, Relix session) {
        try {
            invocation.catalog().define(session);
            return true;
        } catch (RelixException e) {
            invocation.reporter().error(source.name() + ": the catalog does not analyse: " + e.getMessage());
            return false;
        }
    }

    /**
     * Declares every bound input on a session, reporting the first that cannot be read.
     *
     * @return whether all were declared
     */
    private boolean declareInputs(ScriptSource source, Relix session) {
        for (InputBinding binding : inputs.bindings()) {
            try {
                binding.declare(session, invocation.host().in(), inputs.sample(),
                        inputs.unbounded().contains(binding.name()));
            } catch (RelixException e) {
                if (interruption.interrupted()) {
                    throw new CommandFailure(ExitCode.INTERRUPTED, null);
                }
                invocation.reporter().error(source.name() + ": -i " + binding.name() + "="
                        + binding.location() + ": " + e.getMessage());
                return false;
            }
        }
        return true;
    }
}
