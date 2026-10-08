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
import com.darkcollective.relix.cli.drivers.DownloadProgress;
import com.darkcollective.relix.connectors.std.DriverCatalog;
import com.darkcollective.relix.connectors.std.DriverProvisioner;
import com.darkcollective.relix.symbol.ScalarType;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

/**
 * {@code relix drivers}: the JDBC drivers relix can download, and installing one
 * (design §3). Installing is how a driver arrives ahead of a run; during a run,
 * {@code --allow-download} permits fetching one the run needs.
 */
@Command(name = "drivers",
        mixinStandardHelpOptions = true,
        sortOptions = false,
        usageHelpAutoWidth = true,
        description = {
            "Lists and installs JDBC drivers. A connection to a database needs its driver,",
            "installed here ahead of a run, or fetched during one with --allow-download."},
        subcommands = {DriversCommand.Ls.class, DriversCommand.Install.class})
final class DriversCommand implements Callable<Integer> {

    /** A URL of a scheme's, for asking whether a driver accepts that scheme. */
    private static final String PROBE = "//localhost/";

    @ParentCommand
    RelixCommand root;

    @Override
    public Integer call() {
        throw new CommandFailure(ExitCode.USAGE, null);
    }

    /** Whether a driver that accepts a JDBC scheme is registered. */
    static boolean registered(String scheme) {
        try {
            return DriverManager.getDriver(scheme + PROBE) != null;
        } catch (SQLException none) {
            return false;
        }
    }

    /** The short name of a scheme: {@code postgresql} for {@code jdbc:postgresql:}. */
    static String shortName(String scheme) {
        String name = scheme.toLowerCase(Locale.ROOT);
        name = name.startsWith("jdbc:") ? name.substring(5) : name;
        return name.endsWith(":") ? name.substring(0, name.length() - 1) : name;
    }

    /** {@code relix drivers ls}. */
    @Command(name = "ls",
            mixinStandardHelpOptions = true,
            sortOptions = false,
            usageHelpAutoWidth = true,
            description = {
                "Lists the drivers relix can install: name, the JDBC scheme each serves,",
                "and whether one is installed."})
    static final class Ls implements Callable<Integer> {

        @ParentCommand
        DriversCommand drivers;

        @Mixin
        FormatOptions formatting;

        @Override
        public Integer call() {
            Invocation invocation = drivers.root.invocation(false);
            invocation.loadInstalledDrivers();
            Listing listing = new Listing("name", ScalarType.STRING, "scheme", ScalarType.STRING,
                    "installed", ScalarType.BOOLEAN);
            for (DriverCatalog.Entry entry : DriverCatalog.load().entries()) {
                listing.add(shortName(entry.scheme()), entry.scheme(), registered(entry.scheme()));
            }
            listing.print(invocation, invocation.format(formatting), "drivers");
            return ExitCode.SUCCESS.status();
        }
    }

    /** {@code relix drivers install NAME}. */
    @Command(name = "install",
            mixinStandardHelpOptions = true,
            sortOptions = false,
            usageHelpAutoWidth = true,
            description = "Downloads the driver NAME, checks its checksum, and installs it.",
            exitCodeListHeading = "%nExit status:%n",
            exitCodeList = {
                " 0:installed, or already installed",
                " 2:no driver has that name",
                " 5:the download failed"})
    static final class Install implements Callable<Integer> {

        @ParentCommand
        DriversCommand drivers;

        @Parameters(paramLabel = "NAME", description = "A driver relix drivers ls lists, such as postgresql.")
        String name;

        @Override
        public Integer call() {
            Invocation invocation = drivers.root.invocation(false);
            DriverCatalog catalog = DriverCatalog.load();
            DriverCatalog.Entry entry = catalog.entries().stream()
                    .filter(e -> shortName(e.scheme()).equalsIgnoreCase(name) || e.name().equalsIgnoreCase(name))
                    .findFirst()
                    .orElseThrow(() -> new CommandFailure(ExitCode.USAGE, "no driver named '" + name + "'; "
                            + "relix can install " + catalog.entries().stream()
                                    .map(e -> shortName(e.scheme())).collect(Collectors.joining(", "))));
            invocation.loadInstalledDrivers();
            if (registered(entry.scheme())) {
                invocation.reporter().info(entry.name() + " is already installed");
                return ExitCode.SUCCESS.status();
            }
            PrintWriter progress = new PrintWriter(invocation.host().err(), true, StandardCharsets.UTF_8);
            DriverProvisioner.Result result = DriverProvisioner.create(true, DownloadProgress.reporting(progress))
                    .provision(entry.scheme() + PROBE);
            return switch (result.outcome()) {
                case PROVISIONED, ALREADY_AVAILABLE -> {
                    invocation.reporter().info("installed " + entry.name() + ": " + result.message());
                    yield ExitCode.SUCCESS.status();
                }
                case UNKNOWN, DISABLED, FAILED -> throw new CommandFailure(ExitCode.ENVIRONMENT, result.message());
            };
        }
    }
}
