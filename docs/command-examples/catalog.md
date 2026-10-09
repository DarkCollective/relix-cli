## Examples

```shell
relix catalog ls -o tsv
```
```
name	kind	arity	file
BigSpenders	QR	2	~/shop/.relix/catalog/10-views.relix
Completed	QR	5	~/shop/.relix/catalog/10-views.relix
Customers	SRC	3	~/shop/.relix/catalog/00-sources.relix
Orders	SRC	5	~/shop/.relix/catalog/00-sources.relix
Regions	INL	2	~/.relix/catalog/00-regions.relix
Spend	QR	2	~/shop/.relix/catalog/10-views.relix
Stock	SRC	2	~/shop/.relix/catalog/20-warehouse.relix
```

```shell
relix catalog schema Customers -o tsv
```
```
column	type
customer_id	N
name	S
city	S
```

```shell
relix catalog where Orders -o tsv
```
```
file	line	kind	state
~/shop/.relix/catalog/00-sources.relix	7	source	wins
```

```shell
relix catalog trust --list -o tsv
```
```
directory	state
~/shop	trusted
~/shop/reports	trusted
```

[The catalog](../catalog.md) covers discovery, the overlay and trust.
