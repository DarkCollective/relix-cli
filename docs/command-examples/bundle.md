## Examples

A bundle is one JSON document per script, for a tool such as a query playground:

```shell
relix bundle -e 'SELECT amount > 100 (Orders)' | jq -c 'keys'
```
```
["diagnostics","namespace","queries"]
```
