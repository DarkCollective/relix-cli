# The catalog

A project's tables, connections and views are declared once, in its `.relix/` directory,
and every command run inside the project can name them. `relix` finds those directories by
walking up from where it starts, the way `git` finds `.git`, so the same command works
from any subdirectory. This page covers how the directories are laid out and found, how
a nearer declaration replaces a farther one, how to see what a run will load, and the
trust a project's directory needs before anything in it is loaded.

## The layout

A `.relix/` directory holds:

| Path | What it holds |
|---|---|
| `catalog/*.relix` | the declarations: `source`, `connection`, views (`Name := { … };`), `def`, `import` |
| `relixrc` | defaults for the commands run below it: the output format and the profile |
| `profiles.json` | named sets of `${VAR}` values; see [Secrets and profiles](secrets.md) |
| `root` | a marker that stops the walk up at this directory |

The shop the examples use has one:

```shell
find .relix -type f | sort
```
```
.relix/catalog/00-sources.relix
.relix/catalog/10-views.relix
.relix/catalog/20-warehouse.relix
.relix/profiles.json
```

```shell
cat .relix/catalog/00-sources.relix
```
```
-- The shop's data. A relative path resolves against this file's directory.
source Customers from csv("../../data/customers.csv") {
    header: true,
    schema: { customer_id: NUMBER, name: STRING, city: STRING }
};

source Orders from csv("../../data/orders.csv") {
    header: true,
    schema: { order_id: NUMBER, customer_id: NUMBER, amount: NUMBER, status: STRING, placed: DATE }
};
```

```shell
cat .relix/catalog/10-views.relix
```
```
Completed := { SELECT status = 'completed' (Orders) };

Spend := { GROUP customer_id, SUM(amount) -> total (Completed) };

BigSpenders := { PROJECT name, total (Customers JOIN (SELECT total >= 200 (Spend))) };
```

A catalog file holds declarations only; a `query` statement in one is an error. A
relative path in a catalog file resolves against the file's own directory, not the
directory the command runs in, so `../../data/orders.csv` above means `~/shop/data`
from wherever in the project `relix` is started.

Declaring costs nothing at run time: no file is read and no connection opened until a
query reaches the name. A large catalog costs a parse and an analysis, not a connection.

## Finding the directories

A run's catalog comes from these directories, loaded **outermost first**:

1. `~/.relix`, your own, which every directory under your home sees.
2. Each `.relix/` from the outermost directory down to the one the command starts in,
   walking up from it and stopping at your home directory. A `.relix/` that holds a file
   named `root` ends the walk there, so the projects of a monorepo do not inherit each
   other's declarations. Started outside your home directory, `relix` reads only that
   directory's own `.relix/`.
3. The files named by `$RELIX_CATALOG_PATH`, separated as in `PATH`, then those named
   with `--catalog FILE`, in order.

Within a directory, `catalog/*.relix` load in the lexical order of their names, so
`00-sources.relix` comes before `10-views.relix`, as in `/etc/*.d`.

`-N` (`--no-catalog`) leaves out every `.relix/` directory's catalog and `relixrc`;
`--catalog` files still load. `-C DIR` starts the walk from `DIR`.

## A project must be trusted

Loading code because of the directory you are standing in is a risk: a cloned
repository's `.relix/` could declare a source that sends a `${TOKEN}` from your
environment to a server of its author's choosing. So a project's `.relix/` is loaded only
once you have trusted it, as `direnv` does with an `.envrc`. Until then, it is skipped,
with a warning saying how to trust it, and a run that needs one of its names fails with
exit status 5:

```shell
relix -e 'Customers' 2>&1; echo "exit $?"
```
```
relix: warning: skipping ~/shop/.relix, which is not trusted; to load it: relix catalog trust ~/shop
-e:1:1: error: Undefined relation: 'Customers'
relix: Customers is declared in ~/shop/.relix/catalog/00-sources.relix, which is not trusted; to load it: relix catalog trust ~/shop
exit 5
```

`relix catalog trust` trusts the working directory's project, or the one it names. It
records a hash of every file under the `.relix/` directory in `~/.relix/trusted`:

```shell
relix catalog trust
relix -e 'PROJECT name (Customers)' -o tsv
```
```
name
Alice
Bob
Carol
Dan
```

Changing, adding or removing any file under a trusted `.relix/` — a catalog file, the
`relixrc`, the `profiles.json` — makes it untrusted again until it is trusted again, so a
`git pull` cannot change what runs without you seeing it. `--list` shows each trusted
project and whether it has changed since:

```shell
echo "Pending := { SELECT status = 'pending' (Orders) };" > .relix/catalog/30-pending.relix
relix catalog trust --list -o tsv
```
```
directory	state
~/shop	changed
```

```shell
relix catalog trust
relix catalog trust --list -o tsv
```
```
directory	state
~/shop	trusted
```

`--revoke DIR` removes a project from the record.

Some things are trusted without asking, because you chose them: `~/.relix`, a script
named on the command line or given with `-e` or on standard input, and `--catalog` and
`$RELIX_CATALOG_PATH` files. `RELIX_TRUST_ALL=1` trusts every directory, for a CI
container whose checkout is the only thing in it.

## A nearer declaration wins

When two directories declare the same name, the **nearer** one's declaration replaces the
farther one's. That is an overlay, not an error, and it is what lets a sub-project change
one table while keeping everything else above it. `~/shop/reports` declares its own
`Orders`, a fixed sample to write reports against:

```shell
cat reports/.relix/catalog/10-sample.relix
```
```
-- Reports are written against a fixed sample rather than the live orders.
source Orders from csv("../../sample-orders.csv") {
    header: true,
    schema: { order_id: NUMBER, customer_id: NUMBER, amount: NUMBER, status: STRING, placed: DATE }
};
```

Every view the shop declares reads whichever `Orders` is nearest, so `BigSpenders` from
inside `reports` is computed over the sample:

```shell
relix catalog trust reports
```

```shell
relix -e 'BigSpenders'
```
```
── <expression 1> ──────────────────────────────────────────────────────────────
 name   total
 ─────  ─────
 Alice    200
 Carol    200
(2 rows)
```

```shell
relix -C reports -e 'BigSpenders'
```
```
── <expression 1> ──────────────────────────────────────────────────────────────
 name   total
 ─────  ─────
 Alice    500
 Dan      250
(2 rows)
```

Each file is parsed first, the nearest declaration of every name kept, and only then are
the survivors defined together, so the order of the files decides which declaration
wins, never which can see which. A name is replaced only by a declaration of the same
kind: a relation by a relation, a function by a function.

A file in an untrusted directory neither declares nor replaces anything.

## Inspecting the catalog

`relix catalog` answers what a run would load, without running anything:

| Command | What it lists |
|---|---|
| `relix catalog files` | every catalog file, in load order, whether it is trusted, and the names it declares |
| `relix catalog ls` | every relation: its name, kind, number of columns, and the file whose declaration won |
| `relix catalog schema NAME` | the columns of a relation and their types |
| `relix catalog where NAME` | every declaration of a name, nearest first: the one that won, then those it replaced |

Each writes rows like any query, as a table on a terminal and in any `-o` format:

```shell
relix catalog files -o tsv
```
```
file	trusted	declares
~/.relix/catalog/00-regions.relix	true	Regions
~/shop/.relix/catalog/00-sources.relix	true	Customers Orders
~/shop/.relix/catalog/10-views.relix	true	Completed Spend BigSpenders
~/shop/.relix/catalog/20-warehouse.relix	true	warehouse Stock
~/shop/.relix/catalog/30-pending.relix	true	Pending
```

```shell
relix catalog ls -o tsv
```
```
name	kind	arity	file
BigSpenders	QR	2	~/shop/.relix/catalog/10-views.relix
Completed	QR	5	~/shop/.relix/catalog/10-views.relix
Customers	SRC	3	~/shop/.relix/catalog/00-sources.relix
Orders	SRC	5	~/shop/.relix/catalog/00-sources.relix
Pending	QR	5	~/shop/.relix/catalog/30-pending.relix
Regions	INL	2	~/.relix/catalog/00-regions.relix
Spend	QR	2	~/shop/.relix/catalog/10-views.relix
Stock	SRC	2	~/shop/.relix/catalog/20-warehouse.relix
```

The `kind` is the engine's: `SRC` a source, `INL` an inline table, `QR` a view, `DB` a
table reached through a connection.

```shell
relix catalog schema Orders
```
```
── Orders ──────────────────────────────────────────────────────────────────────
 column       type
 ───────────  ────
 order_id     N
 customer_id  N
 amount       N
 status       S
 placed       D
(5 rows)
```

The type codes are the engine's: `N` a number, `S` a string, `B` a boolean, `D` a date,
`T` a time, `TS` a timestamp, `DUR` a duration, `?` any type.

```shell
relix -C reports catalog where Orders -o tsv
```
```
file	line	kind	state
~/shop/reports/.relix/catalog/10-sample.relix	2	source	wins
~/shop/.relix/catalog/00-sources.relix	7	source	shadowed
```

## Catalog files from elsewhere

`--catalog FILE` adds a file to the catalog for one run, nearer than any `.relix/`
directory's. `$RELIX_CATALOG_PATH` names files the same way for every run. Both are
trusted, since you named them:

```shell
cat > ~/rates.relix <<'EOF'
Rates := [
| status    | fee |
|-----------|-----|
| completed | 2   |
| pending   | 0   |
];
EOF
relix --catalog ~/rates.relix -e 'PROJECT order_id, fee (Orders JOIN Rates)' -o tsv
```
```
order_id	fee
1	2
2	2
3	0
4	2
```

## Defaults: relixrc

A `relixrc` file sets defaults for the commands run below it, as `key = value` lines; a
line starting `#` is a comment. The keys are:

| Key | Default it sets | Beaten by |
|---|---|---|
| `output` | the output format | `-o`, `$RELIX_OUTPUT` |
| `profile` | the profile `${VAR}` placeholders resolve from | `-P`, `$RELIX_PROFILE` |

The nearest file's value for a key wins. A key the command does not know, or a line that
is not `key = value`, is reported as a warning and ignored, so a file written for a later
version still works. A `relixrc` is part of its `.relix/` directory, so it is read only
once the directory is trusted:

```shell
printf 'output = csv\n' > .relix/relixrc
relix catalog trust
relix -e 'PROJECT name (Customers)'
```
```
name
Alice
Bob
Carol
Dan
```
