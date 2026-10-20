/**
 * Test-only fixture family for a single malformed {@code @AliasFor} declaration, discovered
 * during a real {@code ComponentScanner.scanPackage} walk rather than by calling
 * {@code MergedAnnotationResolver.validateAliases(Class)} directly.
 * <p>
 * This closes the {@code unrun-verify} window 03-01 recorded in {@code .planning/WINDOWS.md}
 * (id 1): 03-01 proved the resolver throws {@link com.ultikits.ultitools.exceptions.ContainerException}
 * for a malformed declaration directly against the resolver, but could not prove it propagates
 * out of a real {@code ComponentScanner.scanPackage()} scan, because
 * {@code ComponentScanner.hasComponentAnnotation} was not yet wired to the resolver -- that
 * wiring is 03-02's own task. See {@link com.ultikits.testfixtures.malformedalias.scanner} for
 * the fixture itself.
 * <p>
 * This package family exists <b>outside</b> {@code com.ultikits.ultitools} on purpose, following
 * the exact precedent of {@link com.ultikits.testfixtures.finalviolation}: component scanning
 * walks whatever package it is told to scan, recursively, with no notion of "this is a test
 * fixture, skip it". A malformed alias sitting inside {@code com.ultikits.ultitools.context}
 * (even nested under it, since {@code ComponentScanner#scanDirectory} recurses into
 * subdirectories) would trip every other test that scans that tree -
 * {@code ComponentScannerTest} scans {@code com.ultikits.ultitools.context} directly - for a
 * reason that has nothing to do with what those tests actually check.
 * <p>
 * <b>Rule: this package itself must never directly hold a fixture class, and no test may scan it
 * directly</b> - only ever scan (or import from) the named {@code scanner} subpackage, mirroring
 * {@link com.ultikits.testfixtures.finalviolation}'s own rule for the identical reason.
 * <br>
 * 本包族服务于一个单一的、故意写错的 {@code @AliasFor} 声明，通过一次真实的
 * {@code ComponentScanner.scanPackage} 扫描发现它，而不是直接调用
 * {@code MergedAnnotationResolver.validateAliases(Class)}。
 * <p>
 * 本包用于关闭 03-01 记录在 {@code .planning/WINDOWS.md} 中的 {@code unrun-verify} 缺口（id 1）：
 * 03-01 已经直接对解析器证明了畸形声明会抛出
 * {@link com.ultikits.ultitools.exceptions.ContainerException}，但当时无法证明它会从一次真实的
 * {@code ComponentScanner.scanPackage()} 扫描中传播出来，因为
 * {@code ComponentScanner.hasComponentAnnotation} 尚未接入解析器——这项接线正是 03-02 自己的任务。
 * 具体 fixture 见 {@link com.ultikits.testfixtures.malformedalias.scanner}。
 * <p>
 * 本包族故意放在 {@code com.ultikits.ultitools} 包树之外，完全遵循
 * {@link com.ultikits.testfixtures.finalviolation} 的既有先例：组件扫描会递归扫描它被要求扫描的
 * 任意包，并不知道"这是测试 fixture、应该跳过"。若把一个畸形别名放进
 * {@code com.ultikits.ultitools.context}（哪怕只是嵌套在它之下，因为
 * {@code ComponentScanner#scanDirectory} 会递归下探子目录），就会无关地带崩每一个扫描该树的其他
 * 测试——{@code ComponentScannerTest} 就直接扫描 {@code com.ultikits.ultitools.context}。
 * <p>
 * <b>规则：本包直属不得放任何类，任何测试也不得直接扫描本包本身</b>——永远只扫描（或 import）具名的
 * {@code scanner} 子包，与 {@link com.ultikits.testfixtures.finalviolation} 出于同样的原因保持
 * 同一条规则。
 */
package com.ultikits.testfixtures.malformedalias;
