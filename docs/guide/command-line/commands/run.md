# relix run

Runs scripts and writes their queries' rows to standard output.

## Usage

```text
relix run [OPTIONS] [SCRIPT...]
```

## Arguments

| Argument | Description |
|---|---|
| `SCRIPT...` | Script files, each run in a session of its own; - is standard input. With neither -e nor SCRIPT, standard input is read when it is not a terminal. |

## Options

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

It also takes the [global options](relix.md#global-options).

## Examples

`relix run` is what `relix` does with no command, so these two are the same:

```shell
relix run -e 'GROUP status, SUM(amount) -> total (Orders)'
```
```
── <expression 1> ──────────────────────────────────────────────────────────────
 status     total
 ─────────  ─────
 completed    400
 pending       50
(2 rows)
```

```shell
relix -e 'GROUP status, SUM(amount) -> total (Orders)' -o csv
```
```
status,total
completed,400
pending,50
```

Each script file is a run of its own, with its own declarations:

```shell
relix run scripts/top-customers.relix -a city=Leeds scripts/by-city.relix -o tsv
```
```
name	total
Alice	200
Carol	200
name
Bob
```

[Binding inputs](../inputs.md), [Output formats](../output.md) and
[Pipelines](../pipelines.md) cover the options in use.
