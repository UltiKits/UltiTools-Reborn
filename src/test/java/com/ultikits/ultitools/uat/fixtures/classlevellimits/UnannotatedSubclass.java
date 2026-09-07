package com.ultikits.ultitools.uat.fixtures.classlevellimits;

/**
 * Extends {@link AnnotatedBase} without redeclaring {@code @CmdExecutor} (Phase 10, Codex
 * review of PR #427). {@code CommandManager.register}'s own direct
 * {@code isAnnotationPresent(CmdExecutor.class)} check fails for this class -- neither
 * {@code @CmdExecutor} nor {@code @CmdTarget} is {@code @Inherited} -- so the runtime logs a
 * warning and registers NO Bukkit command for it at all; the extractor must emit no row here
 * either, not one describing a command that can never be exercised.
 *
 * @since 6.3.0
 */
public class UnannotatedSubclass extends AnnotatedBase {
}
