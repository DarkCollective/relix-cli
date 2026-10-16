## Examples

```shell
relix init ~/stall --root -P dev -o csv
find ~/stall/.relix | sort
cat ~/stall/.relix/relixrc
```
```
~/stall/.relix
~/stall/.relix/.gitignore
~/stall/.relix/catalog
~/stall/.relix/profiles.json
~/stall/.relix/relixrc
~/stall/.relix/root
output = csv
profile = dev
```

A directory that has a `.relix/` already is refused, and nothing in it changes:

```shell
relix init ~/stall 2>&1; echo "exit $?"
```
```
relix: ~/stall/.relix already exists; relix init makes a new project and never changes one
exit 2
```

[The catalog](../catalog.md#starting-a-project) covers what each file is for.
