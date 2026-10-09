## Examples

A script that analyses prints nothing, and exits 0:

```shell
relix check scripts/top-customers.relix
```

A problem is printed on standard error, and the status is 3:

```shell
relix check scripts/broken.relix 2>&1; echo "exit $?"
```
```
scripts/broken.relix:1:30: error: Undefined relation: 'Ordrs'
exit 3
```

`--json` writes the diagnostics to standard output instead, as one document for a tool to
read:

```shell
relix check --json scripts/broken.relix; echo "exit $?"
```
```
{"diagnostics":[{"file":"scripts/broken.relix","line":1,"column":30,"severity":"error","message":"Undefined relation: 'Ordrs'"}]}
exit 3
```

[Pipelines](../pipelines.md#lint-every-script) checks every script in a tree.
