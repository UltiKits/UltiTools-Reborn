/**
 * Framework-owned Bukkit listeners.
 * <p>
 * These listeners back core behaviour (player cache eviction, update
 * notification on join, enhanced player events forwarded to UltiPanel). They are
 * registered by the framework during bootstrap. Modules declare their own
 * listeners with {@code @EventListener} instead of touching these.
 *
 * @since 6.2.5
 */
@ApiStatus.Internal
package com.ultikits.ultitools.listeners;

import org.jetbrains.annotations.ApiStatus;
