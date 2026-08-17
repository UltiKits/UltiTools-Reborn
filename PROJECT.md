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
- **Runtime dependency model:** only `paper-api`, at `provided` scope. Paper auto-downloads the `libraries:`
  entries declared in `plugin.yml`. Adventure `Component` is used throughout, so plain Spigot is not a
  supported build target.
- **Downstream:** consumer plugin modules declare `com.ultikits:UltiTools-API` at `provided` scope. Bumping
  the version here affects every consumer that hard-codes it rather than inheriting a property.

## Branching Rule

**Two long-lived branches. Never commit to either directly.**

- **`main` is the GitHub default branch** (`origin/HEAD` → `origin/main`). Scheduled workflow triggers
  fire only for the copy of a workflow that lives here.
- **`alpha` is the integration branch**, not the default. Day-to-day work targets `alpha`.
- **A pull request into `main` may only come from `alpha`.** This is enforced by `pr-source-guard.yml`,
  which is wired up as a required check — anything else has to integrate into `alpha` first. Target
  `alpha` unless you have been told otherwise.
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

All jobs run on JDK 21 (temurin). **Workflow files change more often than this card does — read
`.github/workflows/` rather than trusting the table below whenever behaviour surprises you.**

| Workflow | Trigger | What it does |
|---|---|---|
| `maven-ci.yml` | `push` on every branch, **and** `pull_request` into `main` or `alpha` | `mvn -B clean verify` plus JaCoCo reporting and a Codacy coverage upload. A second job builds a SNAPSHOT and runs only on `push` to `alpha`. |
| `pr-source-guard.yml` | `pull_request` into `main` | Wired up as a required check. Rejects any pull request into `main` whose head branch is not `alpha`. |
| `publish-packages.yml` | `release: [published]` — a published GitHub Release, **not** a tag push | Sets the version from the release tag, builds skipping tests and GPG, publishes to GitHub Packages under the lowercase id `ultitools-api`. A second `publish-central` job targets Maven Central but is gated behind the repository variable `PUBLISH_TO_CENTRAL == 'true'` — it does not run unless that variable is set. |
| `gpg-key-expiry.yml` | monthly `schedule`, `workflow_dispatch`, and pull requests touching its own script | Warns before the Maven Central signing key expires. Uses the public key only. `schedule` fires only for workflows living on the **default** branch. |

Maven Central publishing goes through `central-publishing-maven-plugin` with server id `ultikits-sonatype`.

Releases are cut from the maintainer's machine with `.github/scripts/release.sh`, which runs every check
that has to happen **before** the tag exists (clean worktree, release branch, version format, tag not taken)
and then creates the Release with the JAR attached. `publish-packages.yml` takes over from `release:
published`. There is no `workflow_dispatch` release workflow — one existed, was never run once, and was
removed in #231.

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
3. **`CommandManager.registerAll(plugin, packageName)` casts to the legacy `AbstractCommandExecutor`.**
   `BaseCommandExecutor` does not extend it — it implements `TabExecutor` directly — so pointing that
   overload at a package containing one throws `ClassCastException`. The surrounding `catch` lists only the
   four reflective checked exceptions, so the CCE **propagates out of registration** rather than being
   skipped. Use `CommandManager.register(plugin, Class)` for explicit registration, or the container-based
   `registerAll(plugin)` overload, which resolves commands as `CommandExecutor` beans and does not cast.
4. **Bukkit thread safety.** Anything reaching Bukkit from an async context must go through
   `Bukkit.getScheduler().runTask()`, or Paper's AsyncCatcher rejects it.
5. **AOP uses ByteBuddy subclass proxies only.** `final` classes and methods cannot be proxied, and
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

- **Date:** 2026-08-15, against `alpha`.
- **How:** every claim re-checked against `git`, `pom.xml`, `plugin.yml`, `.github/workflows/`, and the
  source tree. Nothing was carried over on trust.
- **What this card is for:** the things that do not change — the annotation namespace, the base-class
  traps, the credential hazard, the line-ending convention. Anything that is a snapshot of current state
  (branch divergence, dependency versions, which jobs a workflow happens to have today) belongs in the
  file that owns it, and this card points you at that file instead of copying the number.
- **Next verification due:** after the next release, or whenever `.github/workflows/` changes shape.
