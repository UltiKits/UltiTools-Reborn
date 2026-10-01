# Config golden corpus (plan 17-56)

Every file below must parse and render byte for byte through `ConfigDocument` (`ConfigDocumentGoldenTest`), and each SHA-256 must
match the file on disk, so a checkout that converts line endings fails the test instead of passing on different bytes
(`.gitattributes` marks this directory `-text`).

- `bundled/` - every `.yml`/`.yaml` resource the fifteen active modules ship at `origin/master`, excluding `plugin.yml` and `lang/`,
  copied as bytes with `git show <commit>:<path> > <file>`. Measured 2026-10-01 after `git fetch origin master` in each module.
- `written-by-6.2/` - the text the `dfe71e01` writer (`AbstractConfigEntity#init` first boot, and `save()` for `10-`) produces for
  each shape of the research's file-compatibility table, captured in a throwaway detached worktree at `dfe71e01` (capture command
  and fixture list in `reviews/17-REVIEW-UltiTools-Reborn-wfu-configref.md`). The `save()` output of the comments fixture is
  byte-identical to its first-boot output and is not stored twice. `11-` is a file 6.2 writes on first boot and then cannot load
  (#575). The 6.2 writer's unreadable output (a `!!java.util.UUID` tag, #560) is not a round-trip fixture; `ConfigLoadResultTest`
  covers it.
- `hand-edited/` - operator-style files: comments in every position, quotes, YAML 1.1 scalars, flow collections, keys `yes`/`1`/`~`/`on`
  and dotted keys, anchors with merge keys, CRLF, `\u` escapes, four-space indentation, long lines, block scalars, a byte order
  mark, and a file without a final line break.

## Known limits of byte preservation

Unchanged documents return their exact source, including anchors. For a changed document without anchors, source spans are
spliced: changed scalars only, new keys at their mapping's end, removed keys and their own comments, framework-owned comment lines.
Untouched bytes are not emitted again. `splice-layout.yml` pins aligned inline comments, flow spacing, extra spaces after a colon,
a leading document marker, two indentation widths and trailing spaces, both unchanged and while another key is set, added or removed.

Only changed documents with anchors, aliases or merge keys use the recorded full-render fallback. Their values remain equal, but
SnakeYAML regenerates layout: inline-comment alignment, flow spacing, extra colon spacing, document markers, mixed indentation,
trailing spaces, folded scalars and unused anchor names need not survive. The fallback expands aliases and merge keys. It does not
promise byte preservation of untouched lines. Unchanged anchored files remain byte-identical.

Bundled module config files: 14 (control: 29 resource files listed, `plugin.yml` in all 15 listings)

| Fixture | Source | Commit | SHA-256 |
|---|---|---|---|
| bundled/UltiChat/config/announcements.yml | UltiKits/UltiChat `origin/master:src/main/resources/config/announcements.yml` (bytes via `git show`) | 20f97d5e5ec15d2e5007709c52e2437da82b637e | f4b8f220bd8d4f89fa72f4fe4b121b8aba9b7ac6d1e9c0ed137aee5e1c083a7d |
| bundled/UltiChat/config/autoreply.yml | UltiKits/UltiChat `origin/master:src/main/resources/config/autoreply.yml` (bytes via `git show`) | 20f97d5e5ec15d2e5007709c52e2437da82b637e | 8851d84744252d76340c5332ccbc4684f3754cc4d2c9f7365955d59a5d180661 |
| bundled/UltiChat/config/channels.yml | UltiKits/UltiChat `origin/master:src/main/resources/config/channels.yml` (bytes via `git show`) | 20f97d5e5ec15d2e5007709c52e2437da82b637e | 5def03b1f2397c11e31441d2d9d1429e862bcdfbd5cff5afdc5a53947f0db80e |
| bundled/UltiChat/config/chat.yml | UltiKits/UltiChat `origin/master:src/main/resources/config/chat.yml` (bytes via `git show`) | 20f97d5e5ec15d2e5007709c52e2437da82b637e | a487130a47a7b0f91ee1a85d440863b819d0ab78228c0af3789ba457f714606f |
| bundled/UltiChat/config/emojis.yml | UltiKits/UltiChat `origin/master:src/main/resources/config/emojis.yml` (bytes via `git show`) | 20f97d5e5ec15d2e5007709c52e2437da82b637e | f527f3e4dd5908f12765b1d6f74fcb1625326cd3b29113a516ef68dd4bd30ce9 |
| bundled/UltiEconomy/config/config.yml | UltiKits/UltiEconomy `origin/master:src/main/resources/config/config.yml` (bytes via `git show`) | 0911129b3aaee52366550fd9173f4b8ee1814073 | 2e0aa92bc5bb87c39476f604c4f56563f5296b59b7baad00289edbdfb8878a53 |
| bundled/UltiEconomy/config/currencies.yml | UltiKits/UltiEconomy `origin/master:src/main/resources/config/currencies.yml` (bytes via `git show`) | 0911129b3aaee52366550fd9173f4b8ee1814073 | c8c64f52fa6be5dd152dd920753b4f137558515f512c62cc9b2541ed7d2f64bc |
| bundled/UltiKits/config/config.yml | UltiKits/UltiKits `origin/master:src/main/resources/config/config.yml` (bytes via `git show`) | f873f4f81099124b1eec108cd341b7674ba0264c | e439091948991dfb8bd0c746d09be1cc6b0e854e91f19df20e227f43a611687f |
| bundled/UltiKits/kits/en/starter.yml | UltiKits/UltiKits `origin/master:src/main/resources/kits/en/starter.yml` (bytes via `git show`) | f873f4f81099124b1eec108cd341b7674ba0264c | 19c54bf7fb8612271084e09b0293f1ee7ccf6610926ae010e5e652b009d3e892 |
| bundled/UltiKits/kits/zh/starter.yml | UltiKits/UltiKits `origin/master:src/main/resources/kits/zh/starter.yml` (bytes via `git show`) | f873f4f81099124b1eec108cd341b7674ba0264c | 8b6ee89a053b6c5cbe15999e1402a8b573738e58b77071708fe0775a5ea2c1aa |
| bundled/UltiMenu/config/config.yml | UltiKits/UltiMenu `origin/master:src/main/resources/config/config.yml` (bytes via `git show`) | 5188a29f4c47b44fa04bcdf61369f764f63513e7 | 1df04bd3ffa701b09f7731a45d1daf27232554422c45b9eafc4751630d14f298 |
| bundled/UltiMenu/menus/en/example.yml | UltiKits/UltiMenu `origin/master:src/main/resources/menus/en/example.yml` (bytes via `git show`) | 5188a29f4c47b44fa04bcdf61369f764f63513e7 | 461ebf17793acae9a1df72699edf84a51e970507a0b6abeabf7a95d72a8acc6f |
| bundled/UltiMenu/menus/zh/example.yml | UltiKits/UltiMenu `origin/master:src/main/resources/menus/zh/example.yml` (bytes via `git show`) | 5188a29f4c47b44fa04bcdf61369f764f63513e7 | 72c4aadde6140640a6deaf095ca4f4a49f7f984ff759ef68aff4c0253e60e571 |
| bundled/UltiWorlds/config/worlds.yml | UltiKits/UltiWorlds `origin/master:src/main/resources/config/worlds.yml` (bytes via `git show`) | 1e19ed78a806181fd6d703102dc0c51054ca1e0a | c7fcd3c712442e484dcf08f24a97dd34a9dbd65213db080cd5091025f7d74ac1 |
| written-by-6.2/01-booleans.yml | the 6.2 writer's first-boot write in `init` (`Capture62WriterTest`) | dfe71e011beec0498ed89de6e387c7a39b60e815 | 2391661f11db515f365234885b304970e4add76bb2e52f19a21754db3e79ec86 |
| written-by-6.2/02-integers.yml | the 6.2 writer's first-boot write in `init` (`Capture62WriterTest`) | dfe71e011beec0498ed89de6e387c7a39b60e815 | 5c3cbdb8aaabbfb65593d0ad48a3ac891342009ad6b625baaeaf4ba799bef639 |
| written-by-6.2/03-decimals.yml | the 6.2 writer's first-boot write in `init` (`Capture62WriterTest`) | dfe71e011beec0498ed89de6e387c7a39b60e815 | 781add8f3fdd23ff32764f91d1308058593ae9cac8a91cd68e8834dfb5bb96b1 |
| written-by-6.2/04-strings.yml | the 6.2 writer's first-boot write in `init` (`Capture62WriterTest`) | dfe71e011beec0498ed89de6e387c7a39b60e815 | 8f8dce39a43fd549627bcde159ea68e8676469f127e797abe4d3f1e5d2f260b8 |
| written-by-6.2/05-unicode.yml | the 6.2 writer's first-boot write in `init` (`Capture62WriterTest`) | dfe71e011beec0498ed89de6e387c7a39b60e815 | efb8b9e3cbe4ee1a19d9e52b6200e3f091ae9a9f016b47aed6876a0391fb5ca3 |
| written-by-6.2/06-lists.yml | the 6.2 writer's first-boot write in `init` (`Capture62WriterTest`) | dfe71e011beec0498ed89de6e387c7a39b60e815 | ad7e64b4cf4e3a35dff19d52fb15b69ef1a09501d263c2073e66849ba645a9ca |
| written-by-6.2/07-list-of-maps.yml | the 6.2 writer's first-boot write in `init` (`Capture62WriterTest`) | dfe71e011beec0498ed89de6e387c7a39b60e815 | d99c268562e34fccb9ec573d05ca4566457f4395139bb2ad4a95aec559516cc6 |
| written-by-6.2/08-nested-maps.yml | the 6.2 writer's first-boot write in `init` (`Capture62WriterTest`) | dfe71e011beec0498ed89de6e387c7a39b60e815 | ca9382d650cecb39be6560a0940685fee81eb89d6b58e754c9e84fc7cfc64e34 |
| written-by-6.2/09-dotted-keys-first-boot.yml | the 6.2 writer's first-boot write in `init` (`Capture62WriterTest`) | dfe71e011beec0498ed89de6e387c7a39b60e815 | 6ffcb05416b821208017b4884e46e81b9bbe8e50c3b26899a4d7af8bf0c4d18c |
| written-by-6.2/10-dotted-keys-save.yml | the 6.2 writer's `save()` (`Capture62WriterTest`) | dfe71e011beec0498ed89de6e387c7a39b60e815 | 0b0d0318b656a0c2dba2619d762cedf7952e78a932886eba24e33d00257c2a4b |
| written-by-6.2/11-trailing-dot-first-boot.yml | the 6.2 writer's first-boot write in `init` (`Capture62WriterTest`) | dfe71e011beec0498ed89de6e387c7a39b60e815 | e603afe6bd4acffca4ebd1474455f1c9850de83718c352dce60e6a2070910e1b |
| written-by-6.2/12-configuration-serializable.yml | the 6.2 writer's first-boot write in `init` (`Capture62WriterTest`) | dfe71e011beec0498ed89de6e387c7a39b60e815 | 433ddbeee23e80965173b6149f0b2aed63dc4894fbd9db5ef19250b344fe891b |
| written-by-6.2/13-comments.yml | the 6.2 writer's first-boot write in `init` (`Capture62WriterTest`) | dfe71e011beec0498ed89de6e387c7a39b60e815 | df862d793fb19a5c9df89585156dce6d9236e05ea0d1c1f5c13c9608c4c83352 |
| hand-edited/anchors.yml | hand-written operator-style file (plan 17-56) | - | b1e36aa75b9687c319fce7277ab75807e118ad7abc8428c2dca2969e20aa9b26 |
| hand-edited/block-scalars.yml | hand-written operator-style file (plan 17-56) | - | fbeda3b6338fad33bb351d9771269e7952ae9415e9ff56e6f8021e06525cb8be |
| hand-edited/bom.yml | hand-written operator-style file (plan 17-56) | - | 4b0ddffa1e9849a9615a4a796d212bf88d99b022d8b3bbe6aab21230c61d1b98 |
| hand-edited/comments-everywhere.yml | hand-written operator-style file (plan 17-56) | - | 2f6dbc81dc00edaf747af51f92ffa44dc1880b474c911b0b58282c3d1c5b6226 |
| hand-edited/crlf.yml | hand-written operator-style file (plan 17-56) | - | 290f28e26bb226566e7c1355de6c773abe56dbfe587c5e087fef4f72cfeb2771 |
| hand-edited/escapes.yml | hand-written operator-style file (plan 17-56) | - | dee20118ab181004798d7c76044c22a30943a9e3dab4e7e24da5a9c339fad861 |
| hand-edited/flow-collections.yml | hand-written operator-style file (plan 17-56) | - | 3177f61246593f1de87110aeb88b3cb28615ec231385b27d94202217b750a32c |
| hand-edited/indent-four.yml | hand-written operator-style file (plan 17-56) | - | 87e613daea324d031505cf48e78315bb09bb3d5111ed1fcf9406e569a4c35645 |
| hand-edited/keys.yml | hand-written operator-style file (plan 17-56) | - | 812f66b4160133179da058aa3bbb7ef8848dc715bf911e30df4c3cdb9e402cef |
| hand-edited/long-lines.yml | hand-written operator-style file (plan 17-56) | - | 8f4b9c55809d48a37ea0657106cf62c5057a64d22ba80124e9735e087791e2f3 |
| hand-edited/no-final-newline.yml | hand-written operator-style file (plan 17-56) | - | 9c268a86cd3457e0810a4e523fcf9b669e078f58ed18b00fbb2b8d2cc2566dd9 |
| hand-edited/scalars.yml | hand-written operator-style file (plan 17-56) | - | 0cf5fefd3ecd714d72a62315cd5b54d712b3276ab2534c90988bcd7d98ffdd5c |
| hand-edited/splice-layout.yml | hand-written review B1/B2 regression shapes (plan 17-56b) | - | bb13ed9c26452d4f01c712f67da07be1da6f97b60bdf74cf5c63ac73ecca4a76 |
