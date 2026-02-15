package com.ultikits.ultitools.abstracts.command.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("TypeParseException Tests")
class TypeParseExceptionTest {

    @Test
    @DisplayName("Should construct with message only")
    void shouldConstructWithMessage() {
        String message = "Parse error";
        TypeParseException exception = new TypeParseException(message);
        
        assertEquals(message, exception.getMessage());
        assertNull(exception.getInputValue());
        assertNull(exception.getTargetType());
        assertNull(exception.getCause());
    }

    @Test
    @DisplayName("Should construct with message and cause")
    void shouldConstructWithMessageAndCause() {
        String message = "Parse error";
        Throwable cause = new RuntimeException("Root cause");
        TypeParseException exception = new TypeParseException(message, cause);
        
        assertEquals(message, exception.getMessage());
        assertEquals(cause, exception.getCause());
        assertNull(exception.getInputValue());
        assertNull(exception.getTargetType());
    }

    @Test
    @DisplayName("Should construct with full details")
    void shouldConstructWithFullDetails() {
        String value = "invalid";
        Class<?> type = Integer.class;
        String message = "Cannot parse";
        
        TypeParseException exception = new TypeParseException(value, type, message);
        
        assertEquals(message, exception.getMessage());
        assertEquals(value, exception.getInputValue());
        assertEquals(type, exception.getTargetType());
        assertNull(exception.getCause());
    }

    @Test
    @DisplayName("Should construct with full details and cause")
    void shouldConstructWithFullDetailsAndCause() {
        String value = "invalid";
        Class<?> type = Integer.class;
        String message = "Cannot parse";
        Throwable cause = new NumberFormatException();
        
        TypeParseException exception = new TypeParseException(value, type, message, cause);
        
        assertEquals(message, exception.getMessage());
        assertEquals(cause, exception.getCause());
        assertEquals(value, exception.getInputValue());
        assertEquals(type, exception.getTargetType());
    }

    @Test
    @DisplayName("Should create invalid format exception")
    void shouldCreateInvalidFormat() {
        String value = "abc";
        Class<?> type = Integer.class;
        
        TypeParseException exception = TypeParseException.invalidFormat(value, type);
        
        assertEquals(value, exception.getInputValue());
        assertEquals(type, exception.getTargetType());
        assertTrue(exception.getMessage().contains("invalid format"));
        assertTrue(exception.getMessage().contains(value));
        assertTrue(exception.getMessage().contains(type.getSimpleName()));
    }

    @Test
    @DisplayName("Should create null value exception")
    void shouldCreateNullValue() {
        Class<?> type = Double.class;
        
        TypeParseException exception = TypeParseException.nullValue(type);
        
        assertNull(exception.getInputValue());
        assertEquals(type, exception.getTargetType());
        assertTrue(exception.getMessage().contains("Cannot parse null"));
        assertTrue(exception.getMessage().contains(type.getSimpleName()));
    }

    @Test
    @DisplayName("Should return null for unset fields")
    void shouldReturnNullForUnsetFields() {
        TypeParseException ex1 = new TypeParseException("msg");
        assertNull(ex1.getInputValue());
        assertNull(ex1.getTargetType());
        
        TypeParseException ex2 = new TypeParseException("msg", new RuntimeException());
        assertNull(ex2.getInputValue());
        assertNull(ex2.getTargetType());
    }
}
