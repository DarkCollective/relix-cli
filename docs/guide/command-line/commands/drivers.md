# relix drivers

Lists and installs JDBC drivers. A connection to a database needs its driver, installed here ahead of a run, or fetched during one with --allow-download.

## Usage

```text
relix drivers [OPTIONS] [COMMAND]
```

It also takes the [global options](relix.md#global-options).

## relix drivers ls

Lists the drivers relix can install: name, the JDBC scheme each serves, and whether one is installed.

### Usage

```text
relix drivers ls [OPTIONS]
```

### Options

| Option | Description |
|---|---|
| `-o`, `--output=FORMAT` | The rows' format: table, tsv, csv, ndjson, json or markdown (default: $RELIX_OUTPUT, else relixrc's, else table on a terminal, tsv in a pipe). |
| `--no-header` | Leave out the header row of csv and tsv. |
| `--null=STRING` | How NULL is written (default: empty in csv, tsv and markdown; NULL in a table). |
| `--color=WHEN` | Colour a table: auto, always or never (default: auto). auto colours on a terminal unless $NO_COLOR is set. |

It also takes the [global options](relix.md#global-options).

## relix drivers install

Downloads the driver NAME, checks its checksum, and installs it.

### Usage

```text
relix drivers install [OPTIONS] NAME
```

### Arguments

| Argument | Description |
|---|---|
| `NAME` | A driver relix drivers ls lists, such as postgresql. |

It also takes the [global options](relix.md#global-options).

### Exit status

| Status | Meaning |
|---|---|
| 0 | installed, or already installed |
| 2 | no driver has that name |
| 5 | the download failed |

## Examples

```shell
relix drivers ls
```
```
── drivers ─────────────────────────────────────────────────────────────────────
 name        scheme            installed
 ──────────  ────────────────  ─────────
 postgresql  jdbc:postgresql:  false
 mysql       jdbc:mysql:       false
(2 rows)
```

`relix drivers install NAME` downloads one of those drivers, checks its checksum, and
installs it in `~/.relix/drivers`, or in `$RELIX_DRIVERS` when that is set. A run that
needs a driver that is not installed fails with exit status 5, unless `--allow-download`
lets it fetch the driver itself. The H2 driver is part of
`relix`, and needs no installing. A driver `relix drivers ls` does not list is installed
by copying its jar into the driver directory.
