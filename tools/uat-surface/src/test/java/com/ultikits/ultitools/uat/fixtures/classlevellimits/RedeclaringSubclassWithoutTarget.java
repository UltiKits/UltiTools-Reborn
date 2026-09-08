package com.ultikits.ultitools.uat.fixtures.classlevellimits;

import com.ultikits.ultitools.annotations.command.CmdExecutor;

/**
 * Redeclares {@code @CmdExecutor} but NOT {@code @CmdTarget}, extending {@link AnnotatedBase}
 * (which carries {@code @CmdTarget(PLAYER)}) (Phase 10, Codex review of PR #427).
 * {@code BaseCommandExecutor.createDefaultValidatorChain}'s own direct
 * {@code this.getClass().getAnnotation(CmdTarget.class)} returns {@code null} for this
 * concrete class -- {@code @CmdTarget} is not {@code @Inherited} -- so
 * {@code SenderTypeValidator.fromAnnotation(null)} accepts {@code BOTH} sender types at
 * runtime, NOT the ancestor's {@code PLAYER}-only restriction. The extractor must report the
 * same absence of a restriction, not the inherited one.
 *
 * @since 6.3.0
 */
@CmdExecutor(alias = {"redeclared"})
public class RedeclaringSubclassWithoutTarget extends AnnotatedBase {
}
