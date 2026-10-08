# Design: a pipeline-native `relix` command

Status: **accepted** (2026-10-06). Decisions are recorded in §10.

## 1. Starting point

An earlier, unpublished `relix` command was a *report generator with an execute flag*. Its default output is
the IR report; rows need `--exec`; each other view (`--optimize`, `--explain`,
`--trace`, `--bundle`, `--provenance`) is a flag that adds a section to one combined
stdout. That suits reading a script, but it makes a poor pipeline stage:

| Problem | Where it shows |
|---|---|
| stdout mixes reports and rows | `--exec --optimize` interleaves a rewrite report with the table; the machine formats work only because reports are *suppressed* when `-f` is not `table` |
| No way to feed **data** in | stdin is always a script. `jq … \| relix` cannot work, and a CSV piped in has nowhere to go |
| No way to name the tables a script reads, except inside the script | every piped-in script has to declare its own sources, so `echo 'σ amount > 100 (Orders)' \| relix` cannot work |
| Exit status is binary | `1` covers a typo, a dead database and a failed assertion alike |
| No end-of-pipe discipline | `relix --exec big.relix \| head` keeps pulling rows into a closed pipe (`PrintWriter` swallows the `EPIPE`) |
| Machine output is incomplete | no TSV, no NDJSON (the format `jq -c`, `mlr` and log tools exchange) |
| Download permission is a run flag | `--download-drivers` mixes installing software into running a query |
| Startup | a cold JVM per stage of a pipeline |

## 2. Principles

1. **stdout is data, stderr is talk.** Every subcommand writes exactly one artefact to
   stdout: rows, a plan, a script, a JSON document. Diagnostics, progress, loaded-driver
   notices and traces go to stderr. Nothing is "suppressed for machine formats",
   because nothing else is ever on stdout.
2. **One verb, one job** (`git`/`kubectl` style). The views that are flags today become
   subcommands, so each has a well-defined output.
3. **Composable both ways.** Relix text can arrive on stdin, and so can rows. Rows can
   leave as text formats other tools read (`csv`, `tsv`, `ndjson`, `json`).
4. **The directory supplies the context.** A project's tables, connections and views are
   declared once in `.relix/` directories and found by walking up from the working
   directory, the way `git` finds `.git` and `editorconfig` finds `.editorconfig`.
5. **Ordinary conventions, no surprises.** POSIX short options that cluster, GNU long
   options with `--opt=value`, `--` ends options, `-` means stdin, `isatty` decides the
   human-or-machine defaults, `NO_COLOR` is honoured, `SIGPIPE` and `SIGINT` end a run
   cleanly, exit codes distinguish failure classes.
6. **Only the engine's published API.** The command builds a `Relix` session and drives
   `Relation` terminals, as today; no engine internals. Where the API is short of what a
   pipeline needs, §9 lists it as a request rather than working around it.

## 3. The command surface

```
relix [GLOBAL OPTIONS] <command> [OPTIONS] [ARGS]
relix [GLOBAL OPTIONS] [RUN OPTIONS] [SCRIPT...]       # `run` is the default command
```

| Command | stdout | Replaces |
|---|---|---|
| `relix run` *(default)* | result rows | `--exec` |
| `relix check` | nothing (diagnostics on stderr), or `--json` | today's default IR run, used as a validator |
| `relix explain` | the physical plan (`--json` for machine form) | `--explain` |
| `relix optimize` | the **rewritten Relix text** (`--report` for the rules and before/after trees) | `--optimize` |
| `relix trace` | the event feed (rows discarded) | `--trace` |
| `relix bundle` | the playground JSON bundle | `--bundle` |
| `relix ir` | the IR report | `--ir` / default |
| `relix provenance` | annotated rows (`--semiring`, `--weight`) | `--provenance` |
| `relix fmt` | the script, printed canonically (`--glyphs`/`--keywords`) | *new* |
| `relix catalog` | `ls`, `schema NAME`, `where NAME`, `files`, `trust` | *new* (§5) |
| `relix profiles` | the named environments and where each came from | `relix env` |
| `relix drivers` | `ls`, `install NAME` | `--download-drivers` |
| `relix connectors` | `ls`, `install NAME` | `relix connectors`, `--download-connectors` |
| `relix doc [TOPIC]` | a reference page (paged on a TTY) | `relix help lang …` |
| `relix help [COMMAND]` | command usage | `relix help` |
| `relix completion SHELL` | a completion script (bash, zsh, fish, powershell) | *new* |
| `relix version` | the front-end and engine versions | `relix version` |

`relix optimize` deserves a note: it makes the *read a query, optimise it, hand back
better text* use the engine's `Relation` was designed for into a filter:

```bash
relix optimize slow.relix > fast.relix
```

### 3.1 Where the script comes from

In precedence order, for every command that reads Relix:

1. `-e TEXT` / `--expr=TEXT`, repeatable (as `sed -e`, `perl -e`). A bare relational
   expression is accepted and treated as `query { … };` — `Relix.relation(String)`
   already parses exactly that — so short queries need no ceremony.
2. `SCRIPT...` file arguments; `-` names stdin.
3. stdin, when there are no `-e`/file arguments and stdin is not a terminal.
4. Otherwise (a terminal and nothing given): print usage, exit 2.

Several script files are independent runs, each in its own session, as today: two
scripts on one command line must not see each other's declarations. Their outputs are
concatenated on stdout in order (for `table` with a `== file ==` separator to stderr).

### 3.2 Where data comes from: binding inputs

`-i NAME=SPEC` / `--input NAME=SPEC`, repeatable, binds a relation name for the run:

```
SPEC   :=  [FORMAT:]LOCATION
FORMAT :=  csv | tsv | json | ndjson        (default: from the extension; stdin needs one)
LOCATION := a path | - (stdin) | an http(s) URL (needs --remote)
```

```bash
# jq shapes the API response, relix joins it with a table from the catalog
curl -s https://api.example.com/orders | jq -c '.items[]' \
  | relix -i orders=ndjson:- -e 'orders ⋈ Customers' -o csv

# a file found by find, bound positionally
find logs -name '*.csv' -newer last-run \
  | xargs -I{} relix -i r={} -e "σ status = 'FAILED' (r)" --no-header
```

Only one input may be stdin, and when it is, the script must come from `-e` or a file.
Bound inputs **shadow** catalog relations of the same name, which is how a catalog
view written against the real `Orders` table is run against a sample file:
`relix -i Orders=sample.csv -e 'BigSpenders'`.

The schema of a bound input is inferred (header for CSV/TSV, first N rows for
JSON/NDJSON — `--infer-rows=N`), or given explicitly with `--schema NAME='{ id: NUMBER, … }'`.
Inference and stream reading are API request **R1** (§9).

An input that never ends, such as `tail -f`'s, is declared with `--unbounded=NAME`: its rows
stream as they arrive, and a query that would have to read all of it first is refused.

### 3.3 Where data goes: output formats

`-o FORMAT` / `--output=FORMAT`, or `RELIX_OUTPUT`:

| Format | Streams | Notes |
|---|---|---|
| `table` | no (aligns columns) | default **on a terminal** |
| `tsv` | yes | default **in a pipe**; IANA TSV, tabs/newlines in values escaped `\t`/`\n` — what `cut`, `awk`, `sort -t$'\t'` and `column -t` expect |
| `csv` | yes | RFC 4180 |
| `ndjson` | yes | one object per line — the `jq -c` / `mlr --ijsonl` exchange format |
| `json` | yes | one array |
| `markdown` | no | |

Format modifiers: `--no-header` (csv/tsv), `--null=STRING` (how NULL is written; default
empty for csv/tsv, `NULL` in a table), `--color=auto|always|never`.

`tsv` and `ndjson` are formats of the command's own `OutputFormat` (§7.2); they are
renderers and need nothing from the engine.

**A script with several `query` statements.** `table` prints each with its label, as
today. A machine format prints **one** result set, because a CSV with two headers is not
a CSV: by default the *last* query (the way a shell function returns its last command),
or the one named with `--query=NAME` (long form only: `-q` is `--quiet`, as in nearly
every Unix tool). `--all` with `ndjson` emits every
query, each row tagged `"_query": NAME`; with other machine formats `--all` is a usage
error.

### 3.4 Exit status

| Code | Meaning |
|---|---|
| 0 | success |
| 1 | an assertion asked for failed: `--fail-empty` (no rows) or `--fail-rows` (any rows) — the `grep -q` idiom, for data checks in CI and shell `if` |
| 2 | usage error (bad option, unknown format, missing argument) |
| 3 | the script did not parse or analyse |
| 4 | execution failed (a data error, a source that could not be read, a limit hit) |
| 5 | environment: a missing driver/connector, an untrusted catalog, a connection refused |
| 70 | internal error — a defect in relix (`EX_SOFTWARE`; the stack trace goes to stderr) |
| 130 / 141 | interrupted (`SIGINT`) / downstream closed (`SIGPIPE`) — conventional shell values |

`relix check` exits 0 or 3, so `find . -name '*.relix' -print0 | xargs -0 relix check`
is a lint pass.

### 3.5 Diagnostics

`FILE:LINE:COL: severity: message` on stderr — the `gcc`/`grep -n` shape that editors,
`vim`'s quickfix and CI annotators already parse.
`relix check --json` emits the same list as one JSON document on stdout for tools.
`-q` silences warnings, `-v` adds notices (loaded drivers, catalog files read), `-vv`
adds timings.

### 3.6 Pipe and signal discipline

- **`SIGPIPE`/`EPIPE`**: the row sink checks for a write failure after each row; on one,
  it closes the `Stream<Tuple>`, which the engine already treats as cancellation (a
  pull-based plan stops pulling, a JDBC cursor is closed), and exits 141 silently.
  `relix -e 'Naturals' | head -5` must terminate.
- **`SIGINT`**: a shutdown hook closes the active stream and the session; exit 130.
- **Streaming is the default** for every format except `table`/`markdown`, so a result
  larger than memory goes straight through, and `tail -f access.ndjson | relix -i
  log=ndjson:- -e 'σ status >= 500 (log)'` emits as lines arrive (needs R1's unbounded
  form; the engine's boundedness check already refuses a blocking operator over it).
- stdout is a `BufferedOutputStream` flushed at the end of each result set, and per row
  with `--line-buffered` (as `grep --line-buffered`) for interactive tails.

## 4. Global and run options

| Option | Meaning | Current |
|---|---|---|
| `-C DIR` | act as if started in DIR (catalog discovery, relative paths) — `git -C`, `make -C`, `tar -C` | — |
| `-P NAME`, `--profile=NAME` | the named profile `${VAR}`s resolve from (`RELIX_PROFILE`) | `-e/--env` (renamed: `-e` is the expression, by convention) |
| `-D NAME=VALUE` | set one `${VAR}` for this run (`awk -v`, `cc -D`); beats the profile. Not for secrets: it lands in shell history and `ps` | — |
| `-N`, `--no-catalog` | ignore `.relix/` discovery | — |
| `--catalog=FILE` | add a catalog file (repeatable; `RELIX_CATALOG_PATH`, `:`-separated) | — |
| `--now=INSTANT` | pin `NOW()` (`RELIX_NOW`) | same |
| `--max-fixpoint-rounds=N` | | same |
| `--max-materialized-rows=N` | | same |
| `--max-processed-rows=N` | *new*: exposes `Builder.maxProcessedRows` | — |
| `--timeout=DURATION` | *new*: exposes `Builder.timeout` (`30s`, `5m`) | — |
| `--sandbox=FILE` | *new*: run under `Sandbox.load(FILE)` | — |
| `--remote` | permit `http(s)` file locations (`Builder.remoteFiles`) | — |
| `--allow-download` | permit fetching a missing driver/connector during a run | `--download-drivers`, `--download-connectors` |
| `--trace[=FILE]` | on `run`: write the event feed to stderr or FILE *while* rows go to stdout | — (today `--trace` discards rows) |

`--trace` on `run` is a real improvement the split makes possible: today the trace
drains the query for its events and throws the rows away, because both wanted stdout.

## 5. The catalog: context from the directory hierarchy

### 5.1 Layout

```
~/.relix/                       user level (already the home of drivers/, connectors/, models/)
    catalog/*.relix
    profiles.json               named profiles of ${VAR} values
~/work/acme/.relix/             a project
    catalog/*.relix             sources, connections, views, defs, relate
    profiles.json
    relixrc                     defaults: output=…, profile=…  (key = value lines)
~/work/acme/reports/.relix/     a sub-project: more views, or a different `Orders`
    catalog/*.relix
```

### 5.2 Discovery

1. Walk from the working directory (or `-C DIR`) upward, collecting each `.relix/`
   directory, and stop after `$HOME` — the same ceiling `profiles.json` is found
   under, so the two cannot disagree. A directory whose
   `.relix/` holds a `root` file stops the walk there (a monorepo's projects do not
   inherit each other). Outside `$HOME`, only the working directory's `.relix/`.
2. Load **outermost first**: `~/.relix`, then each project level, then
   `--catalog`/`RELIX_CATALOG_PATH` files, then `-i` bindings, then the script. Within a
   directory, `catalog/*.relix` in lexical order (`00-connections.relix`,
   `10-views.relix` — the `/etc/*.d` convention).
3. **A nearer declaration replaces a farther one of the same name** (an overlay, not a
   duplicate-name error). The CLI does this before defining anything: it parses each
   file with `Relix.parse`, keeps the nearest declaration of each name, and defines the
   survivors into one session. A view declared at an outer level then reads whichever
   `Orders` is nearest — which is exactly what lets `reports/.relix` point `Orders` at a
   sample while reusing every view above it.
4. Relative paths inside a catalog file resolve against **that file's directory**, not
   the working directory — otherwise a project catalog breaks the moment you `cd` into
   a subdirectory. (API request **R2**.)
5. Declaring is cheap: nothing is opened or introspected until a query references it,
   so a large catalog costs a parse and an analysis, not a connection.

### 5.3 Inspecting it

```bash
relix catalog files          # every file loaded, in order, with what it declared
relix catalog ls             # name, kind, arity, defining file  (tsv in a pipe)
relix catalog schema Orders  # columns and types
relix catalog where Orders   # the declaration that won, and the ones it shadowed
```

`catalog ls` and `schema` are queries over the engine's own introspection relations
(`relix.relations`, `relix.columns`), run through the same row renderers, so they come
out as TSV/NDJSON in a pipe for free.

### 5.4 Trust

Loading code because of the directory you are standing in is how `direnv` and `.envrc`
became an attack vector: a cloned repository's `.relix/` could declare an `http` source
pointed somewhere, with `${GITHUB_TOKEN}` in its headers. The design borrows direnv's
answer: a catalog directory **outside `~/.relix`** is loaded only once trusted, by
`relix catalog trust [DIR]`, which records a hash of its files in
`~/.relix/trusted`; a changed file needs trusting again. An untrusted catalog is
skipped with one stderr line naming the command to run, and exit 5 if the script needed
a name it would have supplied. `RELIX_TRUST_ALL=1` exists for CI containers.

What is trusted without asking is what the user chose: `~/.relix`, a script named on
the command line, `-e`, stdin, and `--catalog` files. `relix catalog trust --list` and
`--revoke DIR` manage the record. `profiles.json` (any level) is refused, as `ssh`
refuses a loose private key, when it is readable by group or others, since it may hold
credentials; the error names the `chmod` that fixes it. On Windows, where POSIX
permission bits do not apply, this check is skipped.

### 5.5 Placeholders

`${VAR}` resolves, in order, from `-D`, then the active profile (merged across levels, nearest
winning), then — new — the process environment. The process
environment is what twelve-factor deployments and CI secrets use; the trust gate in
§5.4 is what makes reading it safe. Resolution stays lazy (the session's
`placeholders` resolver), so `relix fmt` and `relix bundle` print the placeholder,
never the value.

## 6. Worked pipelines

```bash
# 1. The case in the brief: a piped script, tables from the catalog
cat monthly.relix | relix -P production -o csv > monthly.csv

# 2. One-liners against the catalog
relix -e 'τ amount DESC λ 10 (Orders)'

# 3. relix as a jq companion: JSON in, join, JSON out, jq again
kubectl get pods -o json | jq -c '.items[] | {name: .metadata.name, node: .spec.nodeName}' \
  | relix -i pods=ndjson:- -e 'pods ⋈ Nodes' -o ndjson \
  | jq -r 'select(.zone == "eu-west-1a") | .name'

# 4. A data assertion in CI
relix --fail-rows -e 'σ total < 0 (Invoices)' || { echo "negative invoices"; exit 1; }

# 5. Lint every script in the tree
find . -name '*.relix' -print0 | xargs -0 relix check

# 6. Optimise in place, review the diff
relix optimize q.relix | diff -u q.relix -

```

## 7. Implementation

### 7.1 Engine API use

Everything goes through `com.darkcollective.relix.embed`:

| Need | API |
|---|---|
| session per script | `Relix.builder()…build()`, `baseDirectory`, `scriptLoader`, `placeholders`, `clock`, `provisioners`, `allowUnresolved`, limits, `timeout`, `sandbox`, `remoteFiles` |
| catalog overlay | `Relix.parse(text, source)` → statements, then `define(Statement...)` |
| diagnostics | `validate` → `Diagnostic` |
| queries | `script(text)`, `relation(expression)` |
| rows | `Relation.stream()` (closed on `EPIPE`/`SIGINT`) |
| `explain`, `optimize`, `trace`, `bundle`, `ir`, `provenance` | `explain`/`explainJson`, `optimized().render()` + `rewrites()`, `stream(listener)`, `renderJson`, `model().ir()`, `provenance(semiring[, weight])` |
| `fmt` | `ScriptPrinter.print(Script, Spelling)`, which keeps comments |
| `catalog ls/schema` | `relation("relix.relations")` etc. |
| `doc` | the reference lookup in `relix-docs` (R5): language pages and the installed functions' pages, one index |

A boundary test holds every source set, tests included, to the packages the engine jar's module descriptor exports; the modular build gets that from the compiler, and the test covers what runs on a class path.

### 7.2 Repository layout

One module, `relix-cli`, and no library. The module exports nothing: everything below is
the command's implementation, not an API, and none of it is published to Maven Central.

```
relix-cli        the command:
  com.darkcollective.relix.cli
    Main         picocli entry, exit-code mapping, signal hooks
    command/     one class per command (Run, Check, Explain, Optimize, …)
    catalog/     Discovery (walk, root marker), Overlay (nearest-wins), Trust
    io/          ScriptSource (-e / files / stdin), InputBinding (-i), RowSink
                 (EPIPE-aware), Terminal (isatty, NO_COLOR, pager),
                 FileScriptLoader (the engine's ScriptLoader over files, so
                 `import` resolves relative to the importing file)
    config/      relixrc, profiles.json, ${VAR} placeholders
    render/      OutputFormat and its formatters (table, tsv, csv, ndjson,
                 json, markdown), DocPage (a reference page on a terminal)
    report/      the bundle JSON, provenance output, the optimisation report
    drivers/     driver download and its progress display
```

Where this code already exists, in the earlier command's `relix-console` module in
DarkCollective/relix, it is brought in as a starting point and changed freely; nothing
keeps the two copies in step.
What the command needs and the engine does not offer goes into one of two places: the
command, or, when another embedder would need the same thing, the engine's published API
(§9). The reference-page index is the one case so far that belongs in the engine (R5).

**A guard follows its renderer.** The test that renders every worked example in the
engine's reference pages and diffs the result against the table the page prints comes
here with the table renderer, reading the engine's `manuals` artifact for the pinned
version: its claim is that the manual's pasted output is what a user of this command
sees.

### 7.3 Packaging

- **jlink**, as today, one merged image per platform; Picocli and H2 `forceMerge`d.
- **Startup**: a pipeline pays JVM start once per stage, so the image ships an
  **AppCDS archive** generated at build time (`-XX:ArchiveClassesAtExit` over a
  representative run, launcher `-XX:SharedArchiveFile`). Expect a cold start of
  roughly half; a test asserts the archive is *used* (`-Xshare:on` would fail without it)
  rather than a wall-clock number.
- **Man pages and completions** come from the picocli model (`picocli-codegen`'s
  `ManPageGenerator`, `AutoComplete`), are generated in the build and shipped in the
  archive; the Homebrew formula installs them (`man1.install`,
  `bash_completion.install`, `zsh_completion.install`, `fish_completion.install`), and
  Scoop's manifest registers the PowerShell completion.
- **Where it ships from.** The platform archives are attached to this repository's
  GitHub Releases. The Homebrew tap (`DarkCollective/homebrew-relix`, so that
  `brew tap DarkCollective/relix` resolves) and the Scoop bucket
  (`DarkCollective/scoop-relix`) stay separate repositories, updated by the release
  job; they stay private until the first release and must be public from then on,
  as must the archives they point at.
- Windows: `isatty` via `System.console()`; `SIGPIPE` does not exist and the `EPIPE`
  path covers it; paths in `-i` accept both separators.

### 7.4 Documentation

The site renders a **Command line** section. This repository supplies it, as the engine
supplies its manuals: each release publishes a docs artifact holding one page per
command — generated from the same picocli `CommandSpec` that produces `--help` and the
man pages, so the three cannot drift — and the hand-written pages on the catalog,
input binding, output formats, secrets and pipelines. The site renders a pinned
version of it.

**Every example in those pages runs** in this repository's tests, over a fixture
`.relix/` tree and fixture files, comparing stdout and the exit status — so a page
cannot be published with an example the command does not honour.

### 7.5 Tests

Each command end-to-end through `Main` with captured streams; catalog discovery and
overlay over `@TempDir` trees (including the root marker, `$HOME` ceiling, trust hash);
`EPIPE` with a sink that fails after N rows (assert the stream was closed and rows
pulled ≤ N+1 — a count, not a time); exit-code table as a parameterised test; every
view of the earlier command exercised through its replacement.

## 8. Migration

None needed: the earlier command was never published, and nothing reads its flags or
its `relix-env.json` file.

## 9. Engine API changes (approved; filed on relix-core)

**R1 — Stream-backed sources with the connectors' own parsers.** The command needs to
bind `-i orders=ndjson:-`. `Relix.source(name, schema, Supplier<Stream<Row>>)` exists, but
using it means the CLI writing a second CSV/JSON parser and its own schema inference,
beside the connectors' — two readings of one format. Proposed:

```java
Relix.Builder.input(String name, InputFormat format, Supplier<InputStream> in)   // inferred schema
Relix.Builder.input(String name, InputFormat format, Supplier<InputStream> in, Schema schema)
enum InputFormat { CSV, TSV, JSON, NDJSON }
```

with two properties stated on it: *single-pass* (the planner spools when the relation is
read twice — the CSE spool already exists) and optionally *unbounded* (so
`tail -f | relix` streams and a blocking operator over it is refused at plan time). It
also gives `from csv(...)` schema inference, which today requires a declared schema.
The fallback without R1 is spooling stdin to a temporary file and declaring a `csv`/`json`
source over it: it works, but cannot stream, and still needs a schema for CSV.

**R2 — Relative paths resolve against the declaring file.** `baseDirectory` is per
session; a catalog assembled from several directories needs each declaration's relative
path resolved against the file it was written in (its `SourceLocation` already names
it). `import` already resolves relative to the importing file, so this extends that rule
to source paths. Fallback: the CLI rewrites relative paths to absolute before defining,
which is fragile.

**R3 — Bound query parameters.** `${VAR}` is resolved only in string values of
declarations, so `relix -D id=42 -e 'σ id = ${id} (Orders)'` cannot work, and textual
substitution into a query would be an injection hole. Proposed: a parameter operand in
the grammar (e.g. `$id`), typed at analysis, and `Builder.parameters(Map<String,?>)` /
`Relation.bind(name, value)`, so `relix run -a id=42 order.relix` and `xargs` fan-out
are safe. This is a language change (`area:language`, `core`), so it is the biggest of
the three; the CLI can ship without it.

**R4 (verify, may need nothing)** — that closing a `Stream<Tuple>` mid-flight cancels
an in-progress JDBC statement and an HTTP fetch, not only the iteration. Needed for
§3.6; not yet confirmed.

**R5 — The reference lookup in `relix-docs`.** `relix doc` finds a page by glyph,
keyword or name, across the language pages and the pages of the installed functions, with
a language keyword keeping a shared name (`fix` is the operator; `Fix` the function is
found as a function). Every program that shows the reference needs that same index, so it
belongs beside the pages it indexes rather than in each front end. Filed as
DarkCollective/relix-core#95.

## 10. Decisions

- **Default output in a pipe is `tsv`**; every other format stays one `-o` away.
- **A machine format prints the last query** of a script unless `--query=NAME` names
  another. `-q` is `--quiet`, which silences warnings; the query option has no short form.
- **An interactive session is out of scope.** It is a separate program; `ask` is not
  part of this command.
- **Secrets come from the process environment** (and so from `op run`, `aws-vault exec`,
  `doppler run`, `direnv` and CI secret variables, which all inject them there), or
  from a user-only `profiles.json`. `-D` is for non-secret values and is documented so.
- **Project catalogs are trusted per directory**, direnv-style (§5.4).
- **`-P/--profile` replaces `-e/--env`**; `-e` is the inline expression.
- **R1–R5 are filed on relix-core**: R1 DarkCollective/relix-core#80, R2 #81, R3 #82,
  R4 #83, R5 #95.
- **No library is published from here.** The renderers, reports, script loader, profile
  loading and download progress are internal packages of the command. What the command
  needs goes into the command, or into the engine's published API when another embedder
  would need it too; a second published artifact would be an API promise for code that
  is not one.
- **The tap and bucket stay private until the first release.**
- **No backward compatibility** is needed with the earlier, unpublished command.

