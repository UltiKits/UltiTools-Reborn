# 待夹具 — catalogue REST samples

These files are hand-written placeholders, not an exported contract. The module-catalogue REST
contract of UltiCloud (backend P3/P15) has no frozen export yet, so the framework cannot read a
real fixture set for it.

## 待夹具 (pending fixture)

Every test that reads one of these samples carries `@PendingFixture` (JUnit tag
`pending-fixture`), so the phase closeout can list them mechanically.

## Source of the shapes

- Backend P15 planning record, `UltiKits/ulticloud` branch `feature/p15-catalogue` commit
  `fc07890`, `.planning/phases/15-catalogue/15-CONTEXT.md` §4 (query parameters, object
  differences, URL shapes, `moduleRef` rule) and §5 (export layout, fixture list).
- Contract answers to the framework's question 12 (`FW-contract-answers.md`): the download route
  answers 200 with the bytes and never a 302; `:moduleRef` accepts a ULID or an identifier;
  `GET /v1/catalogue/framework` names the newest UltiTools version.
- `22-DECISIONS.md`, "Backend catalogue contract (planning-stage ...)": the parameter names,
  `sha256`, `sizeBytes`, `apiLevel`, the relative `download.url` and the 404 for a non-public
  version.

## Shape

Each sample is a whole response body, as the API answers it: `{"data": ...}` for a success,
`{"error": {...}}` for a failure. The list endpoints answer `data = {items, total, page,
pageSize}`; a version answers `data = {version, isLatest, publishedAt, sizeBytes, sha256,
apiLevel, changelog, download}` where `download` is `{"kind": "public", "url": "<relative>"}` or
`{"kind": "license_required"}`.

## Placeholders replaced when served

`sizeBytes` and `sha256` in `version-public.json` are placeholders: the test catalogue stub serves
a JAR it generates, so it substitutes the real byte count and hash of exactly the bytes it serves.
Everything else is served byte for byte.

## Replacement

Plan 22-26 replaces this directory with the frozen export
(`packages/contracts/generated/rest-v1/catalogue/<version>/`, MANIFEST-checked) and removes the
`@PendingFixture` markers. Keep fixture loading in one place — `StubCatalogue` — so that swap
touches one class.
