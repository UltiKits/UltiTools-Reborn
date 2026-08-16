package com.ultikits.ultitools.manager;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.ultikits.ultitools.annotations.PlayerCache;
import com.ultikits.ultitools.annotations.PlayerCacheSaver;
import org.jetbrains.annotations.ApiStatus;

/**
 * Manages automatic cleanup of @PlayerCache-annotated Map fields when players quit.
 * <p>
 * 管理带有 @PlayerCache 注解的 Map 字段，在玩家退出时自动清理。
 *
 * @since 6.2.0
 */
@ApiStatus.Internal
public class PlayerCacheManager {

    private static final Logger LOGGER = Logger.getLogger(PlayerCacheManager.class.getName());

    private final List<TrackedBean> trackedBeans = new ArrayList<>();

    /**
     * Scans a bean for @PlayerCache fields and tracks them.
     */
    public void registerBean(Object bean) {
        List<TrackedField> fields = new ArrayList<>();
        for (Field field : bean.getClass().getDeclaredFields()) {
            PlayerCache annotation = field.getAnnotation(PlayerCache.class);
            if (annotation != null && Map.class.isAssignableFrom(field.getType())) {
                field.setAccessible(true); // NOPMD
                fields.add(new TrackedField(field, annotation.saveBeforeRemove()));
            }
        }
        if (!fields.isEmpty()) {
            trackedBeans.add(new TrackedBean(bean, fields));
        }
    }

    /**
     * Removes a bean from tracking.
     */
    public void unregisterBean(Object bean) {
        trackedBeans.removeIf(tb -> tb.bean == bean);
    }

    /**
     * Called when a player quits. Cleans up all tracked maps.
     */
    @SuppressWarnings("unchecked")
    public void onPlayerQuit(UUID playerId) {
        for (TrackedBean tracked : trackedBeans) {
            for (TrackedField tf : tracked.fields) {
                try {
                    if (tf.saveBeforeRemove && tracked.bean instanceof PlayerCacheSaver) {
                        ((PlayerCacheSaver) tracked.bean).savePlayerData(playerId);
                    }
                    Map<UUID, ?> map = (Map<UUID, ?>) tf.field.get(tracked.bean);
                    if (map != null) {
                        map.remove(playerId);
                    }
                } catch (IllegalAccessException e) {
                    LOGGER.log(Level.WARNING, "Failed to clean player cache field: "
                            + tf.field.getName() + " on " + tracked.bean.getClass().getName(), e);
                }
            }
        }
    }

    /**
     * Returns the number of beans being tracked.
     */
    public int getTrackedBeanCount() {
        return trackedBeans.size();
    }

    private static class TrackedBean {
        final Object bean;
        final List<TrackedField> fields;

        TrackedBean(Object bean, List<TrackedField> fields) {
            this.bean = bean;
            this.fields = fields;
        }
    }

    private static class TrackedField {
        final Field field;
        final boolean saveBeforeRemove;

        TrackedField(Field field, boolean saveBeforeRemove) {
            this.field = field;
            this.saveBeforeRemove = saveBeforeRemove;
        }
    }
}
