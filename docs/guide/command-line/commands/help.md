# relix help

When no COMMAND is given, the usage help for the main command is displayed. If a COMMAND is specified, the help for that command is shown.

## Usage

```text
relix help [OPTIONS] [COMMAND]
```

## Arguments

| Argument | Description |
|---|---|
| `COMMAND` | The COMMAND to display the usage help message for. |

It also takes the [global options](relix.md#global-options).

## Examples

`relix help COMMAND` prints what `relix COMMAND --help` prints:

```shell
relix help version | head -3
```
```
Usage: relix version [-hNqVv] [--allow-download] [--remote] [-C=DIR]
                     [--max-fixpoint-rounds=N] [--max-materialized-rows=N]
                     [--max-processed-rows=N] [--now=INSTANT] [-P=NAME]
```
