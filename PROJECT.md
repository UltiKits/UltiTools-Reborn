# PROJECT: UltiTools-Reborn

> Control card for coding agents working in this repository.
> Scope: **this repository only.** Every number here is a snapshot — verify against `git`, `pom.xml`,
> and the workflow files before relying on it.

## Identity

- **What it is:** `UltiTools-API` — an annotation-driven plugin framework for Minecraft **Paper** servers.
  It ships its own IoC container, ByteBuddy-based AOP, an ORM over MySQL/SQLite/JSON, a declarative reactive
  GUI layer, a module EventBus, and a WebSocket client for remote server management.
- **Maven coordinates:** `com.ultikits:UltiTools-API` — read the current version from `pom.xml`;
  `main` and `alpha` do not always carry the same one. Produces a shaded JAR via maven-shade-plugin.
- **No Spring.** The IoC container is hand-written (`context/SimpleContainer`); `pom.xml` declares no Spring
  dependency at all. `@Service`, `@Autowired`, `@Configuration` and friends are **this framework's own
  annotations** under `com.ultikits.ultitools.annotations` — importing Spring's will not resolve against the
  published artifact.
- **Three version numbers, all different, all intentional:**

  | Where | Value | Meaning |
  |---|---|---|
  | `pom.xml` `<java.version>` | `1.8` | `-source` / `-target` bytecode level |
  | CI toolchain + `paper-api` | JDK 21 · Paper `1.21.11-R0.1-SNAPSHOT` | build toolchain and compile-time API |
  | `plugin.yml` `api-version` | `1.19` | Bukkit API level |

  The pom uses `-source`/`-target`, **not** `--release`, and the codebase relies on that gap (for example
  `@Deprecated(since=…, forRemoval=…)` is a Java 9+ construct). Adding `<release>8</release>` breaks the
  build. Practically: avoid `var` and records, but do not assume a real JDK 8 compiles this.
- **Runtime dependency model:** Paper is the only declared server API (`paper-api`, `provided` scope).
  Paper auto-downloads the `libraries:` entries declared in `src/main/resources/plugin.yml`; other
  dependencies, including XSeries, are also `provided` and are not bundled unless explicitly shaded.
  Plain Spigot is not supported. The server floor is Paper 1.19.2 build 163; use Java 17 below Paper
  1.20.5 and Java 21 from Paper 1.20.5 (see `COMPATIBILITY.md`).
- **Downstream:** consumer plugin modules declare `com.ultikits:UltiTools-API` at `provided` scope. Bumping
  the version here affects every consumer that hard-codes it rather than inheriting a property.

## Branching Rule

**Two long-lived branches. Never commit to either directly.**

- **`main` is the GitHub default branch** (`origin/HEAD` → `origin/main`). Scheduled workflow triggers
  fire only for the copy of a workflow that lives here.
- **`alpha` is the integration branch**, not the default. Day-to-day work targets `alpha`.
- **A pull request into `main` may only come from `alpha`.** `pr-source-guard.yml` checks that source
  branch; whether it is required is repository configuration, not guaranteed by the workflow file.
  Everything else integrates into `alpha` first. Target `alpha` unless told otherwise.
- **Before editing, run `git branch --show-current`.** If it returns `main` or `alpha`, branch off first.
- **Enforcement is configured through repository rulesets and branch protection, and it changes.** Do not
  assume a branch is locked just because this file says so — check the live state:

  ```bash
  gh api repos/UltiKits/UltiTools-Reborn/rulesets \
    --jq '.[] | "\(.name)\t\(.enforcement)"'
  gh api repos/UltiKits/UltiTools-Reborn/branches/main/protection \
    --jq '.required_pull_request_reviews'
  ```
- **Branch naming:** `feature/<short-description>`, `fix/<short-description>`, `chore/<short-description>`,
  `docs/<short-description>`.
- Measure the current divergence instead of trusting a number written here:

  ```bash
  MB=$(git merge-base origin/main origin/alpha)
  git rev-list --count $MB..origin/main     # commits main is ahead
  git rev-list --count $MB..origin/alpha    # commits alpha is ahead
  ```

## Build / Test / Verify

All commands run from the repository root.

| Goal | Command |
|---|---|
| Build shaded JAR | `mvn clean package` → `target/UltiTools-API-<version>.jar` |
| Full verification | `mvn clean verify` — package plus Java 8 API, dependency, japicmp and deprecation-registry checks |
| Test | `mvn test` |
| Single test class | `mvn test -Dtest=ClassName` |
| Include isolated tests | `mvn test -DexcludedGroups=` |
| Javadoc | `mvn javadoc:javadoc` → `target/site/apidocs/` |

- `pom.xml` sets `<excludedGroups>isolated</excludedGroups>`, so `@Tag("isolated")` tests are skipped by
  default. That property name is also Surefire's own user property, which is why passing it empty re-enables
  them.
- JaCoCo runs automatically during `mvn test` → `target/site/jacoco/index.html`. **No coverage threshold is
  enforced** — the `check` rule exists but is commented out.
- `mvn package` does **not** run `ultitools-maven-plugin`: its POM declaration has no bound executions.
  To request a local server copy, invoke the goal explicitly:
  `mvn package ultitools:install -Dultitools.deploy.folder=/path/to/server/plugins`.
  Without an override, that explicit goal uses `target/deploy/`. **Never hard-code a local path into
  `pom.xml`** — see "Public repository hygiene".

## CI

All jobs run on JDK 21 (temurin). **Workflow files change more often than this card does — read
`.github/workflows/` rather than trusting the table below whenever behaviour surprises you.**

| Workflow | Trigger | What it does |
|---|---|---|
| `maven-ci.yml` | `push` on every branch, **and** `pull_request` into `main` or `alpha` | `mvn -B clean verify`, release-tooling regression suites and JaCoCo reporting; Codacy coverage upload on pushes. An isolated-test job runs separately. A third job builds a SNAPSHOT artifact only on `push` to `alpha`. |
| `pr-source-guard.yml` | `pull_request` into `main` | Rejects a pull request whose head is not this repository's `alpha`. Required-check configuration is maintained separately. |
| `publish-packages.yml` | `release: [published]` — a published GitHub Release, **not** a tag push | Validates the tag and retained manifest, then publishes the already accepted assets without rebuilding. GitHub Packages uses lowercase `ultitools-api`; the Maven Central job runs only if `PUBLISH_TO_CENTRAL == 'true'`. |
| `snapshot-publish.yml` | `push` to `alpha`, `workflow_dispatch` | Publishes well-formed `-SNAPSHOT` versions to Central. A formal version such as `6.3.0` succeeds without publishing; malformed versions fail. |
| `phase-closeout.yml` | `pull_request_target: [closed]` into `alpha` or `main` | On a merged PR, reads issue declarations and closes them. It uses the default branch's workflow and script, so an `alpha`-only change takes effect only after promotion to `main`. |
| `gpg-key-expiry.yml` | monthly `schedule`, `workflow_dispatch`, and pull requests touching its own script | Warns before the Maven Central signing key expires. Uses the public key only. `schedule` fires only for workflows living on the **default** branch. |

Maven Central publishing goes through `central-publishing-maven-plugin` with server id `ultikits-sonatype`.

Releases require an explicit maintainer instruction. `.github/scripts/release.sh --prepare <new-dir>`
checks the checkout, builds once and retains the exact candidate files and manifest without publishing.
After independent acceptance and the maintainer's decision, `--publish-approved <manifest>` requires
`--authority-receipt <file>` and `--uat-receipt <file>` and publishes those retained bytes without
rebuilding. `publish-packages.yml` consumes the attached assets on `release: published`; it does not
compile a replacement artifact. There is no `workflow_dispatch` release workflow.

The release path is shared with every downstream module repository — **get maintainer sign-off before
changing `publish-packages.yml` or `.github/scripts/release.sh`.** Verification-only changes to
`maven-ci.yml` are made routinely.

## Scope Boundaries

- **Do NOT modify:** `target/` (build output), `.flattened-pom.xml` (generated by flatten-maven-plugin), IDE
  artifacts (`.idea/`, `*.iml`, `.classpath`, `.project`, `.settings/`), generated sources.
- **Do NOT commit:** secrets, `.env*`, `data.json`, local config overrides, OS junk (`.DS_Store`,
  `Thumbs.db`).
- **Leave unrelated dirty entries alone.** Do not stage, restore, stash, or overwrite working-tree changes
  you did not make. Run `git status --porcelain` and check what is actually yours first.

### Credential hazard: `.ultikits/credentials.json`

As of 6.3.0, `<server root>/.ultikits/credentials.json` holds the server UUID and UltiCloud access
and refresh tokens. `utils/CredentialStore` is its sole owner; it migrates the pre-6.3.0
`plugins/UltiTools/data.json` without overwriting a current identity. Never read or commit live
credential files. Both `.ultikits/` and the legacy `data.json` are gitignored.

Before staging anything, verify exclusions using synthetic paths, not the credential contents:

```bash
git ls-files -- .ultikits/credentials.json data.json  # expected: no output
git check-ignore -v .ultikits/credentials.json data.json
```

If an older branch tracks a credential file, stop staging it and remove it from the index before
committing; never publish its contents.

### Public repository hygiene

- No absolute developer paths (`/home/<user>/`, `/Users/<user>/`, `C:\Users\`) in tracked files —
  parameterize them as Maven properties, or write `/path/to/...` in documentation.
- No real server IDs, tokens, or panel identifiers in `config-example.yml` or documentation — use
  `<your-server-id>`-style placeholders.
- CI credentials come from `${{ secrets.* }}` only, and must never be echoed into job logs.
- **Line endings vary by file:** `README.md`, `.gitignore` and `pom.xml` use CRLF, while this card and
  `COMPATIBILITY.md` use LF. Check each file and preserve its convention; disable newline translation
  in scripted edits to avoid turning a small correction into a whole-file rewrite.

## Pitfalls

1. **`@Service` must be `com.ultikits.ultitools.annotations.Service`** — not Spring's. There is no Spring on
   the classpath.
2. **`UltiToolsPlugin` is not a Bukkit `Plugin`.** For scheduler tasks use
   `Bukkit.getPluginManager().getPlugin("UltiTools")`; do not cast it to `JavaPlugin`.
3. **`AbstractCommandExecutor` and `CommandManager.registerAll(plugin, packageName)` are removed in 6.3.0.**
   Extend `abstracts.command.BaseCommandExecutor` and implement `handleHelp(CommandSender)`. Use
   `CommandManager.register(plugin, Class)` for explicit registration, or `registerAll(plugin)` for
   commands resolved as `CommandExecutor` beans from the module's container.
4. **Bukkit thread safety.** Anything reaching Bukkit from an async context must go through
   `Bukkit.getScheduler().runTask()`, or Paper's AsyncCatcher rejects it.
5. **AOP uses ByteBuddy subclass proxies only.** `final` classes and methods cannot be proxied.
   Self-invocation, a call a bean makes to its own method (`this.method()`), is intercepted like an
   external call, because the proxy is the bean itself, so an inner `REQUIRES_NEW` call opens its own
   transaction.
6. **Event listeners need the framework's `@EventListener`**, not just Bukkit's `Listener` interface.

## Related Docs

Reading order for an agent starting work here:

1. This `PROJECT.md`
2. `README.md` — quick start, dependency snippet, module scaffolding
3. `pom.xml` — published coordinates, shaded JAR layout, plugin configuration
4. Developer documentation — [dev.ultikits.com](https://dev.ultikits.com)
5. Issues and discussion — [GitHub Issues](https://github.com/UltiKits/UltiTools-Reborn/issues)

## Last Verified

- **Date:** 2026-10-21, against the 6.3.0 source.
- **How:** every claim re-checked against `git`, `pom.xml`, `plugin.yml`, `.github/workflows/`, and the
  source tree. Nothing was carried over on trust.
- **What this card is for:** the things that do not change — the annotation namespace, the base-class
  traps, the credential hazard, the line-ending convention. Anything that is a snapshot of current state
  (branch divergence, dependency versions, which jobs a workflow happens to have today) belongs in the
  file that owns it, and this card points you at that file instead of copying the number.
- **Next verification due:** after the next release, or whenever `.github/workflows/` changes shape.
