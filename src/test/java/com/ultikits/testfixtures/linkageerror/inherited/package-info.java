/**
 * Fixtures for the inherited-signature case of the scanner's per-class LinkageError boundary.
 * <p>
 * {@link com.ultikits.testfixtures.linkageerror.inherited.InheritedSignatureCommand} declares nothing itself, so its own class
 * and the {@code @Final} check load and run normally; its base class
 * {@link com.ultikits.testfixtures.linkageerror.inherited.OptionalDependencyCommandBase} has a command method whose signature
 * names {@link com.ultikits.testfixtures.linkageerror.inherited.MissingOptionalType}, which the test hides from the class loader.
 * The first code to read the base class's method descriptors is therefore the inherited {@code @CmdTarget}
 * composition check, and the scan must skip only the command class while
 * {@link com.ultikits.testfixtures.linkageerror.inherited.InheritedSignatureSurvivor} still registers.
 */
package com.ultikits.testfixtures.linkageerror.inherited;
