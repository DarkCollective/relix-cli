# Pipelines

`relix` reads text and writes text, so it works with the programs a shell already has.
This page is a set of recipes: `relix` with `jq` for JSON, with `find` and `xargs` for
many files, with `awk`, `sort` and `head` for its rows, and as a check in CI.

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
