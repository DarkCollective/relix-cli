# relix provenance

Runs each query and prints its rows, each annotated with where it came from in a provenance semiring.

## Usage

```text
relix provenance [OPTIONS] [SCRIPT...]
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
| `--semiring=NAME` | The semiring: boolean, counting, tropical, security, lineage, cheapest-route, or one an installed library offers (default: counting). |
| `--weight=COLUMN` | The column a weighted closure takes each edge's weight from, such as --semiring tropical --weight cost for shortest paths. |
| `-o`, `--output=FORMAT` | table or json (default: table on a terminal, json in a pipe). |
| `--query=NAME` | Annotate only the query NAME. |

It also takes the [global options](relix.md#global-options).

## Examples

The default semiring, `counting`, says in how many ways each row is derived. Two London
customers each derive the row `London`:

```shell
relix provenance -e 'PROJECT city (Customers)'
```
```
<expression 1>
------  ------
city    [prov]
------  ------
London  2
Leeds   1
York    1
------  ------
(3 tuples; provenance semiring: counting)
```

The `lineage` semiring names the source rows each result row came from:

```shell
relix provenance --semiring=lineage -e 'PROJECT city (SELECT amount > 100 (Customers JOIN Orders))'
```
```
<expression 1>
------  -------------------------------------------
city    [prov]
------  -------------------------------------------
London  Customers#1·Orders#1 + Customers#3·Orders#4
------  -------------------------------------------
(1 tuple; provenance semiring: lineage)
```

In a pipe, or with `-o json`, the rows and their annotations are one JSON document:

```shell
relix provenance -e 'PROJECT city (Customers)' -o json
```
```
{
  "query": "<expression 1>",
  "semiring": "counting",
  "schema": ["city"],
  "tuples": [
    {"row": {"city": "London"}, "provenance": "2"},
    {"row": {"city": "Leeds"}, "provenance": "1"},
    {"row": {"city": "York"}, "provenance": "1"}
  ]
}
```

The engine's [provenance page](../../provenance.md) explains each semiring.
