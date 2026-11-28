# Archived 6.x compatibility history

These files are a frozen, read-only snapshot of the 6.x deprecation registry and removal records
before the 7.0.0 baseline reset. They are historical evidence, not an active removal ledger, and
`DeprecationRegistryGenerator` never reads or rewrites this directory.

- `deprecations.json`: 131 historical entries (124 REMOVED, 5 DEPRECATED, 2 ANNOUNCED).
- `DEPRECATIONS.md`: the corresponding historical rendered registry.
- `records/6.3.0.md`: the 6.3.0 behavioural removal records, with links to the original compatibility guide.

The original prose and version-specific links are retained unchanged. Links from the removal
records to the compatibility guide point to [the original anchors on the `6.x` branch](https://github.com/UltiKits/UltiTools-Reborn/blob/6.x/COMPATIBILITY.md), not the 7.0 guide on the default branch.
The active `compatibility/deprecations.json` and `compatibility/DEPRECATIONS.md` are regenerated
from current source declarations during the 7.0.0 development/release cycle. Existing annotations
retain their original metadata; archiving history does not delete or authorize deleting an API.

7.0.0 established a new public API baseline without comparing against 6.x. After the artifact
became available from Maven Central, the move to `7.1.0-SNAPSHOT` set `japicmp.baseline.version`
to `7.0.0` and restored the comparison with `japicmp.skip=false` (PR #1019). The comparison is
enabled for subsequent development and release builds.
