/**
 * The config storage layer: a config file as a document over SnakeYAML's node tree, the plain-data
 * boundary every value crosses before it reaches a file, and the atomic writer that is the only way a
 * config file is replaced.
 * <p>
 * Framework-internal. Module authors use {@code AbstractConfigEntity} and the converter API; nothing
 * here is a module-facing contract.
 *
 * @since 6.3.0
 */
@ApiStatus.Internal
package com.ultikits.ultitools.config.document;

import org.jetbrains.annotations.ApiStatus;
