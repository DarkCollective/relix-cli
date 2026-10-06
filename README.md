# relix — the command line for Relix

`relix` runs [Relix](https://relix.darkcollective.com) relational-algebra scripts
from the shell, as one stage of an ordinary Unix pipeline:

```bash
cat monthly.relix | relix -o csv > monthly.csv
curl -s https://api.example.com/orders | jq -c '.items[]' \
  | relix -i orders=ndjson:- -e 'orders ⋈ Customers' -o ndjson | jq .
```

It is a front end to the Relix engine,
[relix-core](https://github.com/DarkCollective/relix-core), and reaches it only through
the engine's published API.

## Status

Under development. There is no release yet, and the command line described above is
the design being built, not a shipped interface.

## Building

Requires Java 21.

## Licence

[Apache License 2.0](LICENSE.txt). See [CONTRIBUTING.md](CONTRIBUTING.md) to take
part, and [SECURITY.md](SECURITY.md) to report a vulnerability.
