/**
 * Fixtures for {@code com.ultikits.ultitools.context.ComponentScannerTest}'s LinkageError
 * skip-and-continue coverage (03-07, D-25/D-26): a class whose loading raises a
 * {@link java.lang.LinkageError} (or a subtype such as {@code NoClassDefFoundError}) must be
 * skipped with a {@code Level.WARNING} record, and every other class in the same package must
 * still register - on <b>both</b> {@code ComponentScanner} scan modes identically.
 * <p>
 * See {@link com.ultikits.testfixtures.linkageerror.scanner} for the two real fixture classes.
 * Quarantined outside {@code com.ultikits.ultitools} for the same reason as
 * {@code com.ultikits.testfixtures.finalviolation}: {@code ComponentScanner#scanDirectory}
 * recurses into subdirectories with no notion of "this is a test fixture" - it just sees real
 * classes on the classpath - so a fixture that changes a scan's outcome must live entirely
 * outside the framework's own package tree.
 * <br>
 * 本包为 {@code ComponentScannerTest} 的 LinkageError 跳过并继续覆盖（03-07，D-25/D-26）提供
 * fixture：一个加载时抛出 {@link java.lang.LinkageError}（或其子类，例如
 * {@code NoClassDefFoundError}）的类必须被跳过并记录一条 {@code Level.WARNING}，同一包内的其他
 * 类必须照常注册——且在两种扫描模式下表现完全一致。出于与
 * {@code com.ultikits.testfixtures.finalviolation} 相同的理由，本包放在
 * {@code com.ultikits.ultitools} 包树之外。
 */
package com.ultikits.testfixtures.linkageerror;
