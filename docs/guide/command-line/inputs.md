# Binding inputs

A script names the relations it reads. Most of the time those are declared somewhere — in
the script, or in the project's [catalog](catalog.md) — but a pipeline often has data that
exists only for this run: a file another program wrote, the output of `jq`, a log being
followed. `-i` binds a relation name to such data for one run, without a declaration.
`-a` does the same for a single value, a query parameter.

## `-i NAME=[FORMAT:]LOCATION`

`-i` binds the relation `NAME` to the data at `LOCATION`, which is a file, `-` for
standard input, or an `http(s)` URL. It is repeatable, one relation per `-i`:

```shell
cat data/returns.ndjson
```
```
{"order_id": 2, "reason": "damaged", "refund": {"amount": 80, "currency": "GBP"}}
{"order_id": 4, "reason": "late", "refund": {"amount": 20, "currency": "GBP"}}
```

```shell
relix -i Returns=data/returns.ndjson -e 'PROJECT order_id, reason (Returns)'
```
```
── <expression 1> ──────────────────────────────────────────────────────────────
 order_id  reason
 ────────  ───────
        2  damaged
        4  late
(2 rows)
```

A bound relation is used like any other, and joins with the catalog's tables:

```shell
relix -i Returns=data/returns.ndjson -e 'PROJECT order_id, amount, reason (Orders JOIN Returns)'
```
```
── <expression 1> ──────────────────────────────────────────────────────────────
 order_id  amount  reason
 ────────  ──────  ───────
        2      80  damaged
        4     200  late
(2 rows)
```

A relative path resolves against the working directory, or against `-C DIR`. The same name
may not be bound twice.

### Formats

`FORMAT` is one of:

| Format | What it reads |
|---|---|
| `csv` | comma-separated values, RFC 4180, with a header row |
| `tsv` | tab-separated values, with a header row |
| `json` | one JSON array of objects |
| `ndjson` | one JSON object per line, the format `jq -c` writes |

Without a format, the file's extension decides: `.csv`, `.tsv` or `.tab`, `.json`, and
`.ndjson` or `.jsonl`. A file with any other extension needs its format named:

```shell
cp data/orders.csv orders.txt
relix -i O=orders.txt -e 'O' 2>&1; echo "exit $?"
```
```
relix: -i O=orders.txt: cannot tell the format from the extension .txt; name it, as NAME=csv:LOCATION (csv, tsv, json or ndjson)
exit 2
```

```shell
relix -i O=csv:orders.txt -e 'GROUP status, COUNT(*) -> n (O)'
```
```
── <expression 1> ──────────────────────────────────────────────────────────────
 status     n
 ─────────  ─
 completed  3
 pending    1
(2 rows)
```

A nested JSON object becomes a nested value, which the language's dotted paths and
`UNNEST` take apart; [Nested data](../nested.md) covers them.

### Standard input

`-` binds standard input. Standard input has no extension, so it always names its format:

```shell
jq -c 'select(.refund.amount > 50)' data/returns.ndjson \
  | relix -i Returns=ndjson:- -e 'PROJECT name, order_id, reason (Customers JOIN Orders JOIN Returns)'
```
```
── <expression 1> ──────────────────────────────────────────────────────────────
 name   order_id  reason
 ─────  ────────  ───────
 Alice         2  damaged
(1 row)
```

Only one input can be standard input, and when one is, the script cannot be read from it
too: it comes from `-e` or a file.

```shell
relix -i A=csv:- -i B=csv:- -e 'A' 2>&1 < data/orders.csv; echo "exit $?"
```
```
relix: standard input (-) can be bound to one input only
exit 2
```

### The heading of an input

The columns of a bound input and their types are inferred. For `csv` and `tsv` the names
are the header row's; for `json` and `ndjson` they are the keys of the objects. The types
are inferred from the values in the first 1000 records, or in as many as `--infer-rows=N`
says, so `order_id` above is a number and `placed` a date.

`--schema NAME='{ … }'` gives the heading instead, in the syntax a `source` declaration's
`schema` uses. Here the `amount` column is read as a string rather than a number:

```shell
relix -i O=data/orders.csv --schema O='{ order_id: NUMBER, amount: STRING }' -e 'PROJECT order_id, amount (O)' -o json
```
```
[
  {"order_id":1,"amount":"120"},
  {"order_id":2,"amount":"80"},
  {"order_id":3,"amount":"50"},
  {"order_id":4,"amount":"200"}
]
```

`--schema` and `--unbounded` name an input bound with `-i`; naming any other is a usage
error.

### Inputs that never end

An input such as `tail -f`'s never ends. `--unbounded=NAME` declares that, and two things
follow. The rows stream: each is filtered, joined against bounded tables and written as
it arrives, rather than after the input ends. And a query that would have to read all of
the input before writing anything — a `GROUP`, a `SORT`, a `DISTINCT` — is refused before
it starts, rather than waiting forever:

```shell
printf '{"level":"error","msg":"disk"}\n{"level":"info","msg":"ok"}\n' | relix -i Log=ndjson:- --unbounded=Log -e "SELECT level = 'error' (Log)" -o ndjson
```
```
{"level":"error","msg":"disk"}
```

```shell
printf '{"level":"error"}\n' | relix -i Log=ndjson:- --unbounded=Log -e 'GROUP level, COUNT(*) -> n (Log)' -o ndjson 2>&1; echo "exit $?"
```
```
relix: -e: cannot materialise unbounded relation for blocking operator γ (GROUP); add a bound (e.g. λ n) below it
exit 3
```

A `table` needs every row before it can align the columns, so an unbounded input is
written in a format that streams — `tsv`, `csv`, `ndjson` or `json` — and on a terminal
that means naming one with `-o`. A `json` input is one array, read whole, so it cannot be
unbounded. Write one object per
line and bind it as `ndjson`. With `--line-buffered` each row is written out as soon as it
is produced, for a reader that wants it at once; see [Output formats](output.md).

### Files on the web

A location that is an `http://` or `https://` URL is fetched, and only with `--remote`,
which a run must give for anything to be fetched. Without it, binding a URL is a usage
error. The format comes from the URL's path, or is named.

### Shadowing the catalog

A bound input replaces a relation of the same name that the catalog declares, for this
run. Every view that reads the name reads the input instead, so a view written against the
real `Orders` can be run over a sample file without changing it:

```shell
relix -e 'BigSpenders'
```
```
── <expression 1> ──────────────────────────────────────────────────────────────
 name   total
 ─────  ─────
 Alice    200
 Carol    200
(2 rows)
```

```shell
relix -i Orders=reports/sample-orders.csv -e 'BigSpenders'
```
```
── <expression 1> ──────────────────────────────────────────────────────────────
 name   total
 ─────  ─────
 Alice    500
 Dan      250
(2 rows)
```

## `-a NAME=VALUE`: query parameters

A script can leave a value to be supplied when it runs, as a parameter: `$city` in

```shell
cat scripts/by-city.relix
```
```
-- The customers of one city, named when the script runs: relix -a city=London
query { PROJECT name (SELECT city = $city (Customers)) };
```

`-a city=London` binds it (the long form is `--arg`):

```shell
relix -a city=London scripts/by-city.relix
```
```
── <expression 1> ──────────────────────────────────────────────────────────────
 name
 ─────
 Alice
 Carol
(2 rows)
```

```shell
relix --arg city=Leeds scripts/by-city.relix -o tsv
```
```
name
Bob
```

The value is bound to the parameter, never pasted into the script's text, so whatever it
holds — quotes, a semicolon, a fragment of Relix — it is only ever a value, and cannot
change what the query does:

```shell
relix -a "city=London' OR 1 = 1 --" scripts/by-city.relix -o tsv
```
```
name
```

A parameter takes the type of what it is compared with, and the text `-a` gives is read
as that type: a number, a date, `true` or `false`. Text that is not of the type is an
error naming the parameter:

```shell
relix -a since=2026-09-15 -e 'PROJECT order_id, placed (SELECT placed >= $since (Orders))'
```
```
── <expression 1> ──────────────────────────────────────────────────────────────
 order_id  placed
 ────────  ──────────
        3  2026-09-20
        4  2026-10-01
(2 rows)
```

```shell
relix -a since=yesterday -e 'SELECT placed >= $since (Orders)' 2>&1; echo "exit $?"
```
```
relix: -e: parameter $since is compared with a DATE, so it cannot be bound to the text 'yesterday'
exit 3
```

A query cannot run until every parameter it reaches has a value:

```shell
relix scripts/by-city.relix 2>&1; echo "exit $?"
```
```
relix: scripts/by-city.relix: parameter $city is not bound; give it a value with -a city=VALUE
exit 3
```

The [parameter reference page](../../reference/language/parameter.md) has the rules in
full.

`-a` binds a value a query compares with. A value a *declaration* needs — a database's
address, a password, a token — is a placeholder, `${NAME}`, given by a profile, the
environment or `-D`, and the subject of [Secrets and profiles](secrets.md).
