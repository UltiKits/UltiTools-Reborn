# Archived 6.x compatibility history

These files are a frozen, read-only snapshot of the 6.x deprecation registry and removal records
before the 7.0.0 baseline reset. They are historical evidence, not an active removal ledger, and
`DeprecationRegistryGenerator` never reads or rewrites this directory.

- `deprecations.json`: 131 historical entries (124 REMOVED, 5 DEPRECATED, 2 ANNOUNCED).
- `DEPRECATIONS.md`: the corresponding historical rendered registry.
- `records/6.3.0.md`: the 6.3.0 behavioural removal records, with relative links adjusted for the archive location.

The original prose and version-specific links are retained unchanged. Relative links from the
removal records to the repository-root `COMPATIBILITY.md` use `../../../../COMPATIBILITY.md`;
only these paths changed when the records moved into the archive.
The active `compatibility/deprecations.json` and `compatibility/DEPRECATIONS.md` are regenerated
from current source declarations during the 7.0.0 development/release cycle. Existing annotations
retain their original metadata; archiving history does not delete or authorize deleting an API.

7.0.0 is a new public API baseline, with no comparison against 6.x. After its artifact is available
from Maven Central, set `japicmp.baseline.version` to `7.0.0` and `japicmp.skip` to `false` before
advancing the project version. All later development and release builds must compare again.
