# Output formats

Rows go to standard output and nothing else does. Errors, warnings, notices and
progress go to standard error, so the rows can be piped, redirected or captured without
anything to strip out of them. This page covers the formats rows are written in, which
queries of a script are written, how a run can assert something about its rows, and how
a run ends when its reader stops reading.

## Choosing a format

`-o FORMAT` (`--output=FORMAT`) chooses one of:

| Format | Shape | Streams |
|---|---|---|
| `table` | aligned columns under a rule, with a row count | no |
| `tsv` | tab-separated values under a header row | yes |
| `csv` | comma-separated values under a header row, RFC 4180 | yes |
| `ndjson` | one JSON object per row, one per line | yes |
| `json` | one JSON array of objects | yes |
| `markdown` | a Markdown table | no |

Without `-o`, the format is `$RELIX_OUTPUT`, else the `output` a project's
[`relixrc`](catalog.md#defaults-relixrc) sets, else `table` when standard output is a
terminal and `tsv` when it is not. So the same command prints a table to a person and
tab-separated values to a pipe.

```shell
relix -e 'Customers' -o csv
```
```
customer_id,name,city
1,Alice,London
2,Bob,Leeds
3,Carol,London
4,Dan,York
```

```shell
relix -e 'Customers' -o ndjson
```
```
{"customer_id":1,"name":"Alice","city":"London"}
{"customer_id":2,"name":"Bob","city":"Leeds"}
{"customer_id":3,"name":"Carol","city":"London"}
{"customer_id":4,"name":"Dan","city":"York"}
```

```shell
relix -e 'PROJECT name, city (Customers)' -o json
```
```
[
  {"name":"Alice","city":"London"},
  {"name":"Bob","city":"Leeds"},
  {"name":"Carol","city":"London"},
  {"name":"Dan","city":"York"}
]
```

```shell
relix -e 'PROJECT name, city (Customers)' -o markdown
```
```
### <expression 1>

| name | city |
| --- | --- |
| Alice | London |
| Bob | Leeds |
| Carol | London |
| Dan | York |
```

```shell
RELIX_OUTPUT=tsv relix -e 'PROJECT name (Customers)'
```
```
name
Alice
Bob
Carol
Dan
```

`tsv` writes a tab, a newline, a carriage return or a backslash inside a value as `\t`,
`\n`, `\r` or `\\`, so that every line is one row and every tab separates two columns;
this is the form `cut`, `awk` and `sort -t` read. `csv` quotes a value holding a comma, a
quote or a line break instead. `json` and `ndjson` write numbers as JSON numbers, booleans
as `true` and `false`, NULL as `null`, and dates, times and durations as ISO-8601
strings.

A format that streams writes each row as it is produced, so a result larger than memory
goes straight through and the first rows arrive before the last are computed. `table` and
`markdown` align their columns, which needs every row first. In a table a value longer
than 30 characters is cut short, ending `…`; the machine formats write every value whole.

### Headers, NULLs and colour

| Option | Effect |
|---|---|
| `--no-header` | leave out the header row of `csv` and `tsv` |
| `--null=STRING` | write NULL as `STRING`; by default it is empty in `csv`, `tsv` and `markdown`, and `NULL` in a table |
| `--color=WHEN` | colour a table `auto`, `always` or `never`; `auto` colours on a terminal unless `$NO_COLOR` is set |

```shell
relix -e 'PROJECT name, order_id (Customers LJOIN Customers.customer_id = Orders.customer_id Orders)' -o csv --null=-
```
```
name,order_id
Alice,1
Alice,2
Bob,3
Carol,4
Dan,-
```

```shell
relix -e 'PROJECT name (Customers)' -o tsv --no-header
```
```
Alice
Bob
Carol
Dan
```

## Scripts with several queries

A script may hold several `query` statements. A table prints each of them, under its
name: the view's name for `query Name;`, and `<expression N>`, its place among the
script's queries, for `query { … };`.

```shell
relix -e 'query Completed;' -e 'query BigSpenders;'
```
```
── Completed ───────────────────────────────────────────────────────────────────
 order_id  customer_id  amount  status     placed
 ────────  ───────────  ──────  ─────────  ──────────
        1            1     120  completed  2026-09-02
        2            1      80  completed  2026-09-14
        4            3     200  completed  2026-10-01
(3 rows)
── BigSpenders ─────────────────────────────────────────────────────────────────
 name   total
 ─────  ─────
 Alice    200
 Carol    200
(2 rows)
```

A machine format holds one result, since a CSV file with two headers is not a CSV file. It
prints the script's **last** query, as a shell function returns its last command's status,
or the one `--query=NAME` names:

```shell
relix -e 'query Completed;' -e 'query BigSpenders;' -o tsv
```
```
name	total
Alice	200
Carol	200
```

```shell
relix -e 'query Completed;' -e 'query BigSpenders;' -o tsv --query=Completed
```
```
order_id	customer_id	amount	status	placed
1	1	120	completed	2026-09-02
2	1	80	completed	2026-09-14
4	3	200	completed	2026-10-01
```

`--all` prints every query in `ndjson`, each row tagged with the query it belongs to in a
`"_query"` field, so one stream can carry several results and `jq` can take them apart:

```shell
relix -e 'query Spend;' -e 'query BigSpenders;' -o ndjson --all
```
```
{"_query":"Spend","customer_id":1,"total":200}
{"_query":"Spend","customer_id":3,"total":200}
{"_query":"BigSpenders","name":"Alice","total":200}
{"_query":"BigSpenders","name":"Carol","total":200}
```

With any other machine format `--all` is a usage error.

Several script files on one command line are independent runs, and their output is
written one after another in the order the files were named.

## Asserting something about the rows

`--fail-empty` makes a run exit 1 when the queries it printed returned no rows, and
`--fail-rows` when they returned any. The rows are printed either way. This is the
`grep -q` idiom, for a data check in CI or in a shell `if`:

```shell
relix --fail-rows -e 'SELECT amount < 0 (Orders)' -o tsv; echo "exit $?"
```
```
order_id	customer_id	amount	status	placed
exit 0
```

```shell
relix --fail-rows -e "SELECT status = 'pending' (Orders)" -o tsv > /dev/null || echo "orders are still pending"
```
```
orders are still pending
```

[Pipelines](pipelines.md#a-data-check-in-ci) shows one in a CI job.

## Where messages go

A problem in a script is reported on standard error as `FILE:LINE:COLUMN: severity:
message`. `FILE` is the script's path, `-e` (or `-e#2` for the second of several `-e`
texts), or `<stdin>`; `LINE` and `COLUMN` count within that text, so a message about an
`-e` points into what was typed. A problem with the run itself — an option, a file that
is not there, a database that refuses the connection — is one line beginning `relix:`.

```shell
relix -e 'SELECT amount > 100 (Ordrs)' 2>&1; echo "exit $?"
```
```
-e:1:22: error: Undefined relation: 'Ordrs'
exit 3
```

Warnings are printed too, unless `-q` is given. `-v` adds notices — each catalog file
read, each JDBC driver loaded — and `-vv` adds how long each stage took. `relix check
--json` writes the diagnostics to standard output as one JSON document instead, for a
tool to read; see [`relix check`](commands/check.md).

The [exit status](getting-started.md#when-something-goes-wrong) says which kind of
failure it was.

## When the reader stops reading

A program at the other end of a pipe can stop reading at any time: `head` does after its
last line. When it does, `relix` stops: the query in flight is cancelled — a plan stops
pulling rows, a database cursor is closed — and the command exits 141, the status a shell
gives a program ended by `SIGPIPE`, without printing anything. So a query over a large or
endless relation can be cut short with `head`:

```shell
relix -e 'Orders' -o tsv | head -3
```
```
order_id	customer_id	amount	status	placed
1	1	120	completed	2026-09-02
2	1	80	completed	2026-09-14
```

Ctrl-C (`SIGINT`) likewise cancels the query and closes the session, and the command exits
130.

Standard output is written in blocks and flushed at the end of each result. With
`--line-buffered` each row is flushed as it is written, for a reader that wants each row
at once — an input following a log, read by something that reacts to each line.

## Following what the engine did

`--trace` writes the run's event feed — what the optimiser rewrote, the plan it chose,
how many rows each step produced — to standard error while the rows go to standard
output; `--trace=FILE` writes it to `FILE` instead. [`relix trace`](commands/trace.md)
prints the same feed on standard output, without the rows.
