# relix ir

Prints the IR report: the symbols, views and queries the analyser made of each script.

## Usage

```text
relix ir [OPTIONS] [SCRIPT...]
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

```shell
relix ir -e 'Big := { SELECT amount > 100 (Orders) };' -e 'query Big;'
```
```
════════════════════════════════════════════════════════════════════════════════
RELIX IR  namespace=default
════════════════════════════════════════════════════════════════════════════════
  kind: SRC=source  INL=inline  SYS=system  QR=view  DB=database
        FN=function  TVF=table-fn  LIT=truth-literal
  type: N=number  S=string  B=boolean  ?=any  [x]=array  {…}=struct
        D=date  T=time  TS=timestamp  DUR=duration
  mat:  [bag]=bag  [set]=dedup-set  [sort]=sorted  (stream=no label)

── SYMBOLS ─────────────────────────────────────────────────────────────────────
 Big           QR   order_id:N  customer_id:N  amount:N  status:S  placed:D
 BigSpenders   QR   name:S  total:N
 catalog       SYS  name:S  kind:S  arity:N  schema_code:S
 columns       SYS  relation:S  column:S  type:S  ordinal:N  distinct_count:N  …
 Completed     QR   order_id:N  customer_id:N  amount:N  status:S  placed:D
 connections   SYS  name:S  dialect:S
 Customers     SRC  customer_id:N  name:S  city:S
 cycles        QR   name:S
 dependencies  QR   dependent:S  depends_on:S
 deps          TVF  (r:S)→relation
 events        SYS  seq:N  stage:S  code:S  description:S  target:S  rows:N  el…
 find          TVF  (col:S)→relation
 funcs         TVF  (cat:S)→relation
 functions     SYS  name:S  category:S  arity:N  max_arity:N  return_type:S  pu…
 impact        TVF  (r:S)→relation
 keys          SYS  relation:S  key:N  ordinal:N  column:S
 Orders        SRC  order_id:N  customer_id:N  amount:N  status:S  placed:D
 plan          SYS  query:S  node_id:N  parent_id:N  ordinal:N  op:S  label:S  …
 Regions       INL  city:S  region:S
 relations     SYS  name:S  kind:S  namespace:S  materialization:S  row_count:N…
 rules         QR   seq:N  code:S  description:S  target:S
 schema        TVF  (rel:S)→relation
 Spend         QR   customer_id:N  total:N
 Stock         SRC  sku:S  on_hand:N
 unused        QR   name:S
 version       SYS  component:S  kind:S  version:S

── EXPRESSION TREES ────────────────────────────────────────────────────────────
Big [QR]  order_id:N  customer_id:N  amount:N  status:S  placed:D
  σ amount > 100
  └─ Orders [SRC]

BigSpenders [QR]  name:S  total:N
  π name, total
  └─ ⋈
     ├─ Customers [SRC]
     └─ σ total ≥ 200
        └─ Spend [QR]

Completed [QR]  order_id:N  customer_id:N  amount:N  status:S  placed:D
  σ status = "completed"
  └─ Orders [SRC]

Spend [QR]  customer_id:N  total:N
  γ [customer_id] SUM(amount)→total  [bag]
  └─ Completed [QR]

── ROOT QUERIES ────────────────────────────────────────────────────────────────
  query Big [QR]
════════════════════════════════════════════════════════════════════════════════
```
