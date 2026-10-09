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
