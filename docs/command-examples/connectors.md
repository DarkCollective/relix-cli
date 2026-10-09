## Examples

The connectors that ship with `relix`, and any plugins installed in `~/.relix/connectors`:

```shell
relix connectors ls
```
```
── connectors ──────────────────────────────────────────────────────────────────
 type    installed
 ──────  ─────────
 clf     true
 csv     true
 gedcom  true
 log     true
(4 rows)
```

`relix connectors ls --available` adds the connectors that can be installed, which it
fetches from the hosted catalog, and `relix connectors install TYPE` installs one. A
source whose type no installed connector reads — `mongodb`, say — fails with exit status
5, unless `--allow-download` lets the run fetch the connector.
