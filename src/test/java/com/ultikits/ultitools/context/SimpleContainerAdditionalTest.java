package com.ultikits.ultitools.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SimpleContainerAdditionalTest {
    private SimpleContainer container;

    @BeforeEach
    void setUp() {
        container = new SimpleContainer();
    }

    @Test
    @DisplayName("Should register and retrieve type supplier")
    void testRegisterTypeSupplier() {
        container.registerTypeSupplier(String.class, () -> "test");
        String result = container.getBean(String.class);
        assertEquals("test", result);
    }

    @Test
    @DisplayName("Should return null when getBean with name and wrong type")
    void testGetBeanByNameAndWrongType() {
        container.registerSingleton("test", "stringBean");
        Integer result = container.getBean("test", Integer.class);
        assertNull(result);
    }
    
    @Test
    @DisplayName("Should return bean when getBean with name and correct type")
    void testGetBeanByNameAndCorrectType() {
        container.registerSingleton("test", "stringBean");
        String result = container.getBean("test", String.class);
        assertEquals("stringBean", result);
    }
}
