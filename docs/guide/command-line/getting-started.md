# The relix command

`relix` runs Relix scripts from a shell. It is built to be one stage of a Unix pipeline:
the script comes from the command line, a file or standard input; data comes from files,
standard input or the tables a project declares; and the rows go to standard output in a
format the next program reads. Everything else — errors, warnings, progress — goes to
standard error, so a pipeline never mistakes a message for a row.

This page installs the command, runs a first query, and shows the shape every command
shares. The pages after it take one subject each, and the
[command reference](commands/relix.md) lists every command and option.

## Installing

`relix` ships as a self-contained archive for macOS, Linux and Windows: a launcher, its
own Java runtime, the man pages and completion scripts for bash, zsh, fish and
PowerShell. Nothing else needs to be installed, and no Java is needed on the machine.

On macOS and Linux, with Homebrew:

```text
brew install DarkCollective/relix/relix
```

On Windows, with Scoop:

```text
scoop bucket add relix https://github.com/DarkCollective/scoop-relix
scoop install relix
```

Or download the archive for your platform from the
[releases page](https://github.com/DarkCollective/relix-cli/releases), unpack it, and put
its `bin` directory on your `PATH`. Each release lists the archives' SHA-256 checksums
in `checksums.txt`.

`relix version` prints the version of the command and the version of the Relix engine it
runs, as `relix VERSION (engine VERSION)`. The two are numbered independently.

## The examples on these pages

Every example in this section is run on each build of the command, and the block under
it is what it printed. The examples run in `~/shop`, a small project with a customer
file, an order file and a stock database:

```shell
cat data/customers.csv data/orders.csv
```
```
customer_id,name,city
1,Alice,London
2,Bob,Leeds
3,Carol,London
4,Dan,York
order_id,customer_id,amount,status,placed
1,1,120,completed,2026-09-02
2,1,80,completed,2026-09-14
3,2,50,pending,2026-09-20
4,3,200,completed,2026-10-01
```

The project declares its tables once, in `~/shop/.relix/catalog/`, so the examples can
name `Customers` and `Orders` without declaring them. [The catalog](catalog.md) explains
how. A path printed under your home directory is shown as `~`.

## A first query

`-e` takes Relix text. A bare expression is run as a query:

```shell
relix -e 'SELECT amount > 100 (Orders)'
```
```
── <expression 1> ──────────────────────────────────────────────────────────────
 order_id  customer_id  amount  status     placed
 ────────  ───────────  ──────  ─────────  ──────────
        1            1     120  completed  2026-09-02
        4            3     200  completed  2026-10-01
(2 rows)
```

On a terminal the rows are a table. In a pipe they are tab-separated values, one row per
line under a header, which is what `cut`, `sort`, `awk` and `column` expect:

```shell
relix -e 'SELECT amount > 100 (Orders)' | cut -f1,3
```
```
order_id	amount
1	120
4	200
```

The language itself — every operator, predicate and function — is the subject of the
[language reference](../../reference/README.md). Operators have a Unicode glyph and an ASCII
keyword, and the command reads both: `σ amount > 100 (Orders)` is the same query.

## Where the script comes from

A command reads its script from the first of these that is given:

1. **`-e TEXT`**, repeatable. The texts are joined, in order, into one script, so one
   `-e` can declare a view and the next use it. A text that is a bare relational
   expression rather than statements is run as `query { … };`.
2. **Script files**, named as arguments. Each runs in a session of its own, so two files on
   one command line never see each other's declarations. `-` names standard input.
3. **Standard input**, when nothing is named and standard input is not a terminal.

With none of them, the command prints its usage and exits 2.

```shell
relix -e 'Big := { SELECT amount > 100 (Orders) };' -e 'query { PROJECT order_id (Big) };'
```
```
── <expression 1> ──────────────────────────────────────────────────────────────
 order_id
 ────────
        1
        4
(2 rows)
```

A script file holds statements, as a script always does:

```shell
cat scripts/top-customers.relix
```
```
-- The customers who have spent the most, best first.
query { SORT total DESC (PROJECT name, total (Customers JOIN Spend)) };
```

```shell
relix scripts/top-customers.relix
```
```
── <expression 1> ──────────────────────────────────────────────────────────────
 name   total
 ─────  ─────
 Alice    200
 Carol    200
(2 rows)
```

and a script can arrive on standard input:

```shell
echo 'query { PROJECT name (Customers) };' | relix -o csv
```
```
name
Alice
Bob
Carol
Dan
```

A relative path in a script — a `csv("…")` source, an `import` — resolves against the
script file's own directory, and in `-e` text or standard input against the working
directory. `-C DIR` makes the command act as if it were started in `DIR`.

## Commands

`relix` with no command name runs scripts; it is `relix run`. Every other job is a command
of its own, and each writes one thing to standard output:

| Command | Standard output |
|---|---|
| [`relix run`](commands/run.md) | the rows of the queries (the default) |
| [`relix check`](commands/check.md) | nothing; problems go to standard error, or `--json` to standard output |
| [`relix explain`](commands/explain.md) | the physical plan of each query |
| [`relix optimize`](commands/optimize.md) | the script, rewritten by the optimiser |
| [`relix trace`](commands/trace.md) | what the engine rewrote, planned and ran |
| [`relix bundle`](commands/bundle.md) | one JSON document per script: diagnostics, plans and events |
| [`relix ir`](commands/ir.md) | the symbols, views and queries the analyser made of the script |
| [`relix provenance`](commands/provenance.md) | the rows, each with where it came from |
| [`relix fmt`](commands/fmt.md) | the script, printed canonically |
| [`relix catalog`](commands/catalog.md) | the catalog's files, relations, columns and trust |
| [`relix drivers`](commands/drivers.md), [`relix connectors`](commands/connectors.md) | the JDBC drivers and connector plugins, and installing them |
| [`relix doc`](commands/doc.md) | a page of the language reference |
| [`relix completion`](commands/completion.md) | a completion script for a shell |
| [`relix help`](commands/help.md), [`relix version`](commands/version.md) | usage, and the versions |

Options follow the usual conventions: short options cluster (`-qN`), a long option takes
its value as `--output=csv` or `--output csv`, `--` ends the options, and `-` is standard
input. The [global options](commands/relix.md#global-options) — the profile, the limits,
`-C` and the rest — are accepted by every command, before or after its name.

## When something goes wrong

A problem in a script is reported on standard error as `FILE:LINE:COLUMN: severity:
message`, the shape compilers use, so an editor or a CI annotator can jump to it:

```shell
relix check scripts/broken.relix 2>&1; echo "exit $?"
```
```
scripts/broken.relix:1:30: error: Undefined relation: 'Ordrs'
exit 3
```

The exit status says what kind of failure it was, so a script can tell a typo from a
database that is down:

| Status | Meaning |
|---|---|
| 0 | success |
| 1 | an assertion asked for failed: `--fail-empty` or `--fail-rows` |
| 2 | a usage error: a bad option, nothing to run |
| 3 | the script did not parse or analyse |
| 4 | the query failed while it ran: a data error, a limit hit |
| 5 | the environment: a missing driver, an untrusted catalog, a connection refused |
| 70 | an internal error in `relix`; the stack trace goes to standard error |
| 130 | interrupted with Ctrl-C (`SIGINT`) |
| 141 | standard output was closed by the reader (`SIGPIPE`) |

`-q` keeps only the errors, `-v` adds notices such as the catalog files and drivers
loaded, and `-vv` adds timings.
