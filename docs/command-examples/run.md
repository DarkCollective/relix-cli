## Examples

`relix run` is what `relix` does with no command, so these two are the same:

```shell
relix run -e 'GROUP status, SUM(amount) -> total (Orders)'
```
```
── <expression 1> ──────────────────────────────────────────────────────────────
 status     total
 ─────────  ─────
 completed    400
 pending       50
(2 rows)
```

```shell
relix -e 'GROUP status, SUM(amount) -> total (Orders)' -o csv
```
```
status,total
completed,400
pending,50
```

Each script file is a run of its own, with its own declarations:

```shell
relix run scripts/top-customers.relix -a city=Leeds scripts/by-city.relix -o tsv
```
```
name	total
Alice	200
Carol	200
name
Bob
```

[Binding inputs](../inputs.md), [Output formats](../output.md) and
[Pipelines](../pipelines.md) cover the options in use.
