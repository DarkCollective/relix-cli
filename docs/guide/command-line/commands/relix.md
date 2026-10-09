# relix

Runs Relix relational-algebra scripts as a stage of a Unix pipeline. Rows go to standard output; everything else goes to standard error.

## Usage

```text
relix [OPTIONS] [SCRIPT...] [COMMAND]
```

## Arguments

| Argument | Description |
|---|---|
| `SCRIPT...` | Script files, each run in a session of its own; - is standard input. With neither -e nor SCRIPT, standard input is read when it is not a terminal. |

## Run options

With no command, `relix` is `relix run`, and takes its options:

| Option | Description |
|---|---|
| `-e`, `--expr=TEXT` | Relix text to run. Repeatable: the texts are one script, in order. A bare relational expression runs as a query. |
| `-i`, `--input=NAME=[FORMAT:]LOCATION` | Bind the relation NAME to a file, an http(s) URL (with --remote), or - for standard input. FORMAT is csv, tsv, json or ndjson; without it the extension decides, and standard input must name one. Repeatable. |
| `--schema=NAME={...}` | The heading of the input NAME, such as NAME='{ id: NUMBER, name: STRING }', instead of inferring one. |
| `--infer-rows=N` | Infer an input's heading from its first N records (default: 1000). |
| `--unbounded=NAME` | The input NAME never ends, as tail -f's does: its rows stream as they arrive, and a query that would have to read all of it first is refused. Repeatable. |
| `-o`, `--output=FORMAT` | The rows' format: table, tsv, csv, ndjson, json or markdown (default: $RELIX_OUTPUT, else relixrc's, else table on a terminal, tsv in a pipe). |
| `--no-header` | Leave out the header row of csv and tsv. |
| `--null=STRING` | How NULL is written (default: empty in csv, tsv and markdown; NULL in a table). |
| `--color=WHEN` | Colour a table: auto, always or never (default: auto). auto colours on a terminal unless $NO_COLOR is set. |
| `--line-buffered` | Flush each row as it is written, for a reader that wants it at once. |
| `--query=NAME` | Print only the query NAME. A machine format (tsv, csv, ndjson, json) prints one query: the script's last, unless this names another. |
| `--all` | With ndjson, print every query, each row tagged "_query": NAME. |
| `--fail-empty` | Exit 1 when the queries printed return no rows. |
| `--fail-rows` | Exit 1 when the queries printed return any row. |
| `--trace=FILE` | Write the event feed (what was rewritten, planned and run) to standard error, or to FILE with --trace=FILE, while the rows go to standard output. |

## Global options

Every command takes these, before or after its name.

| Option | Description |
|---|---|
| `-C DIR` | Act as if started in DIR: relative paths and .relix/ discovery start there. |
| `-P`, `--profile=NAME` | The profile ${VAR} placeholders resolve from (default: $RELIX_PROFILE). |
| `-D NAME=VALUE` | Set one ${VAR} for this run; it beats the profile and the environment. Not for secrets: it lands in shell history and ps. |
| `-a`, `--arg=NAME=VALUE` | Bind the query parameter $NAME to VALUE. Repeatable. The value is bound, never pasted into the script, so it cannot change the query. |
| `-N`, `--no-catalog` | Ignore the .relix/ directories' catalogs and relixrc; --catalog files still load. |
| `--catalog=FILE` | Add a catalog file, nearer than the .relix/ directories'. Repeatable. $RELIX_CATALOG_PATH adds files too, separated as in PATH. |
| `-q`, `--quiet` | Print errors only, not warnings. |
| `-v`, `--verbose` | Also print notices (drivers loaded); -vv adds timings. |
| `--now=INSTANT` | Pin NOW() to an ISO-8601 instant, such as 2026-07-22T12:00:00Z (default: $RELIX_NOW). |
| `--max-fixpoint-rounds=N` | Fail a recursive query that needs more than N rounds. |
| `--max-materialized-rows=N` | Fail a query in which one blocking operator buffers more than N rows. |
| `--max-processed-rows=N` | Fail a query that processes more than N rows in all. |
| `--timeout=DURATION` | Fail a query that runs longer than DURATION: 500ms, 30s, 5m, 1h, or ISO-8601 (PT30S). |
| `--sandbox=FILE` | Run under the sandbox FILE describes. |
| `--remote` | Permit http(s) file locations. |
| `--allow-download` | Permit fetching a missing JDBC driver or connector during the run. |
| `-h`, `--help` | Show this help message and exit. |
| `-V`, `--version` | Print version information and exit. |

## Commands

| Command | What it does |
|---|---|
| [`relix run`](run.md) | Runs scripts and writes their queries' rows to standard output. |
| [`relix check`](check.md) | Analyses scripts without running them, and reports what is wrong with them on standard error as FILE:LINE:COL: severity: message. |
| [`relix explain`](explain.md) | Prints the physical plan of each query: how the engine would run it. |
| [`relix optimize`](optimize.md) | Prints the script with each query rewritten by the optimiser, so that it works as a filter: relix optimize slow.relix > fast.relix. |
| [`relix trace`](trace.md) | Runs each query and prints its event feed, what the engine rewrote, planned and ran, discarding the rows. |
| [`relix bundle`](bundle.md) | Prints each script's JSON bundle: diagnostics and, per query, the logical plan, the rewrites, the physical plan and the planner's events. |
| [`relix ir`](ir.md) | Prints the IR report: the symbols, views and queries the analyser made of each script. |
| [`relix provenance`](provenance.md) | Runs each query and prints its rows, each annotated with where it came from in a provenance semiring. |
| [`relix fmt`](fmt.md) | Prints scripts canonically, keeping their comments. |
| [`relix catalog`](catalog.md) | Inspects the catalog: the declarations a run takes from the .relix/ directories from ~/.relix down to the working directory, and from --catalog files. |
| [`relix drivers`](drivers.md) | Lists and installs JDBC drivers. |
| [`relix connectors`](connectors.md) | Lists and installs connector plugins, such as mongodb. |
| [`relix doc`](doc.md) | Shows the reference page for TOPIC, found by glyph, keyword or name (σ, select, selection), through $PAGER on a terminal. |
| [`relix help`](help.md) | When no COMMAND is given, the usage help for the main command is displayed. |
| [`relix version`](version.md) | Prints the versions of relix and of the Relix engine it runs. |
| [`relix completion`](completion.md) | Prints a completion script for SHELL: bash, zsh, fish or powershell. |

## Exit status

| Status | Meaning |
|---|---|
| 0 | success |
| 1 | an assertion failed (--fail-empty, --fail-rows) |
| 2 | usage error (bad option, nothing to run) |
| 3 | the script did not parse or analyse |
| 4 | execution failed (a data error, a limit hit) |
| 5 | environment (a missing driver, an untrusted catalog, a connection refused) |
| 70 | internal error in relix |
| 130 | interrupted (SIGINT) |
| 141 | standard output closed (SIGPIPE) |

## Examples

With no command, `relix` runs scripts, as [`relix run`](run.md) does:

```shell
relix -e 'PROJECT name, city (Customers)' -o tsv
```
```
name	city
Alice	London
Bob	Leeds
Carol	London
Dan	York
```

Global options go before or after the command's name:

```shell
relix -C reports catalog ls -o tsv --no-header
```
```
BigSpenders	QR	2	~/shop/.relix/catalog/10-views.relix
Completed	QR	5	~/shop/.relix/catalog/10-views.relix
Customers	SRC	3	~/shop/.relix/catalog/00-sources.relix
Orders	SRC	5	~/shop/reports/.relix/catalog/10-sample.relix
Regions	INL	2	~/.relix/catalog/00-regions.relix
Spend	QR	2	~/shop/.relix/catalog/10-views.relix
Stock	SRC	2	~/shop/.relix/catalog/20-warehouse.relix
```

## Environment

| Variable | Effect |
|---|---|
| `RELIX_OUTPUT` | the output format when `-o` is not given |
| `RELIX_PROFILE` | the profile when `-P` is not given |
| `RELIX_NOW` | the instant `NOW()` returns when `--now` is not given |
| `RELIX_CATALOG_PATH` | catalog files to load, separated as in `PATH`, before the `--catalog` files |
| `RELIX_TRUST_ALL` | `1` or `true` trusts every project's `.relix/` directory |
| `RELIX_DRIVERS` | the directory JDBC drivers are installed in and loaded from, instead of `~/.relix/drivers` |
| `NO_COLOR` | when set, a table on a terminal is not coloured unless `--color=always` |
| `PAGER` | the program [`relix doc`](doc.md) shows a page through on a terminal; `cat` or empty shows it directly |

A `${NAME}` placeholder in a declaration may also be resolved from the environment; see
[Secrets and profiles](../secrets.md).

## Files

| Path | What it holds |
|---|---|
| `~/.relix/catalog/*.relix` | your own catalog, which every directory under your home sees |
| `~/.relix/profiles.json` | your own profiles |
| `~/.relix/trusted` | the projects you have trusted, with a hash of each one's files |
| `~/.relix/drivers/` | installed JDBC drivers |
| `~/.relix/connectors/` | installed connector plugins |
| `~/.relix/cache/files/` | files fetched over `https` by a file connection |
| `.relix/catalog/*.relix`, `.relix/relixrc`, `.relix/profiles.json`, `.relix/root` | a project's catalog, defaults, profiles, and the marker that ends the walk up; see [The catalog](../catalog.md) |
