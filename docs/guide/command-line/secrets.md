# Secrets and profiles

A connection to a database needs an address and a password; a source that calls an API
needs a token. None of them belongs in a script or a catalog file that is committed and
shared. They are written as **placeholders**, `${NAME}`, and the command supplies the
values when a query runs: from `-D`, from a named **profile**, or from the environment.

## Placeholders

The shop's stock lives in a database, declared in the catalog with placeholders where
its address and credentials go:

```shell
cat .relix/catalog/20-warehouse.relix
```
```
-- The stock database. Its address and credentials come from placeholders.
connection warehouse from database {
    url:      "${WAREHOUSE_URL}",
    user:     "${WAREHOUSE_USER}",
    password: "${WAREHOUSE_PASSWORD}"
};

source Stock from warehouse {
    table: "STOCK",
    schema: { sku: STRING, on_hand: NUMBER }
};
```

A placeholder may stand in any string value of a declaration: a URL, a file path, a
table name, a header, a credential. The engine's
[Secrets in declarations](../secrets.md) page has the rules.

A placeholder is resolved each time a query runs, for the declarations that query
reaches, and only then. A query that never reaches the warehouse needs none of its
values, and one that does fails before anything is sent when a value is missing, naming
the placeholder; [below](#the-environment-and-d) is an example.

Each `${NAME}` is looked up in three places, and the first that has it wins:

1. **`-D NAME=VALUE`** on the command line.
2. **The selected profile**, from the `profiles.json` files.
3. **The environment** of the process.

## Profiles

A profile is a named set of values, kept in a `profiles.json` file in a `.relix/`
directory: one JSON object whose members are profiles, each an object of string values.
The shop's project keeps the parts of its `dev` profile that are not secret:

```shell
cat .relix/profiles.json
```
```
{
  "dev": {
    "WAREHOUSE_URL": "jdbc:h2:~/shop/data/warehouse",
    "WAREHOUSE_USER": "shop"
  }
}
```

and the password is in your own `~/.relix/profiles.json`, outside the project and its
repository:

```shell
cat ~/.relix/profiles.json
```
```
{
  "dev": { "WAREHOUSE_PASSWORD": "s3cret" }
}
```

`-P NAME` (`--profile=NAME`) selects a profile. Its values are merged across every
`profiles.json` the run reads, outermost first, so a nearer file's value for a name
replaces a farther one's, and a profile may be split between your own file and the
project's, as `dev` is here:

```shell
relix -P dev -e 'Stock'
```
```
── <expression 1> ──────────────────────────────────────────────────────────────
 sku   on_hand
 ────  ───────
 mug        12
 tee         0
 tote       31
(3 rows)
```

Without `-P`, the profile is `$RELIX_PROFILE`, else the `profile` a
[`relixrc`](catalog.md#defaults-relixrc) names. With none of them, no profile is
selected and placeholders come from `-D` and the environment alone.

```shell
RELIX_PROFILE=dev relix -e 'PROJECT sku (SELECT on_hand = 0 (Stock))' -o tsv
```
```
sku
tee
```

Naming a profile no file defines is a usage error, which lists the ones that are defined:

```shell
relix -P production -e 'Stock' 2>&1; echo "exit $?"
```
```
relix: no profile 'production' (defined: dev)
exit 2
```

A project's `profiles.json` is part of its `.relix/` directory, so it is read only once
the project is [trusted](catalog.md#a-project-must-be-trusted); `~/.relix/profiles.json`
is always read.

### A profile file must be private

A profile may hold a password, so a `profiles.json` that its group or others can read is
refused, as `ssh` refuses a private key anyone can read. The error names the `chmod` that
fixes it:

```shell
chmod 644 ~/.relix/profiles.json
relix -P dev -e 'Stock' 2>&1; echo "exit $?"
chmod 600 ~/.relix/profiles.json
```
```
relix: ~/.relix/profiles.json is readable by group or others, and a profile may hold credentials; make it private with: chmod 600 ~/.relix/profiles.json
exit 5
```

Where the file system has no POSIX permissions, as on Windows, the check is not made.

## The environment, and `-D`

A value not in the selected profile comes from the environment, which is what a CI job's
secrets and a twelve-factor deployment use. Here no profile is selected: `-D` gives the
address and the user, and the environment the password:

```shell
WAREHOUSE_PASSWORD=s3cret relix -D WAREHOUSE_URL=jdbc:h2:$HOME/shop/data/warehouse -D WAREHOUSE_USER=shop -e 'Stock' -o tsv
```
```
sku	on_hand
mug	12
tee	0
tote	31
```

Without the password, the query fails before the connection is opened, and the error
names what is missing. The status is 3, as for any script that cannot be analysed:

```shell
relix -D WAREHOUSE_URL=jdbc:h2:$HOME/shop/data/warehouse -D WAREHOUSE_USER=shop -e 'Stock' 2>&1; echo "exit $?"
```
```
relix: -e: placeholder ${WAREHOUSE_PASSWORD} in connection 'warehouse' has no value: the session's placeholder resolver returned none
exit 3
```

`-D NAME=VALUE` sets one value for one run, and beats the profile and the environment.
It is for values that are not secret: a command line is kept in your shell's history and
shown by `ps` to everyone on the machine. A secret belongs in a private `profiles.json`,
or in the environment.

## What is never printed

The placeholder stays in the declaration, and the value goes only to the connector that
needs it, for the run that needs it. Commands that print a script or a plan —
`relix fmt`, `relix ir`, `relix bundle`, `relix explain`, `relix catalog` — print
`${WAREHOUSE_PASSWORD}`, never the password, and so does an error message:

```shell
relix fmt .relix/catalog/20-warehouse.relix
```
```
-- The stock database. Its address and credentials come from placeholders.
connection warehouse from jdbc { password: "${WAREHOUSE_PASSWORD}", url: "${WAREHOUSE_URL}", user: "${WAREHOUSE_USER}" };

source Stock from warehouse { table: "STOCK", schema: { sku: string, on_hand: number } };
```
