package com.ultikits.ultitools.ui;

/** Runs the reusable display contract against the correct in-memory implementation. */
class InMemoryUiServiceContractTest extends AbstractUiServiceContractTest {
    @Override protected Fixture createFixture() { return new InMemoryUiService.Registry(); }
}
