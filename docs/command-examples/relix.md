## Examples

With no command, `relix` runs scripts, as [`relix run`](run.md) does:

```shell
relix -e 'PROJECT name, city (Customers)' -o tsv
```
```
name	city
Alice	London
Bob	Leeds
Carol	London
Dan	York
```

Global options go before or after the command's name:

```shell
relix -C reports catalog ls -o tsv --no-header
```
```
BigSpenders	QR	2	~/shop/.relix/catalog/10-views.relix
Completed	QR	5	~/shop/.relix/catalog/10-views.relix
Customers	SRC	3	~/shop/.relix/catalog/00-sources.relix
Orders	SRC	5	~/shop/reports/.relix/catalog/10-sample.relix
Regions	INL	2	~/.relix/catalog/00-regions.relix
Spend	QR	2	~/shop/.relix/catalog/10-views.relix
Stock	SRC	2	~/shop/.relix/catalog/20-warehouse.relix
```

## Environment

| Variable | Effect |
|---|---|
| `RELIX_OUTPUT` | the output format when `-o` is not given |
| `RELIX_PROFILE` | the profile when `-P` is not given |
| `RELIX_NOW` | the instant `NOW()` returns when `--now` is not given |
| `RELIX_CATALOG_PATH` | catalog files to load, separated as in `PATH`, before the `--catalog` files |
| `RELIX_TRUST_ALL` | `1` or `true` trusts every project's `.relix/` directory |
| `RELIX_DRIVERS` | the directory JDBC drivers are installed in and loaded from, instead of `~/.relix/drivers` |
| `NO_COLOR` | when set, a table on a terminal is not coloured unless `--color=always` |
| `PAGER` | the program [`relix doc`](doc.md) shows a page through on a terminal; `cat` or empty shows it directly |

A `${NAME}` placeholder in a declaration may also be resolved from the environment; see
[Secrets and profiles](../secrets.md).

## Files

| Path | What it holds |
|---|---|
| `~/.relix/catalog/*.relix` | your own catalog, which every directory under your home sees |
| `~/.relix/profiles.json` | your own profiles |
| `~/.relix/trusted` | the projects you have trusted, with a hash of each one's files |
| `~/.relix/drivers/` | installed JDBC drivers |
| `~/.relix/connectors/` | installed connector plugins |
| `~/.relix/cache/files/` | files fetched over `https` by a file connection |
| `.relix/catalog/*.relix`, `.relix/relixrc`, `.relix/profiles.json`, `.relix/root` | a project's catalog, defaults, profiles, and the marker that ends the walk up; see [The catalog](../catalog.md) |
