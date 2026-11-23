package com.ultikits.ultitools.ui;

class InMemoryGestureEngineContractTest extends AbstractGestureEngineContractTest {
    @Override protected AbstractUiServiceContractTest.Fixture createFixture() { return new InMemoryUiService.Registry(); }
}
