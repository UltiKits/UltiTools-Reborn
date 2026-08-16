/**
 * Framework-owned Bukkit listeners.
 * <p>
 * 框架自身注册的 Bukkit 监听器。
 * <p>
 * These listeners back core behaviour (player cache eviction, update
 * notification on join, enhanced player events forwarded to UltiPanel). They are
 * registered by the framework during bootstrap. Modules declare their own
 * listeners with {@code @EventListener} instead of touching these.
 * <p>
 * 这些监听器支撑核心行为（玩家缓存清理、进服时的更新提示、转发给 UltiPanel 的
 * 增强玩家事件），由框架在启动时注册。模块请用 {@code @EventListener} 声明自己的
 * 监听器，不要碰这里的类。
 *
 * @since 6.2.5
 */
@ApiStatus.Internal
package com.ultikits.ultitools.listeners;

import org.jetbrains.annotations.ApiStatus;
