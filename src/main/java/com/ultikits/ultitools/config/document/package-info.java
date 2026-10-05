/**
 * The config storage layer: a config file as a document over SnakeYAML's node tree, the plain-data
 * boundary every value crosses before it reaches a file, the configuration write gate
 * ({@code OperatorFileWriter}) every write to an operator-editable configuration file goes through, and the
 * atomic writer that publishes what the gate verified.
 * <p>
 * The contract (maintainer rule of 2026-10-04): configuration an operator wrote is never overwritten
 * automatically. Each write owns named keys (or the framework's own comment lines); after rendering, every
 * byte outside them must equal the file as read and the file must still hold the bytes it was read from,
 * otherwise nothing is written and one warning names the file, the keys and the reason (a line number for a
 * layout the renderer cannot keep), never a value. No layout is normalized to make a write pass.
 * <p>
 * Framework-internal. Module authors use {@code AbstractConfigEntity} and the converter API; nothing
 * here is a module-facing contract.
 *
 * @since 6.3.0
 */
@ApiStatus.Internal
package com.ultikits.ultitools.config.document;

import org.jetbrains.annotations.ApiStatus;
