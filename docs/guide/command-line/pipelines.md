# Pipelines

`relix` reads text and writes text, so it works with the programs a shell already has.
This page is a set of recipes: `relix` with `jq` for JSON, with `awk`, `sort` and `head`
for its rows and for other programs' output, with another `relix`, with `find`, `xargs`
and shell loops for many files, and as a check in CI.

The rule that makes all of them work: **standard output carries rows and nothing else.**
Messages go to standard error, the format in a pipe is tab-separated values unless `-o`
says otherwise, and the [exit status](getting-started.md#when-something-goes-wrong) says
what happened.

## With jq

`jq` is the tool for shaping JSON, and `relix` the one for joining and aggregating it. A
pipeline passes JSON between them as NDJSON, one object per line, which `jq -c` writes,
`-i NAME=ndjson:-` reads and `-o ndjson` writes.

Here `jq` flattens the returns file, `relix` joins it to the customers and orders, and
`jq` formats the result:

```shell
jq -c '{order_id, reason, refund: .refund.amount}' data/returns.ndjson \
  | relix -i Returns=ndjson:- -e 'PROJECT name, order_id, refund (Customers JOIN Orders JOIN Returns)' -o ndjson \
  | jq -r '"\(.name) was refunded \(.refund) on order \(.order_id)"'
```
```
Alice was refunded 80 on order 2
Carol was refunded 20 on order 4
```

`relix` can also hand `jq` a single JSON document, an array, with `-o json`:

```shell
relix -e 'GROUP city, COUNT(*) -> customers (Customers)' -o json | jq -c 'map({(.city): .customers}) | add'
```
```
{"London":2,"Leeds":1,"York":1}
```

## With awk, sort and cut

Tab-separated values are what the classic text tools split on. The header is the first
line; `--no-header` leaves it out.

```shell
relix -e 'Orders' --no-header | awk -F'\t' '{ total[$4] += $3 } END { for (s in total) print s, total[s] }' | sort
```
```
completed 400
pending 50
```

### Another program's output, as a table

Any program that prints columns can feed a query: turn its output into tab-separated
values with a header line, and bind standard input as `tsv`. Here `awk` splits a web
server's log into its method and path, and `relix` counts the requests to each path:

```shell
printf 'GET /orders\nGET /customers\nGET /orders\nPOST /orders\n' > access.log
awk 'BEGIN { print "method\tpath" } { print $1 "\t" $2 }' access.log \
  | relix -i Hits=tsv:- -e 'SORT n DESC, path (GROUP path, COUNT(*) -> n (Hits))'
```
```
── <expression 1> ──────────────────────────────────────────────────────────────
 path        n
 ──────────  ─
 /orders     3
 /customers  1
(2 rows)
```

The last stage writes to the terminal, so it prints a table. The column types are inferred
from the values, so `n` is a number to sort on rather than text. `--schema` gives them instead, as [Binding inputs](inputs.md#the-heading-of-an-input)
shows.

## One relix into another

A stage's rows are the next stage's input, so two runs of `relix` compose like any other
two programs. Each run reads the catalog of the directory it runs in, which makes a pipe
the way to join tables two projects declare. The `reports` project declares its own
`Orders`, a fixed sample; the shop's run joins it to the shop's `Customers`:

```shell
(cd reports && relix -e 'Orders') | relix -i January=tsv:- -e 'PROJECT name, order_id, amount (Customers JOIN January)' -o csv
```
```
name,order_id,amount
Alice,101,500
Dan,102,250
```

The first run writes tab-separated values because its output is a pipe, and the second
reads them because `-i` says `tsv`. Within one project a view is the better tool: it is
one query, so the optimiser sees all of it.

### Keep the rows, print a summary

`tee` writes a copy of what passes through it to a file. Here the detail is kept in
`completed.csv`, and a second `relix` summarises it on the way past:

```shell
relix -e 'Completed' -o csv | tee completed.csv | relix -i C=csv:- -e 'GROUP COUNT(*) -> orders, SUM(amount) -> total (C)' -o tsv
cat completed.csv
```
```
orders	total
3	400
order_id,customer_id,amount,status,placed
1,1,120,completed,2026-09-02
2,1,80,completed,2026-09-14
4,3,200,completed,2026-10-01
```

## Stopping early

When the reader of a pipe stops, `relix` stops too: it cancels the query rather than
computing rows nobody will read. `head` can cut a large result short:

```shell
relix -e 'SORT amount DESC (Orders)' | head -3
```
```
order_id	customer_id	amount	status	placed
4	3	200	completed	2026-10-01
1	1	120	completed	2026-09-02
```

## With find and xargs

### Lint every script

`relix check` analyses scripts without running them, reporting each problem as
`FILE:LINE:COLUMN: severity: message` and exiting 3 if any script has one. Over every
script in a tree:

```shell
find scripts -name '*.relix' | sort | xargs relix check 2>&1 || echo "a script has a problem"
```
```
scripts/broken.relix:1:30: error: Undefined relation: 'Ordrs'
a script has a problem
```

`xargs` fails when any run of the command it starts fails, so the pipeline's status says
whether every script passed. Use `find … -print0 | xargs -0` when a file name may hold a
space.

### Run a query over each of many files

`-i` binds one file per run, so `xargs -I{}` runs a query over each file in turn. Here
each CSV file's row count, with its name:

```shell
find data reports -name '*.csv' | sort | xargs -I{} sh -c 'printf "%s\t" {}; relix -i T={} -e "GROUP COUNT(*) -> n (T)" -o tsv --no-header'
```
```
data/customers.csv	4
data/orders.csv	4
reports/sample-orders.csv	2
```

### One output file per value

A shell loop runs a script once for each value another query lists. The first run prints
each city, one per line with no header; the loop runs the
[parameterised](inputs.md#a-name-value-query-parameters) `by-city.relix` for each one and
writes its customers to a file of its own:

```shell
relix -e 'PROJECT city (Customers)' --no-header | while read -r city; do
  relix -a city="$city" scripts/by-city.relix -o csv > "customers-$city.csv"
done
ls customers-*.csv
cat customers-London.csv
```
```
customers-Leeds.csv
customers-London.csv
customers-York.csv
name
Alice
Carol
```

The city goes in as a parameter, so a name with a quote or a space in it is a value to
compare with, never part of the script.

### Check every script's formatting

`relix fmt --check` prints the name of each file that is not formatted canonically, and
exits 1 if there is one; `relix fmt -w` rewrites them in place. The shop's scripts spell
operators as ASCII keywords, so they are checked against `--keywords`:

```shell
find scripts -name '*.relix' | sort | xargs relix fmt --check --keywords || echo "a script needs formatting"
```
```
scripts/messy.relix
scripts/top-customers.relix
a script needs formatting
```

## Scripts written in the shell

A script can be written inline with a here-document, which keeps a longer query readable
and still needs no file:

```shell
relix -o csv <<'EOF'
Late := { SELECT placed >= DATE '2026-09-15' (Orders) };
query { PROJECT name, order_id (Customers JOIN Late) };
EOF
```
```
name,order_id
Bob,3
Carol,4
```

A value the shell knows goes in as a [parameter](inputs.md#a-name-value-query-parameters),
never pasted into the text:

```shell
city=London
relix -a city="$city" scripts/by-city.relix -o tsv
```
```
name
Alice
Carol
```

## A data check in CI

`--fail-rows` exits 1 when the query returns any row, and `--fail-empty` when it returns
none, so a data assertion is one line of a CI job:

```shell
relix --fail-rows -e 'SELECT amount <= 0 (Orders)' -o tsv > /dev/null && echo "every order has a positive amount"
```
```
every order has a positive amount
```

```shell
relix --fail-rows -e "SELECT status = 'pending' (Orders)" -o tsv || echo "pending orders found"
```
```
order_id	customer_id	amount	status	placed
3	2	50	pending	2026-09-20
pending orders found
```

A CI job runs in a fresh container where the project's `.relix/` cannot have been
trusted by hand; `RELIX_TRUST_ALL=1` trusts it, and a profile or the job's secret
environment variables supply the [placeholders](secrets.md).

### Has anything changed since yesterday?

`EXCEPT` keeps the rows of one input that are not in the other, so over two snapshots of
the same export it lists what is new. With `--fail-rows` the exit status says whether
there was anything:

```shell
cp data/orders.csv yesterday.csv
{ cat data/orders.csv; echo '5,4,90,pending,2026-10-12'; } > today.csv
relix -i Before=yesterday.csv -i After=today.csv --fail-rows -e 'After EXCEPT Before' -o tsv; echo "exit $?"
```
```
order_id	customer_id	amount	status	placed
5	4	90	pending	2026-10-12
exit 1
```

Swap the two inputs to list the rows that went away.

### In a workflow

The checks above are each one line of a job, which is all a CI system asks of them. A
GitHub Actions workflow, for instance, installs the Linux archive of the latest release
with `gh`, which its runners carry, then runs the checks, and adds a table to the run's
summary page, a file that takes Markdown:

```yaml
name: data checks
on: [push, pull_request]

jobs:
  check:
    runs-on: ubuntu-latest
    env:
      RELIX_TRUST_ALL: "1"
      GH_TOKEN: ${{ github.token }}
    steps:
      - uses: actions/checkout@v4
      - name: Install relix
        run: |
          gh release download -R DarkCollective/relix-cli -p 'relix-*-linux-x86_64.tar.gz'
          tar xzf relix-*-linux-x86_64.tar.gz
          echo "$PWD"/relix-*/bin >> "$GITHUB_PATH"
      - name: Every script analyses
        run: find scripts -name '*.relix' -print0 | xargs -0 relix check
      - name: Every order has a positive amount
        run: relix --fail-rows -e 'SELECT amount <= 0 (Orders)' -o tsv
      - name: Big spenders
        run: relix -e 'BigSpenders' -o markdown >> "$GITHUB_STEP_SUMMARY"
```

To pin a release instead of taking the latest, name its tag after `download`, as in
`gh release download v0.1.1 …`. The Markdown that last step appends is this:

```shell
relix -e 'BigSpenders' -o markdown
```
```
### <expression 1>

| name | total |
| --- | --- |
| Alice | 200 |
| Carol | 200 |
```

## Optimise a script, and review the change

`relix optimize` prints a script with each query rewritten by the optimiser, so it works
as a filter, and `diff` shows what it changed:

```shell
cat > slow.relix <<'EOF'
query { SELECT city = 'London' (Customers JOIN Orders) };
EOF
relix optimize slow.relix | diff slow.relix -; echo "exit $?"
```
```
1c1
< query { SELECT city = 'London' (Customers JOIN Orders) };
---
> query { (σ city = "London" (Customers)) ⋈ (Orders) };
exit 1
```

`diff` exits 1 when the files differ. To keep the rewritten script, redirect it:
`relix optimize slow.relix > fast.relix`. [`relix optimize --report`](commands/optimize.md)
lists the rules that fired instead.
