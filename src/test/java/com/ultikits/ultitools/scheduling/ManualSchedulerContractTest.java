package com.ultikits.ultitools.scheduling;

class ManualSchedulerContractTest extends AbstractSchedulerContractTest {
    @Override
    protected Fixture createFixture() {
        return new ManualScheduler.Engine();
    }
}
