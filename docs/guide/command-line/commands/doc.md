# relix doc

Shows the reference page for TOPIC, found by glyph, keyword or name (σ, select, selection), through $PAGER on a terminal. With no TOPIC, lists the pages.

## Usage

```text
relix doc [OPTIONS] [TOPIC]
```

## Arguments

| Argument | Description |
|---|---|
| `TOPIC` | A glyph, keyword or name, such as σ, select, join or Len. |

## Options

| Option | Description |
|---|---|
| `--function` | Look TOPIC up among the functions only, or list only them. |
| `--markdown` | Print the page's markdown as it is, not rendered. |
| `--no-pager` | Write the page to standard output even on a terminal. |
| `-o`, `--output=FORMAT` | The rows' format: table, tsv, csv, ndjson, json or markdown (default: $RELIX_OUTPUT, else relixrc's, else table on a terminal, tsv in a pipe). |
| `--no-header` | Leave out the header row of csv and tsv. |
| `--null=STRING` | How NULL is written (default: empty in csv, tsv and markdown; NULL in a table). |
| `--color=WHEN` | Colour a table: auto, always or never (default: auto). auto colours on a terminal unless $NO_COLOR is set. |

It also takes the [global options](relix.md#global-options).

## Exit status

| Status | Meaning |
|---|---|
| 0 | success |
| 2 | no page has that topic |

## Examples

A page is found by its glyph, its keyword or its name:

```shell
relix doc --markdown σ | head -6
```
```
# Name: Selection (σ / SELECT)

# Syntax:
σ <condition> (Relation)
SELECT <condition> (Relation)
```

With no topic, `relix doc` lists the pages, as rows:

```shell
relix doc -o tsv | head -4
```
```
topic	category	title	summary
getting-started	guide	Getting started	Your first script — declare data, name a result, run it
glossary	guide	Glossary	The vocabulary — relation, tuple, bag vs set, materialisation, pushdown
σ	operator	Selection	Filter rows by a condition
```

`--function` lists, or looks up, the functions only:

```shell
relix doc --function -o tsv | grep -i '^round'
```
```
Round	function	Round	Round to nearest
```

On a terminal a page is shown through `$PAGER` (`less` when it is not set); `--no-pager`
writes it straight to standard output.
