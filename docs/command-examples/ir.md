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
