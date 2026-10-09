# relix optimize

Prints the script with each query rewritten by the optimiser, so that it works as a filter: relix optimize slow.relix > fast.relix

## Usage

```text
relix optimize [OPTIONS] [SCRIPT...]
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
| `--report` | Print the rules that fired and each query before and after, instead. |

It also takes the [global options](relix.md#global-options).

## Examples

The rewritten script is printed in the same form the engine runs it in:

```shell
relix optimize -e "query { SELECT city = 'London' (Customers JOIN Orders) };"
```
```
query { (σ city = "London" (Customers)) ⋈ (Orders) };
```

`--report` prints the rules that fired, and each query before and after:

```shell
relix optimize --report -e "query { SELECT city = 'London' (Customers JOIN Orders) };"
```
```
════════════════════════════════════════════════════════════════════════════════
RELIX OPTIMIZER  namespace=default  transformations=1
════════════════════════════════════════════════════════════════════════════════

── SUMMARY ─────────────────────────────────────────────────────────────────────
 SEL-005  Selection pushed into join input ...................................×1

── TREES ───────────────────────────────────────────────────────────────────────

── <expression 1>
  before:  σ city = "London" ((Customers) ⋈ (Orders))
  after:   (σ city = "London" (Customers)) ⋈ (Orders)

── DETAIL ──────────────────────────────────────────────────────────────────────

── <expression 1> (1 transformation)
  [SEL-005]  selection pushed into left input of natural join @ <session>:1:9
════════════════════════════════════════════════════════════════════════════════
```

[Pipelines](../pipelines.md#optimise-a-script-and-review-the-change) compares a script
with its rewrite.
