# Contributing to the Relix command line

Thank you for considering a contribution. This page is everything you need to
produce a pull request that passes, and it is the only statement of these
conventions you will need — nothing here points anywhere you cannot read.

Everyone taking part in the project is expected to follow the
[code of conduct](CODE_OF_CONDUCT.md).

## Licensing of contributions

This project is licensed under the [Apache License 2.0](LICENSE.txt). **Any contribution
you intentionally submit for inclusion is licensed under the same terms**, as
section 5 of that licence provides, with no additional terms or conditions. There
is no separate contributor licence agreement.

By opening a pull request you confirm that you have the right to submit the work
under that licence — that it is your own, or that you have permission to
contribute it — and that it carries no code under a licence incompatible with
Apache 2.0.

## How a pull request is merged

**Open your pull request against `develop`**, the integration branch. `main` is
the default branch, so choose `develop` as the base when you open it; with the
`gh` CLI that is `gh pr create --base develop`. Once it is reviewed and approved,
a maintainer merges it there.

`develop` reaches `main` in small, frequent batches, and the batch is where the
history is tidied: fixups are squashed into what they fix and messages reworded,
without changing any content. A merged pull request's commits can therefore
arrive on `main` under different hashes. Your authorship is kept throughout.

The issue a pull request fixes is closed when the pull request merges into
`develop`, not when the batch lands. GitHub only acts on closing keywords for
merges into the default branch, so the
[close-issues workflow](.github/workflows/close-issues.yml) does it instead: it
reads `Fixes #N`, `Closes #N` or `Resolves #N` in the pull request's description
and closes each issue with a comment naming the merge. `Part of #N` links an
issue without closing it.

`main` stays the default branch because it is what ships: scheduled workflows and
dependency tracking follow the default branch, and they should see what is
released rather than what is being integrated.

### Promoting a batch (maintainers)

1. Cut a promotion branch from `develop`:
   `git fetch origin && git checkout -b promote/<yyyy-mm-dd> origin/develop`
2. Tidy it — squash fixups, combine related commits, reword messages — with
   `git rebase -i origin/main`, or `git reset --soft origin/main` and recommit.
3. Prove that only the history changed: `git diff origin/develop promote/<yyyy-mm-dd>`
   must print nothing. That is also what makes the batch's CI a test of exactly
   what `develop` held.
4. Open the pull request (`gh pr create --base main --head promote/<yyyy-mm-dd>`)
   and merge it with **Rebase and merge**. A merge commit would add a commit of
   its own, and a squash would collapse the tidied commits into one.
5. Reset `develop` to the new `main`:
   `git fetch origin && git push --force-with-lease origin origin/main:develop`.
   Name `origin/main`, not a local `main`, which may be stale. Without this step
   `develop` still holds the originals of commits `main` now has under other
   hashes, and the next batch carries them twice.

A branch still open at promotion time is based on the old `develop`. Rebase it
onto the new one, keeping only its own commits:
`git rebase --onto origin/develop <old develop tip> <branch>`, then
`git push --force-with-lease`.

A fix that has to go straight to `main` is merged back into `develop` afterwards.

## Before you start

For anything larger than a small fix, open an issue first and describe what you
intend. Agreeing on the shape before the code is written saves everyone a rewrite.

**Security problems are the exception**: never report one in an issue. See
[`SECURITY.md`](SECURITY.md).

**A change the engine would need belongs in
[relix-core](https://github.com/DarkCollective/relix-core).** This project depends on
a published version of the engine (a snapshot on `develop`, a released one in a
release) and reaches it only through its published API; what a command needs from
the engine is added there first, where every embedder can use it, and the version is
then raised here.

## The toolchain

**Java 21, and nothing else.** Please do not change the toolchain, add
`--enable-preview`, or add a `gradle.properties` overriding the toolchain path.

## The build gate

A pull request is merged only when this passes on Linux, macOS and Windows, which is
what CI runs:

```bash
./gradlew clean build
```

`build` runs the tests, Javadoc (where a dangling `{@link}` or `@see` is an error),
JaCoCo coverage (the report is in `build/reports/jacoco/test/html`), and the checks
below. You need no Java 21 installed beforehand: Gradle provisions it.

- **The engine boundary.** The command reaches the engine only through the packages
  the engine's module descriptors export. The compiler enforces this for the main
  source set, which is a module; `EngineBoundaryTest` enforces it for every source
  set, tests included. Run it alone with `./gradlew test --tests '*EngineBoundaryTest'`.
- **The engine pin.** `relixEngineVersion` in `build.gradle` is the one place the
  engine's version is named. On `develop` it is `1.0.0-SNAPSHOT`, resolved from
  Central's snapshot repository, which relix-core publishes to on every push to its
  own `develop`. `checkEnginePin` fails a release build that still pins a snapshot:
  `./gradlew checkEnginePin -PcliVersion=<release version>`.
- **One module, no library.** The module exports nothing (`ModuleShapeTest`) and
  the build publishes no Maven artifact (`checkNoPublication`).
- **The engine's manuals.** The worked examples in the pinned engine's reference,
  and the problem-solving manual's tables, must be what this command's table
  renderer prints. The tests read them from the engine's `manuals` artifact, which
  the build unpacks into `build/engine-docs`. A page that disagrees is fixed in
  relix-core.

### The release archive

The command ships as a platform archive: a jlink image with an AppCDS archive, the man
pages and the completion scripts (design §7.3). CI also runs its tests on each
platform, after `build`:

```bash
./gradlew packageTest
```

It builds the archive (`releaseArchive`, a minute or so), unpacks it with the system's
`tar`, and runs the launcher it holds: a script on standard input over a catalog in a
temporary tree, OPTIMIZE and `relix doc` (each finds a provider that jlink would leave
out unnoticed), `-Xshare:on` (which fails unless the CDS archive is used), and `man` over
every page. The man pages need `man` installed, and are not checked on Windows.

### Building against a local engine checkout

To try an engine change before it is published, build against your relix-core
checkout as a Gradle composite build:

```bash
./gradlew build -PrelixCore=../relix-core
```

The engine's artifacts are then built from that checkout, whatever
`relixEngineVersion` says. In this mode `relix-solver-ojalgo` arrives as its project,
together with the engine-internal module it implements, so the runtime class path
holds some engine classes twice. That does no harm on a class path, but do not judge
a module-path launch by it.

The checkout's build runs under this repository's Gradle, so it needs a relix-core
that builds on Gradle 9. An older one fails while configuring, in a plugin that
Gradle 9 broke (for example `info.solidsoft.pitest`, which reports an unknown
property `baseDir`).

### Cutting a release (maintainers)

1. Pin a released engine in `build.gradle` (`checkEnginePin` refuses a snapshot) and
   promote to `main`.
2. Run the **Release** workflow from the Actions tab with the version. It is a dry run:
   it builds and tests the five platform archives, writes the Homebrew formula and the
   Scoop manifest from [`packaging/`](packaging), checks their checksums against the
   archives, and uploads them as the `release-files` artifact. Nothing is published.
3. Tag `main`: `git tag -a v1.2.0 -m "relix 1.2.0" && git push origin v1.2.0`. The
   workflow runs again, attaches the archives and `checksums.txt` to the GitHub Release,
   and pushes the formula to `DarkCollective/homebrew-relix` and the manifest to
   `DarkCollective/scoop-relix`, with the `HOMEBREW_TAP_PAT` and `SCOOP_BUCKET_PAT`
   secrets. A tag with a suffix (`v1.2.0-rc1`) makes a pre-release and leaves the tap and
   the bucket alone.

The formula and the manifest are written whole from `packaging/` on every release, so a
change to either is made there, not in the tap or the bucket.

## Branches and commits

Work on a branch named with a prefix — `feat/`, `fix/`, `docs/`, `chore/` or
`refactor/` — followed by a short description. Write commit messages that say
what changed and why.

## Tests ride with the change

A change is not complete without tests. A command's behaviour is tested through
the command itself — its standard output, its standard error and its exit status —
because that is the interface a script or a pipeline depends on.

## Documentation rides with the change

A change to a command, an option or an exit status updates its help text, which is
also what the manual page and the site's command reference are generated from.

## Questions

Ask in [Discussions](https://github.com/DarkCollective/relix-cli/discussions).
