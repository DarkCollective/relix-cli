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
import picocli.CommandLine.ScopeType;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The options every command takes (design §3.5, §4): where to start, which profile, how
 * much to say, and the limits and permissions of the engine session.
 *
 * <p>Inherited, so that they may be given before or after the command's name.
 */
final class GlobalOptions {

    @Option(names = "-C", paramLabel = "DIR", scope = ScopeType.INHERIT,
            description = "Act as if started in DIR: relative paths and .relix/ discovery start there.")
    Path directory;

    @Option(names = {"-P", "--profile"}, paramLabel = "NAME", scope = ScopeType.INHERIT,
            description = "The profile $${VAR} placeholders resolve from (default: $RELIX_PROFILE).")
    String profile;

    @Option(names = "-D", paramLabel = "NAME=VALUE", scope = ScopeType.INHERIT,
            description = {
                "Set one $${VAR} for this run; it beats the profile and the environment.",
                "Not for secrets: it lands in shell history and ps."})
    Map<String, String> defines = new LinkedHashMap<>();

    @Option(names = {"-q", "--quiet"}, scope = ScopeType.INHERIT,
            description = "Print errors only, not warnings.")
    boolean quiet;

    @Option(names = {"-v", "--verbose"}, scope = ScopeType.INHERIT,
            description = "Also print notices (drivers loaded); -vv adds timings.")
    boolean[] verbose = new boolean[0];

    @Option(names = "--now", paramLabel = "INSTANT", scope = ScopeType.INHERIT,
            description = "Pin NOW() to an ISO-8601 instant, such as 2026-07-22T12:00:00Z (default: $RELIX_NOW).")
    String now;

    @Option(names = "--max-fixpoint-rounds", paramLabel = "N", scope = ScopeType.INHERIT,
            description = "Fail a recursive query that needs more than N rounds.")
    Integer maxFixpointRounds;

    @Option(names = "--max-materialized-rows", paramLabel = "N", scope = ScopeType.INHERIT,
            description = "Fail a query in which one blocking operator buffers more than N rows.")
    Integer maxMaterializedRows;

    @Option(names = "--max-processed-rows", paramLabel = "N", scope = ScopeType.INHERIT,
            description = "Fail a query that processes more than N rows in all.")
    Long maxProcessedRows;

    @Option(names = "--timeout", paramLabel = "DURATION", scope = ScopeType.INHERIT,
            converter = DurationConverter.class,
            description = "Fail a query that runs longer than DURATION: 500ms, 30s, 5m, 1h, or ISO-8601 (PT30S).")
    Duration timeout;

    @Option(names = "--sandbox", paramLabel = "FILE", scope = ScopeType.INHERIT,
            description = "Run under the sandbox FILE describes.")
    Path sandbox;

    @Option(names = "--remote", scope = ScopeType.INHERIT,
            description = "Permit http(s) file locations.")
    boolean remote;

    @Option(names = "--allow-download", scope = ScopeType.INHERIT,
            description = "Permit fetching a missing JDBC driver or connector during the run.")
    boolean allowDownload;
}
