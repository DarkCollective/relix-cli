## Examples

```shell
cat scripts/messy.relix
```
```
-- Orders worth a look
Large:={select amount>100(Orders)};
query {project order_id,amount (Large)};
```

`relix fmt` prints it canonically — one statement per line, spaced, each operator in one
spelling — keeping its comments:

```shell
relix fmt scripts/messy.relix
```
```
-- Orders worth a look
Large := { σ amount > 100 (Orders) };
query { π order_id, amount (Large) };
```

`--keywords` writes the operators as ASCII keywords instead of glyphs:

```shell
relix fmt --keywords scripts/messy.relix
```
```
-- Orders worth a look
Large := { SELECT amount > 100 (Orders) };
query { PROJECT order_id, amount (Large) };
```

`--check` changes nothing and names each file that would change, exiting 1 if there is
one; `-w` rewrites each such file in place:

```shell
relix fmt --check --keywords scripts/messy.relix scripts/by-city.relix; echo "exit $?"
```
```
scripts/messy.relix
exit 1
```

```shell
relix fmt -w --keywords scripts/messy.relix
relix fmt --check --keywords scripts/messy.relix; echo "exit $?"
```
```
exit 0
```

With no file, `relix fmt` formats standard input to standard output:

```shell
echo 'query {select amount>100(Orders)};' | relix fmt
```
```
query { σ amount > 100 (Orders) };
```
