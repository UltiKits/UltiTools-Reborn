/**
 * Two-stage startup: Paper resolves runtime libraries, then UltiTools decides the admitted module
 * set and invokes optional early hooks before worlds load. Module construction, IoC, commands and
 * listeners remain in the plugin enable phase. Early hooks cannot use a Bukkit server or other
 * plugin instances. Internal entry points in this package are not APIs for modules.
 *
 * <p>Modules can declare custom enchantments, item and enchantment tag changes, and bundled
 * data packs through the bootstrap context. These are buffered while the hook runs and become
 * eligible for Paper's write events only after the hook returns normally.
 */
@org.jetbrains.annotations.NotNullByDefault
package com.ultikits.ultitools.bootstrap;
