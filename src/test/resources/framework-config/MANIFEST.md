# Framework config.yml fixtures (plan 17-66)

Each file is the `config.yml` entry of a released `com.ultikits:UltiTools-API` jar, copied as bytes with
`unzip -p <jar> config.yml > config-<version>.yml` (2026-10-05). `FrameworkConfigMigrationGateTest` runs the framework's
start-up migration on each and requires every original line to keep its bytes (UltiKits/UltiTools-Reborn#605).
Jars: 6.2.0, 6.2.1, 6.2.2, 6.2.4 and 6.2.5 from the local Maven repository backup
`evidence/phase-17/fixtures/config-fixture/m2-backup/<version>/`; 6.2.3 from Maven Central
(`repo1.maven.org/maven2/com/ultikits/UltiTools-API/6.2.3/`, SHA-1 matching the published `.jar.sha1`).
The directory is marked `-text` in `.gitattributes`, so no checkout converts line endings.

| Fixture | Version | Jar SHA-256 | Fixture SHA-256 |
|---|---|---|---|
| `config-6.2.0.yml` | 6.2.0 | `68ecdfe1e7129331a234b45099b3bef5b997a2cb95367db1c3e865d16c4a7930` | `804d989c2799f38fc3a3f3a585ab318389e0881984d62c0cb16af1cae63d23de` |
| `config-6.2.1.yml` | 6.2.1 | `0849afc3f4be257b762c74d0e143cca29f4e75d4e8b2fb3354686f3035ad6af7` | `804d989c2799f38fc3a3f3a585ab318389e0881984d62c0cb16af1cae63d23de` |
| `config-6.2.2.yml` | 6.2.2 | `95aec3008f3f67f2f6c69729dc3360ec771846248ca2085b2e8e6e04b4a9b1ac` | `804d989c2799f38fc3a3f3a585ab318389e0881984d62c0cb16af1cae63d23de` |
| `config-6.2.3.yml` | 6.2.3 | `cfeda0b9ddca957a808b4ca2ac3484263c3ea132f8adb8a34231647b52b1e065` | `e2385d3a477a8a6ca9eed2d5742c43c999d8cc58c0f7535bf2ddee6a7ef6ac29` |
| `config-6.2.4.yml` | 6.2.4 | `3f53e7cd4f8d4f8185fce4f34ca01f72ec0b3a9da5a28b5326804dfead1791ad` | `e2385d3a477a8a6ca9eed2d5742c43c999d8cc58c0f7535bf2ddee6a7ef6ac29` |
| `config-6.2.5.yml` | 6.2.5 | `e0daa4e27fb7228ae4be5c5de3d3b1c04c858c61d904af05ee924c4fb8f93b74` | `e2385d3a477a8a6ca9eed2d5742c43c999d8cc58c0f7535bf2ddee6a7ef6ac29` |

6.2.0 … 6.2.2 ship one file (no `ultipanel` section) and 6.2.3 … 6.2.5 another (`ultipanel.logging.error-reporting`);
the fixtures are kept per version so each release is measured by name.
