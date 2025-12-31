package com.ultikits.ultitools.context.scan;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.ultikits.ultitools.context.SimpleContainer;

class ScanTest {
    @Test
    void testProcessConfigurationClass() throws ClassNotFoundException {
        SimpleContainer container = new SimpleContainer();
        container.setClassLoader(this.getClass().getClassLoader());
        
        container.processConfigurationClass(TestConfig.class);
        
        assertTrue(container.containsBean("testComponent"), "Container should contain testComponent");
        assertNotNull(container.getBean("testComponent"), "Bean testComponent should not be null");
    }
}
