## Examples

```shell
relix connection ls -o tsv
```
```
name	kind	location	file
warehouse	jdbc	${WAREHOUSE_URL}	~/shop/.relix/catalog/20-warehouse.relix
```

Every option is a flag. A secret is a `${NAME}` placeholder, whose value comes from a
profile or the environment when a query runs:

```shell
relix connection add archive database --url='${ARCHIVE_URL}' --user='${ARCHIVE_USER}' --password='${ARCHIVE_PASSWORD}'
cat .relix/catalog/00-connection-archive.relix
```
```
connection archive from jdbc { password: "${ARCHIVE_PASSWORD}", url: "${ARCHIVE_URL}", user: "${ARCHIVE_USER}" };
```

and a secret given as its value is refused, so it never reaches the catalog:

```shell
relix connection add scratch database --url=jdbc:h2:mem:scratch --password=hunter2 2>&1; echo "exit $?"
```
```
relix: --password: a secret is written as a ${NAME} placeholder, never its value, which would stay in the catalog, your shell's history and ps; give --password='${NAME}' and set NAME with relix profiles set
exit 2
```

`test` opens and closes each connection under the profile:

```shell
relix connection test -P dev -o tsv; echo "exit $?"
```
```
name	status	problem
archive	failed	${ARCHIVE_PASSWORD} has no value: set it in a profile (-P), the environment or -D
warehouse	ok
exit 5
```

```shell
relix connection rm archive
relix connection test -P dev -o tsv
```
```
name	status	problem
warehouse	ok
```

[The catalog](../catalog.md#declaring-sources-and-connections) covers where declarations
go, and the trust a project keeps across them.
