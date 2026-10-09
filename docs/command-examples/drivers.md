## Examples

```shell
relix drivers ls
```
```
── drivers ─────────────────────────────────────────────────────────────────────
 name        scheme            installed
 ──────────  ────────────────  ─────────
 postgresql  jdbc:postgresql:  false
 mysql       jdbc:mysql:       false
(2 rows)
```

`relix drivers install NAME` downloads one of those drivers, checks its checksum, and
installs it in `~/.relix/drivers`, or in `$RELIX_DRIVERS` when that is set. A run that
needs a driver that is not installed fails with exit status 5, unless `--allow-download`
lets it fetch the driver itself. The H2 driver is part of
`relix`, and needs no installing. A driver `relix drivers ls` does not list is installed
by copying its jar into the driver directory.
