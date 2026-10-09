# The relix command line

Using Relix from a shell: the `relix` command, which runs scripts as one stage of a Unix
pipeline, and the commands beside it that check, explain, optimise and format them.

`relix` writes rows to standard output and everything else to standard error. A script
comes from `-e`, a file or standard input; its data from files, standard input, or the
tables a project declares in its `.relix/` directory; and its rows leave as a table on a
terminal, or tab-separated values, CSV, NDJSON, JSON or Markdown for the next program.

Every example in this section is run on each build of the command, against a small
project, and the block under it is what it printed. The command reference is generated
from the same model as `relix --help` and the man pages.

## The command line

Installing it, and the shape every command shares; then data in, rows out.

| Page | What it covers |
|---|---|
| [The relix command](getting-started.md) | Installing; a first query; where the script comes from; the commands; diagnostics and exit status |
| [Binding inputs](inputs.md) | `-i` and its formats; standard input; inferred and given headings; inputs that never end; shadowing the catalog; query parameters with `-a` |
| [Output formats](output.md) | `table`, `tsv`, `csv`, `ndjson`, `json` and `markdown`; NULLs and headers; scripts with several queries; `--fail-empty` and `--fail-rows`; messages; closed pipes and interrupts |

## Projects on the command line

What a directory gives the commands run in it.

| Page | What it covers |
|---|---|
| [The catalog](catalog.md) | `.relix/` directories and how they are found; trusting a project; a nearer declaration wins; inspecting the catalog; `--catalog`; `relixrc` |
| [Secrets and profiles](secrets.md) | `${NAME}` placeholders; `profiles.json` and `-P`; the environment and `-D`; private profile files; what is never printed |

## Command-line recipes

| Page | What it covers |
|---|---|
| [Pipelines](pipelines.md) | With `jq`, `awk`, `sort` and `head`; with `find` and `xargs`; scripts in here-documents; a data check in CI; optimising a script and reviewing the change |

## Command reference

One page per command: what it does, its usage, arguments and options, its exit status,
and examples.

| Page | What it covers |
|---|---|
| [`relix`](commands/relix.md) | The global options every command takes; the exit statuses; the environment variables and files |
| [`relix run`](commands/run.md) | Runs scripts and writes their queries' rows |
| [`relix check`](commands/check.md) | Analyses scripts without running them, and reports their problems |
| [`relix explain`](commands/explain.md) | Prints each query's physical plan |
| [`relix optimize`](commands/optimize.md) | Prints the script with each query rewritten by the optimiser |
| [`relix trace`](commands/trace.md) | Runs each query and prints what the engine rewrote, planned and ran |
| [`relix bundle`](commands/bundle.md) | Prints each script's JSON bundle of diagnostics, plans and events |
| [`relix ir`](commands/ir.md) | Prints the symbols, views and queries the analyser made of a script |
| [`relix provenance`](commands/provenance.md) | Runs each query and annotates each row with where it came from |
| [`relix fmt`](commands/fmt.md) | Prints scripts canonically, keeping their comments |
| [`relix catalog`](commands/catalog.md) | Lists the catalog's files, relations, columns and declarations; trusts a project |
| [`relix drivers`](commands/drivers.md) | Lists and installs JDBC drivers |
| [`relix connectors`](commands/connectors.md) | Lists and installs connector plugins |
| [`relix doc`](commands/doc.md) | Shows a page of the language reference |
| [`relix help`](commands/help.md) | Prints a command's usage |
| [`relix version`](commands/version.md) | Prints the versions of the command and of the engine |
| [`relix completion`](commands/completion.md) | Prints a completion script for bash, zsh, fish or PowerShell |

A machine-readable index of these pages is `index.json`, beside this one.
