package com.ultikits.ultitools.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a field for automatic cleanup when a player quits.
 * <p>
 * Three field shapes are accepted, and {@link com.ultikits.ultitools.manager.PlayerCacheManager}
 * refuses (throws at registration) any other shape rather than silently ignoring it:
 * <ul>
 *   <li>{@code Map<UUID, ?>} -- the whole entry (key and value, including a nested value such as
 *       {@code Map<UUID, Map<String, Long>>}) is removed via {@code remove(uuid)}.</li>
 *   <li>{@code Set<UUID>} -- the UUID itself is removed via {@code remove(uuid)}.</li>
 *   <li>{@code Map<?, UUID>} -- every entry whose VALUE equals the quitting player's UUID is
 *       removed; entries belonging to other players are left intact.</li>
 * </ul>
 * <p>
 * 标记一个字段，当玩家退出时自动清理。支持三种字段形状：以 UUID 为键的 Map、Set&lt;UUID&gt;，
 * 以及以 UUID 为值的 Map；其他形状会在注册时被拒绝，而不是被静默忽略。
 *
 * @since 6.2.0
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface PlayerCache {

    /**
     * If true, the framework will call the bean's {@code savePlayerData(UUID)}
     * method (if it implements {@link PlayerCacheSaver}) before removing the entry.
     *
     * @return whether to save before removing
     */
    boolean saveBeforeRemove() default false;
}
