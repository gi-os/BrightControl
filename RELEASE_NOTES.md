## BrightControl v3.75 — the check I added was condemning good pairings

Reports #116 and #119 both say this, and both are wrong:

```
Could not run anything after pairing and connecting.
the daemon accepted both the pairing and the connection and then refused a shell
stream, which means the key it accepted is not one it trusts.
```

v3.69 added that verification for a good reason — "paired and connected" was being treated as success
while every command failed afterwards. But it asked **once**, immediately after connecting. And the
one thing this evening established beyond doubt is that *the first command on a freshly connected
socket is the one that dies*: it is why `ensureAlive` probes three times with gaps, and why GRANT ALL
stopped refusing to run on the word of a probe.

So a working pairing was being declared untrusted, its grants abandoned, and its owner told to delete
it. A diagnostic that manufactures the failure it was written to detect is worse than no diagnostic.

**It is patient now:** four asks a quarter-second apart, then one reconnect and a final ask. Only
after all of that is a refusal a fact about the key rather than about the moment — and the trail says
which try answered, so a connection that needs three goes is visible rather than silently fine.

The plumbing's wording is fixed the same way. It now says a shell stream was refused **twice over**,
suggests forgetting the pairing rather than instructing it, and admits that a daemon which has just
come up can refuse a first command too.

If you were told to forget a pairing today, that advice may well have been wrong. Try GRANT ALL on
this build before throwing anything away.
