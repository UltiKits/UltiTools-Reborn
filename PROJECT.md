# PROJECT: UltiTools-Reborn

> Control card for coding agents working in this repository.
> Scope: **this repository only.** Every number here is a snapshot — verify against `git`, `pom.xml`,
> and the workflow files before relying on it.

## Identity

- **What it is:** `UltiTools-API` — an annotation-driven plugin framework for Minecraft **Paper** servers.
  It ships its own IoC container, CGLIB-based AOP, an ORM over MySQL/SQLite/JSON, a declarative reactive
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
- **Runtime dependency model:** only `paper-api`, at `provided` scope. Paper auto-downloads the `libraries:`
  entries declared in `plugin.yml`. Adventure `Component` is used throughout, so plain Spigot is not a
  supported build target.
- **Downstream:** consumer plugin modules declare `com.ultikits:UltiTools-API` at `provided` scope. Bumping
  the version here affects every consumer that hard-codes it rather than inheriting a property.

## Branching Rule

**Two long-lived branches. Never commit to either directly.**

- **`main` is the GitHub default branch** (`origin/HEAD` → `origin/main`). It is also the only branch
  `maven-ci.yml` accepts pull requests into.
- **`alpha` is a parallel long-lived branch**, not the default. The two have diverged, and the same change is
  sometimes shipped to both as separate PRs (for example #166 → `main`, #167 → `alpha`).
- **Ask which branch to target before starting.** Release-bound work goes to `main`; work coordinated with
  the downstream module rollout has historically gone to `alpha`. Do not guess.
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
| Test | `mvn test` |
| Single test class | `mvn test -Dtest=ClassName` |
| Include isolated tests | `mvn test -DexcludedGroups=` |
| Javadoc | `mvn javadoc:javadoc` → `target/site/apidocs/` |

- `pom.xml` sets `<excludedGroups>isolated</excludedGroups>`, so `@Tag("isolated")` tests are skipped by
  default. That property name is also Surefire's own user property, which is why passing it empty re-enables
  them.
- JaCoCo runs automatically during `mvn test` → `target/site/jacoco/index.html`. **No coverage threshold is
  enforced** — the `check` rule exists but is commented out.
- `mvn package` also runs `ultitools-maven-plugin`, which copies the JAR to `${ultitools.deploy.folder}`
  (default `target/deploy/`). Override it on the command line to deploy to a local test server:
  `mvn package -Dultitools.deploy.folder=/path/to/server/plugins`. **Never hard-code a local path into
  `pom.xml`** — see "Public repository hygiene".

## CI

All jobs run on JDK 21 (temurin). Re-read the workflow files rather than trusting this table if behaviour
surprises you.

| Workflow | Trigger | What it does |
|---|---|---|
| `maven-ci.yml` | `push` on every branch, **and** `pull_request` into `main` only | `mvn -B test`, then `mvn -B package` |
| `publish-packages.yml` | `release: [published]` — a published GitHub Release, **not** a tag push | Sets the version from the release tag, builds skipping tests and GPG, publishes to GitHub Packages under the lowercase id `ultitools-api`. A second `publish-central` job targets Maven Central but is gated behind the repository variable `PUBLISH_TO_CENTRAL == 'true'` — it does not run unless that variable is set. |
| `release.yml` | `workflow_dispatch` only, with inputs `release_type`, `release_notes`, `dry_run` | Rejects versions containing `SNAPSHOT`, runs tests, builds package + javadoc, creates the GitHub Release, uploads javadoc over FTP |

Maven Central publishing goes through `central-publishing-maven-plugin` with server id `ultikits-sonatype`.

The release path is shared with every downstream module repository — **get maintainer sign-off before
changing `publish-packages.yml` or `release.yml`.** Verification-only changes to `maven-ci.yml` are made
routinely.

## Scope Boundaries

- **Do NOT modify:** `target/` (build output), `.flattened-pom.xml` (generated by flatten-maven-plugin), IDE
  artifacts (`.idea/`, `*.iml`, `.classpath`, `.project`, `.settings/`), generated sources.
- **Do NOT commit:** secrets, `.env*`, `data.json`, local config overrides, OS junk (`.DS_Store`,
  `Thumbs.db`).
- **Leave unrelated dirty entries alone.** Do not stage, restore, stash, or overwrite working-tree changes
  you did not make. Run `git status --porcelain` and check what is actually yours first.

### ⚠ Credential hazard: `data.json`

`data.json` in the repository root holds the server UUID and — once `ulticloud login` has run against this
working copy — the UltiCloud `access_token` and `refresh_token`. Running the framework or a build with the
repository root as the working directory creates it. **This is a public repository.**

`main` and `alpha` both gitignore and untrack it. **Branches cut before PRs #166/#167 do not.** Before
staging anything, run both:

```bash
git ls-files --error-unmatch data.json   # expected to FAIL: "did not match any file"
git check-ignore -v data.json            # expected to print a .gitignore line
```

If it is tracked, `git rm --cached data.json` and rebase onto the current default branch before committing.

### Public repository hygiene

- No absolute developer paths (`/home/<user>/`, `/Users/<user>/`, `C:\Users\`) in tracked files —
  parameterize them as Maven properties, or write `/path/to/...` in documentation.
- No real server IDs, tokens, or panel identifiers in `config-example.yml` or documentation — use
  `<your-server-id>`-style placeholders.
- CI credentials come from `${{ secrets.* }}` only, and must never be echoed into job logs.
- **Tracked text files use CRLF line endings** (`.gitignore`, `README.md`, `pom.xml` at minimum). Scripted
  edits that normalize to LF turn a three-line change into a whole-file rewrite — disable newline
  translation when editing programmatically.

## Pitfalls

1. **`@Service` must be `com.ultikits.ultitools.annotations.Service`** — not Spring's. There is no Spring on
   the classpath.
2. **`UltiToolsPlugin` is not a Bukkit `Plugin`.** For scheduler tasks use
   `Bukkit.getPluginManager().getPlugin("UltiTools")`; do not cast it to `JavaPlugin`.
3. **`BaseCommandExecutor` is not auto-registered.** `CommandManager`'s package scan casts discovered classes
   to the legacy `AbstractCommandExecutor`, so a `BaseCommandExecutor` picked up by `@CmdExecutor` scanning
   throws `ClassCastException` — which the surrounding catch swallows. Register it explicitly via
   `CommandManager.register(plugin, Class)`.
4. **Bukkit thread safety.** Anything reaching Bukkit from an async context must go through
   `Bukkit.getScheduler().runTask()`, or Paper's AsyncCatcher rejects it.
5. **AOP uses CGLIB subclass proxies only.** `final` classes and methods cannot be proxied, and
   self-invocation (`this.method()`) bypasses the interceptor chain.
6. **Event listeners need the framework's `@EventListener`**, not just Bukkit's `Listener` interface.

## Related Docs

Reading order for an agent starting work here:

1. This `PROJECT.md`
2. `README.md` — quick start, dependency snippet, module scaffolding
3. `pom.xml` — published coordinates, shaded JAR layout, plugin configuration
4. Developer documentation — [dev.ultikits.com](https://dev.ultikits.com)
5. Issues and discussion — [GitHub Issues](https://github.com/UltiKits/UltiTools-Reborn/issues)

## Last Verified

- **Date:** 2026-08-10
- **How:** every claim re-checked against `git`, `pom.xml`, `plugin.yml`, the workflow files, and the source
  tree. Nothing was carried over on trust.
- **Next verification due:** after the next release, or whenever `main` and `alpha` are reconciled.
