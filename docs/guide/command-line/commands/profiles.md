# relix profiles

Lists, sets and removes the variables of the profiles ${VAR} placeholders resolve from (-P). Without a subcommand, lists them, as ls does. A value is never taken from an argument, and never listed.

## Usage

```text
relix profiles [OPTIONS] [COMMAND]
```

## Options

| Option | Description |
|---|---|
| `-o`, `--output=FORMAT` | The rows' format: table, tsv, csv, ndjson, json or markdown (default: $RELIX_OUTPUT, else relixrc's, else table on a terminal, tsv in a pipe). |
| `--no-header` | Leave out the header row of csv and tsv. |
| `--null=STRING` | How NULL is written (default: empty in csv, tsv and markdown; NULL in a table). |
| `--color=WHEN` | Colour a table: auto, always or never (default: auto). auto colours on a terminal unless $NO_COLOR is set. |

It also takes the [global options](relix.md#global-options).

## relix profiles ls

Lists each profile's variables and the file that sets each, outermost first: profile, variable and file, never the value. A profile with no variable is listed once, with an empty variable.

### Usage

```text
relix profiles ls [OPTIONS]
```

### Options

| Option | Description |
|---|---|
| `--user` | Use ~/.relix/profiles.json rather than the nearest project's. |
| `-o`, `--output=FORMAT` | The rows' format: table, tsv, csv, ndjson, json or markdown (default: $RELIX_OUTPUT, else relixrc's, else table on a terminal, tsv in a pipe). |
| `--no-header` | Leave out the header row of csv and tsv. |
| `--null=STRING` | How NULL is written (default: empty in csv, tsv and markdown; NULL in a table). |
| `--color=WHEN` | Colour a table: auto, always or never (default: auto). auto colours on a terminal unless $NO_COLOR is set. |

It also takes the [global options](relix.md#global-options).

## relix profiles set

Sets VARIABLE in PROFILE, creating either. The value is read without echoing it when standard input is a terminal, and from standard input otherwise, less one line break at its end; it is never taken from an argument, which would leave it in shell history and ps. Writes the nearest project's profiles.json, or with --user ~/.relix/profiles.json, making it readable by you alone.

### Usage

```text
relix profiles set [OPTIONS] PROFILE VARIABLE
```

### Arguments

| Argument | Description |
|---|---|
| `PROFILE` | The profile. |
| `VARIABLE` | The variable, as ${VARIABLE} names it. |

### Options

| Option | Description |
|---|---|
| `--user` | Use ~/.relix/profiles.json rather than the nearest project's. |

It also takes the [global options](relix.md#global-options).

### Exit status

| Status | Meaning |
|---|---|
| 0 | success |
| 2 | no project here (give --user), a bad name, or no value |
| 5 | the file is readable by others, or cannot be written |

## relix profiles get

Prints the value VARIABLE has in PROFILE, as a run under -P PROFILE would resolve it from the profiles.json files (the nearest wins), or with --user from ~/.relix/profiles.json alone. The environment and -D are not consulted.

### Usage

```text
relix profiles get [OPTIONS] PROFILE VARIABLE
```

### Arguments

| Argument | Description |
|---|---|
| `PROFILE` | The profile. |
| `VARIABLE` | The variable. |

### Options

| Option | Description |
|---|---|
| `--user` | Use ~/.relix/profiles.json rather than the nearest project's. |

It also takes the [global options](relix.md#global-options).

### Exit status

| Status | Meaning |
|---|---|
| 0 | success |
| 2 | no such profile, or it does not set VARIABLE |
| 5 | a profiles file is readable by others |

## relix profiles rm

Removes VARIABLE from PROFILE, or without VARIABLE the whole profile, in the nearest project's profiles.json, or with --user in ~/.relix/profiles.json.

### Usage

```text
relix profiles rm [OPTIONS] PROFILE [VARIABLE]
```

### Arguments

| Argument | Description |
|---|---|
| `PROFILE` | The profile. |
| `VARIABLE` | The variable. |

### Options

| Option | Description |
|---|---|
| `--user` | Use ~/.relix/profiles.json rather than the nearest project's. |

It also takes the [global options](relix.md#global-options).

### Exit status

| Status | Meaning |
|---|---|
| 0 | success |
| 2 | no project here, or the file does not set it |
| 5 | the file is readable by others, or cannot be written |

## Examples

```shell
relix profiles ls -o tsv
```
```
profile	variable	file
dev	WAREHOUSE_PASSWORD	~/.relix/profiles.json
dev	WAREHOUSE_URL	~/shop/.relix/profiles.json
dev	WAREHOUSE_USER	~/shop/.relix/profiles.json
```

The value comes from standard input, or a prompt that does not echo it on a terminal,
never from an argument:

```shell
printf 'jdbc:h2:mem:staging\n' | relix profiles set staging WAREHOUSE_URL
relix profiles get staging WAREHOUSE_URL
```
```
jdbc:h2:mem:staging
```

```shell
relix profiles rm staging
relix profiles ls -o tsv
```
```
profile	variable	file
dev	WAREHOUSE_PASSWORD	~/.relix/profiles.json
dev	WAREHOUSE_URL	~/shop/.relix/profiles.json
dev	WAREHOUSE_USER	~/shop/.relix/profiles.json
```

[Secrets and profiles](../secrets.md#setting-profile-values) covers where values come
from, and what keeps them private.
