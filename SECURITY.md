# Security policy

## Reporting a vulnerability

**Please do not report a security problem in a public issue, discussion or pull
request.**

Report it privately through GitHub instead:
[**Report a vulnerability**](https://github.com/DarkCollective/relix-cli/security/advisories/new)
(the *Security* tab of this repository → *Report a vulnerability*). The report
stays visible only to you and the maintainers until a fix is published.

Useful things to include:

- the version or commit you tested against;
- what an attacker controls — a script, a catalog file in a directory, piped
  input, an environment variable, a remote response — and what they gain;
- steps that reproduce it.

## What to expect

- **Acknowledgement within 5 working days.**
- An initial assessment within 14 days.
- Progress updates on the private advisory until it is resolved.
- A fix released, and the advisory published, crediting you unless you would
  rather not be named. If a fix will take longer than 90 days we will agree a
  disclosure date with you rather than hold it indefinitely.

## Supported versions

There has been no stable release. Until there is, **security fixes are made only
on `main` and shipped in the next release**.

## Scope

`relix` reads files and configuration because of the directory it runs in, and
resolves secrets from the environment. Reports are especially welcome for:

- **catalog discovery and trust** — a catalog file being loaded, or a changed one
  being honoured, without the user having trusted it;
- **credential exposure** — a secret from the environment or a profile file
  reaching stdout, an error message, a plan, a trace, shell history or a host the
  user did not choose;
- **input handling** — malformed scripts, CSV, JSON or NDJSON on stdin causing more
  than a clean error;
- **downloads** — JDBC drivers and connector plugins fetched on request, including
  checksum verification;
- **the installed image** — the launcher, its file permissions, and what it loads.

Vulnerabilities in the engine itself belong with
[relix-core](https://github.com/DarkCollective/relix-core/security). If you are
unsure which project a report belongs to, report it here privately anyway.
