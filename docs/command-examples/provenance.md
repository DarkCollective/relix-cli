## Examples

The default semiring, `counting`, says in how many ways each row is derived. Two London
customers each derive the row `London`:

```shell
relix provenance -e 'PROJECT city (Customers)'
```
```
<expression 1>
------  ------
city    [prov]
------  ------
London  2
Leeds   1
York    1
------  ------
(3 tuples; provenance semiring: counting)
```

The `lineage` semiring names the source rows each result row came from:

```shell
relix provenance --semiring=lineage -e 'PROJECT city (SELECT amount > 100 (Customers JOIN Orders))'
```
```
<expression 1>
------  -------------------------------------------
city    [prov]
------  -------------------------------------------
London  Customers#1·Orders#1 + Customers#3·Orders#4
------  -------------------------------------------
(1 tuple; provenance semiring: lineage)
```

In a pipe, or with `-o json`, the rows and their annotations are one JSON document:

```shell
relix provenance -e 'PROJECT city (Customers)' -o json
```
```
{
  "query": "<expression 1>",
  "semiring": "counting",
  "schema": ["city"],
  "tuples": [
    {"row": {"city": "London"}, "provenance": "2"},
    {"row": {"city": "Leeds"}, "provenance": "1"},
    {"row": {"city": "York"}, "provenance": "1"}
  ]
}
```

The engine's [provenance page](../../provenance.md) explains each semiring.
