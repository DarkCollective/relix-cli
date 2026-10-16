# relix connection

Declares, lists, changes, removes and tests connections: what several sources share, such as a database and its credentials. Each add writes one declaration to a file of its own, catalog/00-connection-NAME.relix, as relix fmt prints it.

## Usage

```text
relix connection [OPTIONS] [COMMAND]
```

It also takes the [global options](relix.md#global-options).

## relix connection add

Declares the connection NAME of KIND: database (jdbc), a connector's name such as mongodb, or a file kind (csv, log, gedcom) whose path is LOCATION. Every option of the declaration is a flag, --OPTION=VALUE, its value a string. A secret is written as a ${NAME} placeholder, never its value. A name an outer level declares is allowed, and warned about. Writes the nearest project's catalog, or with --global ~/.relix's.

### Usage

```text
relix connection add [OPTIONS] NAME KIND [LOCATION]
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
| 5 | the file cannot be written |

## relix connection ls

Lists the connections the catalog declares, each as the declaration that won: name, kind (jdbc for a database), location (its url or path) and file.

### Usage

```text
relix connection ls [OPTIONS]
```

### Options

| Option | Description |
|---|---|
| `-o`, `--output=FORMAT` | The rows' format: table, tsv, csv, ndjson, json or markdown (default: $RELIX_OUTPUT, else relixrc's, else table on a terminal, tsv in a pipe). |
| `--no-header` | Leave out the header row of csv and tsv. |
| `--null=STRING` | How NULL is written (default: empty in csv, tsv and markdown; NULL in a table). |
| `--color=WHEN` | Colour a table: auto, always or never (default: auto). auto colours on a terminal unless $NO_COLOR is set. |

It also takes the [global options](relix.md#global-options).

## relix connection edit

Changes the connection NAME: sets each --OPTION=VALUE and removes each --unset OPTION. Only a declaration alone in its file is changed; one beside others is reported, with --editor to open it at its line.

### Usage

```text
relix connection edit [OPTIONS] NAME
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

It also takes the [global options](relix.md#global-options).

### Exit status

| Status | Meaning |
|---|---|
| 0 | success |
| 2 | no connection NAME, it shares its file with other declarations, or nothing to change |
| 5 | the file cannot be written |

## relix connection rm

Removes the connection NAME: the file that declares it, at the nearest level that does. Refused while a source or view reads NAME, unless --force; a declaration beside others in its file is reported, not touched. Says when a farther declaration of NAME now shows through.

### Usage

```text
relix connection rm [OPTIONS] NAME
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
| 2 | no connection NAME, another declaration reads it, or it shares its file |

## relix connection test

Opens and closes each connection NAME (default: every one) under the active profile, and lists name, status and problem: whether every placeholder has a value, its driver is installed and the database can be reached and logged into. Only a database connection is opened; any other is listed as untested.

### Usage

```text
relix connection test [OPTIONS] [NAME...]
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
| 0 | every connection passed or was untested |
| 2 | no connection NAME |
| 5 | a connection failed |

## Examples

```shell
relix connection ls -o tsv
```
```
name	kind	location	file
warehouse	jdbc	${WAREHOUSE_URL}	~/shop/.relix/catalog/20-warehouse.relix
```

Every option is a flag. A secret is a `${NAME}` placeholder, whose value comes from a
profile or the environment when a query runs:

```shell
relix connection add archive database --url='${ARCHIVE_URL}' --user='${ARCHIVE_USER}' --password='${ARCHIVE_PASSWORD}'
cat .relix/catalog/00-connection-archive.relix
```
```
connection archive from jdbc { password: "${ARCHIVE_PASSWORD}", url: "${ARCHIVE_URL}", user: "${ARCHIVE_USER}" };
```

and a secret given as its value is refused, so it never reaches the catalog:

```shell
relix connection add scratch database --url=jdbc:h2:mem:scratch --password=hunter2 2>&1; echo "exit $?"
```
```
relix: --password: a secret is written as a ${NAME} placeholder, never its value, which would stay in the catalog, your shell's history and ps; give --password='${NAME}' and set NAME with relix profiles set
exit 2
```

`test` opens and closes each connection under the profile:

```shell
relix connection test -P dev -o tsv; echo "exit $?"
```
```
name	status	problem
archive	failed	${ARCHIVE_PASSWORD} has no value: set it in a profile (-P), the environment or -D
warehouse	ok
exit 5
```

```shell
relix connection rm archive
relix connection test -P dev -o tsv
```
```
name	status	problem
warehouse	ok
```

[The catalog](../catalog.md#declaring-sources-and-connections) covers where declarations
go, and the trust a project keeps across them.
