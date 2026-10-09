## Examples

The rewritten script is printed in the same form the engine runs it in:

```shell
relix optimize -e "query { SELECT city = 'London' (Customers JOIN Orders) };"
```
```
query { (σ city = "London" (Customers)) ⋈ (Orders) };
```

`--report` prints the rules that fired, and each query before and after:

```shell
relix optimize --report -e "query { SELECT city = 'London' (Customers JOIN Orders) };"
```
```
════════════════════════════════════════════════════════════════════════════════
RELIX OPTIMIZER  namespace=default  transformations=1
════════════════════════════════════════════════════════════════════════════════

── SUMMARY ─────────────────────────────────────────────────────────────────────
 SEL-005  Selection pushed into join input ...................................×1

── TREES ───────────────────────────────────────────────────────────────────────

── <expression 1>
  before:  σ city = "London" ((Customers) ⋈ (Orders))
  after:   (σ city = "London" (Customers)) ⋈ (Orders)

── DETAIL ──────────────────────────────────────────────────────────────────────

── <expression 1> (1 transformation)
  [SEL-005]  selection pushed into left input of natural join @ <session>:1:9
════════════════════════════════════════════════════════════════════════════════
```

[Pipelines](../pipelines.md#optimise-a-script-and-review-the-change) compares a script
with its rewrite.
