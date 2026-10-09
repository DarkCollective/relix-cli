# relix trace

Runs each query and prints its event feed, what the engine rewrote, planned and ran, discarding the rows. relix run --trace keeps the rows too.

## Usage

```text
relix trace [OPTIONS] [SCRIPT...]
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
| `--query=NAME` | Trace only the query NAME. |

It also takes the [global options](relix.md#global-options).

## Examples

Each event ends with how long it took, which varies from run to run, so here `cut`
leaves the timings off:

```shell
relix trace -e 'SELECT amount > 100 (Orders)' | cut -d'(' -f1
```
```
[EXECUTE ]  orders  SCAN  scanned 4 rows
[EXECUTE ]  <expression 1>  ROWS  query delivered 2 rows
```

`relix run --trace` writes the same feed to standard error while the rows go to standard
output, and `--trace=FILE` writes it to a file:

```shell
relix --trace=trace.txt -e 'SELECT amount > 100 (Orders)' -o tsv
```
```
order_id	customer_id	amount	status	placed
1	1	120	completed	2026-09-02
4	3	200	completed	2026-10-01
```
