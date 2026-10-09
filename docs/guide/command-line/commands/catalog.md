# relix catalog

Inspects the catalog: the declarations a run takes from the .relix/ directories from ~/.relix down to the working directory, and from --catalog files. A project's .relix/ is loaded only once it is trusted (relix catalog trust).

## Usage

```text
relix catalog [OPTIONS] [COMMAND]
```

It also takes the [global options](relix.md#global-options).

## relix catalog files

Lists every catalog file, in the order they load, with the names each declares and whether it is trusted. An untrusted file is listed but not loaded.

### Usage

```text
relix catalog files [OPTIONS]
```

### Options

| Option | Description |
|---|---|
| `-o`, `--output=FORMAT` | The rows' format: table, tsv, csv, ndjson, json or markdown (default: $RELIX_OUTPUT, else relixrc's, else table on a terminal, tsv in a pipe). |
| `--no-header` | Leave out the header row of csv and tsv. |
| `--null=STRING` | How NULL is written (default: empty in csv, tsv and markdown; NULL in a table). |
| `--color=WHEN` | Colour a table: auto, always or never (default: auto). auto colours on a terminal unless $NO_COLOR is set. |

It also takes the [global options](relix.md#global-options).

## relix catalog ls

Lists the relations the catalog declares: name, kind, arity and the file whose declaration won. kind is the engine's: SRC a source, INL an inline table, QR a view, DB a connection's table.

### Usage

```text
relix catalog ls [OPTIONS]
```

### Options

| Option | Description |
|---|---|
| `-o`, `--output=FORMAT` | The rows' format: table, tsv, csv, ndjson, json or markdown (default: $RELIX_OUTPUT, else relixrc's, else table on a terminal, tsv in a pipe). |
| `--no-header` | Leave out the header row of csv and tsv. |
| `--null=STRING` | How NULL is written (default: empty in csv, tsv and markdown; NULL in a table). |
| `--color=WHEN` | Colour a table: auto, always or never (default: auto). auto colours on a terminal unless $NO_COLOR is set. |

It also takes the [global options](relix.md#global-options).

## relix catalog schema

Lists the columns of the relation NAME and their types, in order.

### Usage

```text
relix catalog schema [OPTIONS] NAME
```

### Arguments

| Argument | Description |
|---|---|
| `NAME` | A relation the catalog declares. |

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
| 0 | success |
| 3 | the catalog declares no relation NAME, or does not analyse |

## relix catalog where

Lists every declaration of NAME, nearest first: the one that won, then the ones it shadowed. A declaration in an untrusted directory is listed as untrusted.

### Usage

```text
relix catalog where [OPTIONS] NAME
```

### Arguments

| Argument | Description |
|---|---|
| `NAME` | A name the catalog declares. |

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
| 0 | success |
| 3 | the catalog declares no NAME |

## relix catalog trust

Trusts the .relix/ directory of DIR (default: the working directory), as its files are now: a project's catalog, relixrc and profiles.json are loaded only once it is trusted, and changing, adding or removing a file there means trusting it again. ~/.relix, --catalog files and the script itself are always trusted; RELIX_TRUST_ALL=1 trusts everything, for a CI container.

### Usage

```text
relix catalog trust [OPTIONS] [DIR]
```

### Arguments

| Argument | Description |
|---|---|
| `DIR` | The project to trust, or its .relix/ directory. |

### Options

| Option | Description |
|---|---|
| `--list` | List the trusted directories and whether each has changed since. |
| `--revoke=DIR` | Stop trusting DIR. |
| `-o`, `--output=FORMAT` | The rows' format: table, tsv, csv, ndjson, json or markdown (default: $RELIX_OUTPUT, else relixrc's, else table on a terminal, tsv in a pipe). |
| `--no-header` | Leave out the header row of csv and tsv. |
| `--null=STRING` | How NULL is written (default: empty in csv, tsv and markdown; NULL in a table). |
| `--color=WHEN` | Colour a table: auto, always or never (default: auto). auto colours on a terminal unless $NO_COLOR is set. |

It also takes the [global options](relix.md#global-options).

## Examples

```shell
relix catalog ls -o tsv
```
```
name	kind	arity	file
BigSpenders	QR	2	~/shop/.relix/catalog/10-views.relix
Completed	QR	5	~/shop/.relix/catalog/10-views.relix
Customers	SRC	3	~/shop/.relix/catalog/00-sources.relix
Orders	SRC	5	~/shop/.relix/catalog/00-sources.relix
Regions	INL	2	~/.relix/catalog/00-regions.relix
Spend	QR	2	~/shop/.relix/catalog/10-views.relix
Stock	SRC	2	~/shop/.relix/catalog/20-warehouse.relix
```

```shell
relix catalog schema Customers -o tsv
```
```
column	type
customer_id	N
name	S
city	S
```

```shell
relix catalog where Orders -o tsv
```
```
file	line	kind	state
~/shop/.relix/catalog/00-sources.relix	7	source	wins
```

```shell
relix catalog trust --list -o tsv
```
```
directory	state
~/shop	trusted
~/shop/reports	trusted
```

[The catalog](../catalog.md) covers discovery, the overlay and trust.
