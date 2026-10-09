## Examples

Each event ends with how long it took, which varies from run to run, so here `cut`
leaves the timings off:

```shell
relix trace -e 'SELECT amount > 100 (Orders)' | cut -d'(' -f1
```
```
[EXECUTE ]  orders  SCAN  scanned 4 rows
[EXECUTE ]  <expression 1>  ROWS  query delivered 2 rows
```

`relix run --trace` writes the same feed to standard error while the rows go to standard
output, and `--trace=FILE` writes it to a file:

```shell
relix --trace=trace.txt -e 'SELECT amount > 100 (Orders)' -o tsv
```
```
order_id	customer_id	amount	status	placed
1	1	120	completed	2026-09-02
4	3	200	completed	2026-10-01
```
