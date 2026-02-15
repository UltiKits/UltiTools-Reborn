package com.ultikits.ultitools.annotations;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("@PlayerCache Annotation Tests")
class PlayerCacheTest {

    static class TestService {
        @PlayerCache
        private final Map<UUID, String> nameCache = new ConcurrentHashMap<>();

        @PlayerCache(saveBeforeRemove = true)
        private final Map<UUID, Object> dataCache = new ConcurrentHashMap<>();

        private final Map<UUID, Long> notAnnotated = new ConcurrentHashMap<>();
    }

    @Test
    @DisplayName("Annotation is present on marked fields")
    void annotationPresent() throws NoSuchFieldException {
        Field field = TestService.class.getDeclaredField("nameCache");
        assertThat(field.isAnnotationPresent(PlayerCache.class)).isTrue();
    }

    @Test
    @DisplayName("Annotation not present on unmarked fields")
    void annotationNotPresent() throws NoSuchFieldException {
        Field field = TestService.class.getDeclaredField("notAnnotated");
        assertThat(field.isAnnotationPresent(PlayerCache.class)).isFalse();
    }

    @Test
    @DisplayName("saveBeforeRemove defaults to false")
    void defaultSaveBeforeRemove() throws NoSuchFieldException {
        Field field = TestService.class.getDeclaredField("nameCache");
        PlayerCache annotation = field.getAnnotation(PlayerCache.class);
        assertThat(annotation.saveBeforeRemove()).isFalse();
    }

    @Test
    @DisplayName("saveBeforeRemove can be set to true")
    void customSaveBeforeRemove() throws NoSuchFieldException {
        Field field = TestService.class.getDeclaredField("dataCache");
        PlayerCache annotation = field.getAnnotation(PlayerCache.class);
        assertThat(annotation.saveBeforeRemove()).isTrue();
    }
}
