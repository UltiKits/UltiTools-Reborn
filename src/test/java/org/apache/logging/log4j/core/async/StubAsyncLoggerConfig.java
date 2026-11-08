package org.apache.logging.log4j.core.async;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.config.LoggerConfig;

/**
 * Test stand-in for an asynchronous logger configuration: {@code ConsoleMirror} recognises one by
 * its package ({@code org.apache.logging.log4j.core.async.}), and the real
 * {@code AsyncLoggerConfig} needs the LMAX Disruptor, which neither Paper nor the tests ship. It
 * is never logged through; it only sits in the configuration while a test runs.
 */
public class StubAsyncLoggerConfig extends LoggerConfig {

    public StubAsyncLoggerConfig(String name) {
        super(name, Level.INFO, true);
    }
}
