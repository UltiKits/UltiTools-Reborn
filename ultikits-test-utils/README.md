# UltiKits Test Utilities

Shared test helpers for authors writing UltiKits plugin modules on top of
[UltiTools-API](https://github.com/UltiKits/UltiTools-Reborn). It provides
lightweight MockBukkit/Mockito helpers and factories so a module's own test
suite does not have to reimplement the same Bukkit and framework mocks.

## Coordinates

```xml
<dependency>
    <groupId>com.ultikits</groupId>
    <artifactId>ultikits-test-utils</artifactId>
    <version>1.0.0</version>
    <scope>test</scope>
</dependency>
```

## Usage

```java
import com.ultikits.ultitools.testing.UltiToolsTestHelper;
import com.ultikits.ultitools.testing.MockFactories;

class MyModuleTest {
    @Test
    void doesSomething() {
        Server server = UltiToolsTestHelper.mockBukkitServer();
        Player player = MockFactories.createMockPlayer("Steve");
        // ... exercise the module under test
        UltiToolsTestHelper.cleanup();
    }
}
```

This artifact deliberately does not depend on `UltiTools-API` or a Bukkit
server API implementation. Every consumer is, by construction, an UltiTools
module author who already has both on their own test classpath — declare
this artifact alongside them.

## License

MIT — see [`LICENSE`](./LICENSE).
