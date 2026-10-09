# relix connectors

Lists and installs connector plugins, such as mongodb. A source of a type no installed connector reads needs one, installed here ahead of a run, or fetched during one with --allow-download.

## Usage

```text
relix connectors [OPTIONS] [COMMAND]
```

It also takes the [global options](relix.md#global-options).

## relix connectors ls

Lists the connectors installed: those shipped with relix and the plugins installed in ~/.relix/connectors. --available adds the ones the hosted catalog offers, which it fetches.

### Usage

```text
relix connectors ls [OPTIONS]
```

### Options

| Option | Description |
|---|---|
| `--available` | Also list the connectors that can be installed, from the hosted catalog. |
| `-o`, `--output=FORMAT` | The rows' format: table, tsv, csv, ndjson, json or markdown (default: $RELIX_OUTPUT, else relixrc's, else table on a terminal, tsv in a pipe). |
| `--no-header` | Leave out the header row of csv and tsv. |
| `--null=STRING` | How NULL is written (default: empty in csv, tsv and markdown; NULL in a table). |
| `--color=WHEN` | Colour a table: auto, always or never (default: auto). auto colours on a terminal unless $NO_COLOR is set. |

It also takes the [global options](relix.md#global-options).

## relix connectors install

Downloads the connector plugin TYPE, checks its checksums, and installs it.

### Usage

```text
relix connectors install [OPTIONS] TYPE
```

### Arguments

| Argument | Description |
|---|---|
| `TYPE` | A connector type, such as mongodb. |

It also takes the [global options](relix.md#global-options).

### Exit status

| Status | Meaning |
|---|---|
| 0 | installed, or already installed |
| 2 | the catalog has no connector of that type |
| 5 | the download failed |

## Examples

The connectors that ship with `relix`, and any plugins installed in `~/.relix/connectors`:

```shell
relix connectors ls
```
```
── connectors ──────────────────────────────────────────────────────────────────
 type    installed
 ──────  ─────────
 clf     true
 csv     true
 gedcom  true
 log     true
(4 rows)
```

`relix connectors ls --available` adds the connectors that can be installed, which it
fetches from the hosted catalog, and `relix connectors install TYPE` installs one. A
source whose type no installed connector reads — `mongodb`, say — fails with exit status
5, unless `--allow-download` lets the run fetch the connector.
