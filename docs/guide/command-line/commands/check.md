# relix check

Analyses scripts without running them, and reports what is wrong with them on standard error as FILE:LINE:COL: severity: message.

```text
find . -name '*.relix' -print0 | xargs -0 relix check
```

## Usage

```text
relix check [OPTIONS] [SCRIPT...]
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
| `--json` | Write the diagnostics to standard output as one JSON document instead. |

It also takes the [global options](relix.md#global-options).

## Exit status

| Status | Meaning |
|---|---|
| 0 | every script analyses |
| 3 | a script does not parse or analyse |

## Examples

A script that analyses prints nothing, and exits 0:

```shell
relix check scripts/top-customers.relix
```

A problem is printed on standard error, and the status is 3:

```shell
relix check scripts/broken.relix 2>&1; echo "exit $?"
```
```
scripts/broken.relix:1:30: error: Undefined relation: 'Ordrs'
exit 3
```

`--json` writes the diagnostics to standard output instead, as one document for a tool to
read:

```shell
relix check --json scripts/broken.relix; echo "exit $?"
```
```
{"diagnostics":[{"file":"scripts/broken.relix","line":1,"column":30,"severity":"error","message":"Undefined relation: 'Ordrs'"}]}
exit 3
```

[Pipelines](../pipelines.md#lint-every-script) checks every script in a tree.
