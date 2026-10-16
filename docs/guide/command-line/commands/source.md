# relix source

Declares, lists, changes, removes and tests sources: the relations the catalog reads from files, databases and APIs. Each add writes one declaration to a file of its own, catalog/00-source-NAME.relix, as relix fmt prints it.

## Usage

```text
relix source [OPTIONS] [COMMAND]
```

It also takes the [global options](relix.md#global-options).

## relix source add

Declares the source NAME, from KIND, a csv or json file at LOCATION, a database, an http API or a generator, or from a connection the catalog declares. Every other option of the declaration is a flag: --OPTION=VALUE, or --OPTION for true. true, false, a number, and a { … } or [ … ] block are written as they are; anything else is a string. A secret is written as a ${NAME} placeholder, never its value. A name an outer level declares is allowed, and warned about. Writes the nearest project's catalog, or with --global ~/.relix's.

### Usage

```text
relix source add [OPTIONS] NAME KIND [LOCATION]
```

### Arguments

| Argument | Description |
|---|---|
| `NAME` | The name to declare. |
| `KIND` | What it is from: a kind (csv, json, database, http, generator), or for a source a connection's name. |
| `LOCATION` | The file it reads, relative to the working directory; for csv and json, and a file connection's path. |

### Options

| Option | Description |
|---|---|
| `--global` | Declare it in ~/.relix, which every directory under your home sees, rather than the nearest project. |
| `--infer-schema` | Read a sample, and write the schema it has. |

It also takes the [global options](relix.md#global-options).

### Exit status

| Status | Meaning |
|---|---|
| 0 | success |
| 2 | a required option is missing, a value is a secret, or NAME is declared here already |
| 5 | --infer-schema cannot read the data, or the file cannot be written |

## relix source ls

Lists the sources the catalog declares, each as the declaration that won: name, kind (csv, json, database, http, generator or the connection's name), location (the file, table or URL it reads) and file.

### Usage

```text
relix source ls [OPTIONS]
```

### Options

| Option | Description |
|---|---|
| `-o`, `--output=FORMAT` | The rows' format: table, tsv, csv, ndjson, json or markdown (default: $RELIX_OUTPUT, else relixrc's, else table on a terminal, tsv in a pipe). |
| `--no-header` | Leave out the header row of csv and tsv. |
| `--null=STRING` | How NULL is written (default: empty in csv, tsv and markdown; NULL in a table). |
| `--color=WHEN` | Colour a table: auto, always or never (default: auto). auto colours on a terminal unless $NO_COLOR is set. |

It also takes the [global options](relix.md#global-options).

## relix source edit

Changes the source NAME: sets each --OPTION=VALUE, removes each --unset OPTION, and with --location reads another file. Only a declaration alone in its file is changed; one beside others is reported, with --editor to open it at its line.

### Usage

```text
relix source edit [OPTIONS] NAME
```

### Arguments

| Argument | Description |
|---|---|
| `NAME` | The declaration to change. |

### Options

| Option | Description |
|---|---|
| `--unset=OPTION` | Remove OPTION. Repeatable. |
| `--infer-schema` | Read a sample, and write the schema it has in place of the one there. |
| `--editor` | Open the file in $VISUAL or $EDITOR at the declaration's line instead, wherever it is. |
| `--location=LOCATION` | Read the file at LOCATION, relative to the working directory. |

It also takes the [global options](relix.md#global-options).

### Exit status

| Status | Meaning |
|---|---|
| 0 | success |
| 2 | no source NAME, it shares its file with other declarations, or nothing to change |
| 5 | --infer-schema cannot read the data, or the file cannot be written |

## relix source rm

Removes the source NAME: the file that declares it, at the nearest level that does. Refused while another declaration reads NAME, unless --force; a declaration beside others in its file is reported, not touched. Says when a farther declaration of NAME now shows through.

### Usage

```text
relix source rm [OPTIONS] NAME
```

### Arguments

| Argument | Description |
|---|---|
| `NAME` | The declaration to remove. |

### Options

| Option | Description |
|---|---|
| `--force` | Remove it though other declarations read it. |

It also takes the [global options](relix.md#global-options).

### Exit status

| Status | Meaning |
|---|---|
| 0 | success |
| 2 | no source NAME, another declaration reads it, or it shares its file |

## relix source test

Reads one row of each source NAME (default: every one) under the active profile, and lists name, status and problem: whether its driver or connector is installed, every placeholder has a value, its location can be reached, and its declared schema matches what was read.

### Usage

```text
relix source test [OPTIONS] [NAME...]
```

### Arguments

| Argument | Description |
|---|---|
| `NAME...` | The declarations to test (default: all). |

### Options

| Option | Description |
|---|---|
| `-o`, `--output=FORMAT` | The rows' format: table, tsv, csv, ndjson, json or markdown (default: $RELIX_OUTPUT, else relixrc's, else table on a terminal, tsv in a pipe). |
| `--no-header` | Leave out the header row of csv and tsv. |
| `--null=STRING` | How NULL is written (default: empty in csv, tsv and markdown; NULL in a table). |
| `--color=WHEN` | Colour a table: auto, always or never (default: auto). auto colours on a terminal unless $NO_COLOR is set. |

It also takes the [global options](relix.md#global-options).

### Exit status

| Status | Meaning |
|---|---|
| 0 | every source passed |
| 2 | no source NAME |
| 5 | a source failed |

## Examples

```shell
relix source ls -o tsv
```
```
name	kind	location	file
Customers	csv	../../data/customers.csv	~/shop/.relix/catalog/00-sources.relix
Orders	csv	../../data/orders.csv	~/shop/.relix/catalog/00-sources.relix
Stock	warehouse	STOCK	~/shop/.relix/catalog/20-warehouse.relix
```

`add` writes one declaration to a file of its own. A relative `LOCATION` is written
relative to that file, so it still names the same file from anywhere in the project, and
`--infer-schema` reads the file for its columns:

```shell
relix source add People csv data/customers.csv --header --infer-schema
cat .relix/catalog/00-source-People.relix
```
```
source People from csv("../../data/customers.csv") { header: true, schema: { customer_id: number, name: string, city: string } };
```

`test` reads one row of each source under the profile, and checks the schema it declares
against what it read:

```shell
relix source test -P dev -o tsv
```
```
name	status	problem
Customers	ok
Orders	ok
People	ok
Stock	ok
```

A source another declaration reads is not removed without `--force`:

```shell
relix source rm Orders 2>&1; echo "exit $?"
```
```
relix: Orders is read by
  Completed (~/shop/.relix/catalog/10-views.relix:1)
remove those first, or give --force
exit 2
```

and one declared beside others in a file written by hand is reported, not changed:

```shell
relix source edit Customers --header=false 2>&1; echo "exit $?"
```
```
relix: ~/shop/.relix/catalog/00-sources.relix:2 declares Customers beside other declarations, which relix source does not edit for you; relix source edit Customers --editor
exit 2
```

```shell
relix source rm People
relix source ls -o tsv
```
```
name	kind	location	file
Customers	csv	../../data/customers.csv	~/shop/.relix/catalog/00-sources.relix
Orders	csv	../../data/orders.csv	~/shop/.relix/catalog/00-sources.relix
Stock	warehouse	STOCK	~/shop/.relix/catalog/20-warehouse.relix
```

[The catalog](../catalog.md#declaring-sources-and-connections) covers where declarations
go, and the trust a project keeps across them.
