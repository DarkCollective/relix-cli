## Examples

```shell
relix profiles ls -o tsv
```
```
profile	variable	file
dev	WAREHOUSE_PASSWORD	~/.relix/profiles.json
dev	WAREHOUSE_URL	~/shop/.relix/profiles.json
dev	WAREHOUSE_USER	~/shop/.relix/profiles.json
```

The value comes from standard input, or a prompt that does not echo it on a terminal,
never from an argument:

```shell
printf 'jdbc:h2:mem:staging\n' | relix profiles set staging WAREHOUSE_URL
relix profiles get staging WAREHOUSE_URL
```
```
jdbc:h2:mem:staging
```

```shell
relix profiles rm staging
relix profiles ls -o tsv
```
```
profile	variable	file
dev	WAREHOUSE_PASSWORD	~/.relix/profiles.json
dev	WAREHOUSE_URL	~/shop/.relix/profiles.json
dev	WAREHOUSE_USER	~/shop/.relix/profiles.json
```

[Secrets and profiles](../secrets.md#setting-profile-values) covers where values come
from, and what keeps them private.
