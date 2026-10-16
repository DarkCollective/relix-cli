## Examples

```shell
relix source ls -o tsv
```
```
name	kind	location	file
Customers	csv	../../data/customers.csv	~/shop/.relix/catalog/00-sources.relix
Orders	csv	../../data/orders.csv	~/shop/.relix/catalog/00-sources.relix
Stock	warehouse	STOCK	~/shop/.relix/catalog/20-warehouse.relix
```

`add` writes one declaration to a file of its own. A relative `LOCATION` is written
relative to that file, so it still names the same file from anywhere in the project, and
`--infer-schema` reads the file for its columns:

```shell
relix source add People csv data/customers.csv --header --infer-schema
cat .relix/catalog/00-source-People.relix
```
```
source People from csv("../../data/customers.csv") { header: true, schema: { customer_id: number, name: string, city: string } };
```

`test` reads one row of each source under the profile, and checks the schema it declares
against what it read:

```shell
relix source test -P dev -o tsv
```
```
name	status	problem
Customers	ok
Orders	ok
People	ok
Stock	ok
```

A source another declaration reads is not removed without `--force`:

```shell
relix source rm Orders 2>&1; echo "exit $?"
```
```
relix: Orders is read by
  Completed (~/shop/.relix/catalog/10-views.relix:1)
remove those first, or give --force
exit 2
```

and one declared beside others in a file written by hand is reported, not changed:

```shell
relix source edit Customers --header=false 2>&1; echo "exit $?"
```
```
relix: ~/shop/.relix/catalog/00-sources.relix:2 declares Customers beside other declarations, which relix source does not edit for you; relix source edit Customers --editor
exit 2
```

```shell
relix source rm People
relix source ls -o tsv
```
```
name	kind	location	file
Customers	csv	../../data/customers.csv	~/shop/.relix/catalog/00-sources.relix
Orders	csv	../../data/orders.csv	~/shop/.relix/catalog/00-sources.relix
Stock	warehouse	STOCK	~/shop/.relix/catalog/20-warehouse.relix
```

[The catalog](../catalog.md#declaring-sources-and-connections) covers where declarations
go, and the trust a project keeps across them.
