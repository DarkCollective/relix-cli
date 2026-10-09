# relix bundle

Prints each script's JSON bundle: diagnostics and, per query, the logical plan, the rewrites, the physical plan and the planner's events. A script that does not analyse still has one, holding its diagnostics.

## Usage

```text
relix bundle [OPTIONS] [SCRIPT...]
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

It also takes the [global options](relix.md#global-options).

## Examples

A bundle is one JSON document per script, for a tool such as a query playground:

```shell
relix bundle -e 'SELECT amount > 100 (Orders)' | jq -c 'keys'
```
```
["diagnostics","namespace","queries"]
```
