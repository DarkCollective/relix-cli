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
import com.darkcollective.relix.processor.connector.ConnectorProvisioner;
import com.darkcollective.relix.symbol.ScalarType;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;

/**
 * {@code relix connectors}: the connectors installed, those that can be downloaded, and
 * installing one (design §3). Installing is how a connector arrives ahead of a run;
 * during a run, {@code --allow-download} permits fetching one the run needs.
 */
@Command(name = "connectors",
        mixinStandardHelpOptions = true,
        sortOptions = false,
        usageHelpAutoWidth = true,
        description = {
            "Lists and installs connector plugins, such as mongodb. A source of a type no",
            "installed connector reads needs one, installed here ahead of a run, or fetched",
            "during one with --allow-download."},
        subcommands = {ConnectorsCommand.Ls.class, ConnectorsCommand.Install.class})
final class ConnectorsCommand implements Callable<Integer> {

    @ParentCommand
    RelixCommand root;

    @Override
    public Integer call() {
        throw new CommandFailure(ExitCode.USAGE, null);
    }

    private static ConnectorProvisioner provisioner(Invocation invocation, boolean download) {
        PrintWriter progress = new PrintWriter(invocation.host().err(), true, StandardCharsets.UTF_8);
        return ConnectorProvisioner.create(download, DownloadProgress.reporting(progress));
    }

    /** {@code relix connectors ls}. */
    @Command(name = "ls",
            mixinStandardHelpOptions = true,
            sortOptions = false,
            usageHelpAutoWidth = true,
            description = {
                "Lists the connectors installed: those shipped with relix and the plugins",
                "installed in ~/.relix/connectors. --available adds the ones the hosted",
                "catalog offers, which it fetches."})
    static final class Ls implements Callable<Integer> {

        @ParentCommand
        ConnectorsCommand connectors;

        @Option(names = "--available",
                description = "Also list the connectors that can be installed, from the hosted catalog.")
        boolean available;

        @Mixin
        FormatOptions formatting;

        @Override
        public Integer call() {
            Invocation invocation = connectors.root.invocation(false);
            Set<String> installed = ConnectorProvisioner.installedTypes();
            Set<String> types = new TreeSet<>(installed);
            if (available) {
                types.addAll(provisioner(invocation, false).catalogTypes());
            }
            Listing listing = new Listing("type", ScalarType.STRING, "installed", ScalarType.BOOLEAN);
            for (String type : types) {
                listing.add(type, installed.contains(type));
            }
            listing.print(invocation, invocation.format(formatting), "connectors");
            return ExitCode.SUCCESS.status();
        }
    }

    /** {@code relix connectors install TYPE}. */
    @Command(name = "install",
            mixinStandardHelpOptions = true,
            sortOptions = false,
            usageHelpAutoWidth = true,
            description = "Downloads the connector plugin TYPE, checks its checksums, and installs it.",
            exitCodeListHeading = "%nExit status:%n",
            exitCodeList = {
                " 0:installed, or already installed",
                " 2:the catalog has no connector of that type",
                " 5:the download failed"})
    static final class Install implements Callable<Integer> {

        @ParentCommand
        ConnectorsCommand connectors;

        @Parameters(paramLabel = "TYPE", description = "A connector type, such as mongodb.")
        String type;

        @Override
        public Integer call() {
            Invocation invocation = connectors.root.invocation(false);
            if (ConnectorProvisioner.installedTypes().contains(type)) {
                invocation.reporter().info("the " + type + " connector is already installed");
                return ExitCode.SUCCESS.status();
            }
            ConnectorProvisioner.Result result = provisioner(invocation, true).provision(type);
            return switch (result.outcome()) {
                case PROVISIONED -> {
                    invocation.reporter().info("installed the " + type + " connector into "
                            + ConnectorProvisioner.defaultDirectory() + ": " + result.message());
                    yield ExitCode.SUCCESS.status();
                }
                case UNKNOWN -> throw new CommandFailure(ExitCode.USAGE, result.message());
                case DISABLED, FAILED -> throw new CommandFailure(ExitCode.ENVIRONMENT, result.message());
            };
        }
    }
}
