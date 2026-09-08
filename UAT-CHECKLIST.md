# UltiTools-API — UAT Checklist

This document is the executable companion to `FEATURES.md`: one row per feature stating the
steps to exercise it and the observable truth that proves it works. It is an internal reference
for real-machine verification, not user-facing documentation.

> Batches are dispatched at 60 rows or fewer, and a batch never spans two repositories. There are
> exactly two legitimate exits to `human-uat-pending`: a row needing the pixel layer while the
> real-client harness is not ready, and a row needing personal credentials. Every other row must
> reach `pass`, `fail`, or `blocked`.

## Conventions

- **Columns:** `ID`, `Preconditions`, `Steps`, `Expected`, `Layer`, `Covers`.
- **ID:** cites its `FEATURES.md` ID verbatim. A negative case suffixes the checklist ID only,
  as `.neg-<slug>` — a negative case still tests the same feature, so the base ID is unchanged.
- **Layer**, copied verbatim from Laojun's own `ultitools-real-client-uat` skill so no
  translation step exists at dispatch time: `protocol`, `java-client`, `os-input`, `pixel`,
  `server`, `human`.
- **Expected** must name an observable truth — an exact chat line, a log line, a database row,
  an inventory slot — and never the words "it works".
- **Covers** back-references a Phase 9 GUI-excluded class name; left blank when no such class
  applies.
- A row whose Preconditions name a prior row must appear after that row in file order.
- **Config-per-file rule (D-06):** one checklist row per `@ConfigEntity`-annotated class or per
  shipped yml file, never one row per key.
- This repository's shipped default is `language: "zh"` in `config.yml`. Every row below whose
  Expected quotes a literal in-game or console line therefore carries the precondition
  `language: en` set in `plugins/UltiTools/config.yml`, so the observed line matches this
  document's English-only text exactly, character for character.

## /ul — framework administration

| ID | Preconditions | Steps | Expected | Layer | Covers |
|---|---|---|---|---|---|
| ultitools.ul.help | `language: en` in config.yml | Run `/ul help` (console or op player) | Chat/console output is exactly `=== UltiTools Commands ===` followed by `/ul reload Reload all modules`, `/ul reload <name> Reload a specific module`, `/ul list Check loaded module list`, `================` | server | |
| ultitools.ul.list | At least one module JAR present in `plugins/UltiTools/plugins/` | Run `/ul list` | One line per loaded module reading `<module name> <version>`, matching `UltiToolsPlugin#getPluginName` and `#getVersion` for every module actually present | server | |
| ultitools.ul.reload | Same precondition as ultitools.ul.list (appears after it) | Run `/ul reload` | Every module's `reloadSelf()` runs with no exception in the console; a module list taken with `/ul list` immediately after is unchanged | server | |
| ultitools.ul.reload-module | `language: en` in config.yml; a loaded module named `<name>` (positive case: an existing module; negative case below: a name that does not exist) | Run `/ul reload <name>`. Negative case: run `/ul reload does-not-exist` | Positive: chat/console line reads `Module <name> has been reloaded` (green). Negative: chat/console line reads `does-not-exist is not a loaded UltiTools module. Use /ul list to see loaded modules` (red) | server | |
