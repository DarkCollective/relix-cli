# relix fmt

Prints scripts canonically, keeping their comments. With no SCRIPT, formats standard input.

## Usage

```text
relix fmt [OPTIONS] [SCRIPT...]
```

## Arguments

| Argument | Description |
|---|---|
| `SCRIPT...` | Script files; - is standard input, which is also read when none is named. |

## Options

| Option | Description |
|---|---|
| `-w` | Rewrite each file that changes in place, instead of printing it. |
| `--check` | Change nothing; print the name of each file that would change, and exit 1 if any would. |
| `--glyphs` | Write operators as glyphs: σ, ⋈, ∧ (the default). |
| `--keywords` | Write operators as ASCII keywords: SELECT, JOIN, AND. |

It also takes the [global options](relix.md#global-options).

## Exit status

| Status | Meaning |
|---|---|
| 0 | success; with --check, no file would change |
| 1 | with --check, a file would change |
| 3 | a script does not parse |

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
