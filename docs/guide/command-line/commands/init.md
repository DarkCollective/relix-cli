# relix init

Creates the .relix/ directory of DIR (default: the working directory): an empty catalog/, a profiles.json only you can read, a .gitignore that keeps profiles.json out of version control, and relixrc and root when asked for: -o and -P (the global option) write a relixrc with those defaults, and the profile -P names is defined, empty, in profiles.json. Then trusts the new directory, so the next run there loads it. Writes nothing to standard output.

## Usage

```text
relix init [OPTIONS] [DIR]
```

## Arguments

| Argument | Description |
|---|---|
| `DIR` | The project's directory, made if it does not exist. |

## Options

| Option | Description |
|---|---|
| `--root` | Write the root marker: discovery stops at this project, and the directories above it are not read. |
| `-o`, `--output=FORMAT` | Write a relixrc whose default output format is FORMAT. |

It also takes the [global options](relix.md#global-options).

## Exit status

| Status | Meaning |
|---|---|
| 0 | success |
| 2 | DIR already has a .relix/ directory, or an option is not valid |
| 5 | a file cannot be written |

## Examples

```shell
relix init ~/stall --root -P dev -o csv
find ~/stall/.relix | sort
cat ~/stall/.relix/relixrc
```
```
~/stall/.relix
~/stall/.relix/.gitignore
~/stall/.relix/catalog
~/stall/.relix/profiles.json
~/stall/.relix/relixrc
~/stall/.relix/root
output = csv
profile = dev
```

A directory that has a `.relix/` already is refused, and nothing in it changes:

```shell
relix init ~/stall 2>&1; echo "exit $?"
```
```
relix: ~/stall/.relix already exists; relix init makes a new project and never changes one
exit 2
```

[The catalog](../catalog.md#starting-a-project) covers what each file is for.
