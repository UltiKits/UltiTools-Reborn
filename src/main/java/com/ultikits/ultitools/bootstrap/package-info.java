/**
 * Two-stage startup: Paper resolves runtime libraries, then UltiTools decides the admitted module
 * set and invokes optional early hooks before worlds load. Module construction, IoC, commands and
 * listeners remain in the plugin enable phase. Early hooks cannot use a Bukkit server or other
 * plugin instances. Internal entry points in this package are not APIs for modules.
 */
@org.jetbrains.annotations.NotNullByDefault
package com.ultikits.ultitools.bootstrap;
