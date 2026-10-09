## Examples

```shell
relix explain -e 'GROUP city, COUNT(*) -> n (Customers JOIN Orders)'
```
```
Aggregate
└─ Join NATURAL/HASH build=RIGHT
   ├─ Scan Customers
   └─ Scan Orders
```

`--json` writes each query's plan as a JSON object instead:

```shell
relix explain --json -e 'SELECT amount > 100 (Orders)' | jq -c 'keys'
```
```
["plan","query","script"]
```
