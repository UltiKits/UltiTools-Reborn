package com.ultikits.ultitools.ui;

class InMemorySlotContainerContractTest extends AbstractSlotContainerContractTest {
    @Override protected AbstractUiServiceContractTest.Fixture createFixture() { return new InMemoryUiService.Registry(); }
}
