package com.ultikits.ultitools.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a Map&lt;UUID, ?&gt; field for automatic cleanup when a player quits.
 * <p>
 * When a player disconnects, the framework automatically calls remove(uuid)
 * on all annotated maps across all registered service beans.
 * <p>
 * 标记一个 Map&lt;UUID, ?&gt; 字段，当玩家退出时自动清理。
 *
 * @since 6.3.0
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
