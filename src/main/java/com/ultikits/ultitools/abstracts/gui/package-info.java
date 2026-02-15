/**
 * Improved GUI page classes using Template Method pattern.
 * <p>
 * This package provides a cleaner GUI framework:
 * <ul>
 *   <li>{@link com.ultikits.ultitools.abstracts.gui.BaseInventoryPage} - Base class with common functionality</li>
 *   <li>{@link com.ultikits.ultitools.abstracts.gui.BasePaginationPage} - Pagination support</li>
 *   <li>{@link com.ultikits.ultitools.abstracts.gui.BaseConfirmationPage} - OK/Cancel dialogs with builder</li>
 * </ul>
 * 
 * <h2>Migration from Old GUI Classes:</h2>
 * <pre>{@code
 * // Old way - PagingPage (deprecated)
 * public class MyPage extends PagingPage {
 *     public List<Icon> setAllItems() { return items; }
 * }
 * 
 * // New way - BasePaginationPage
 * public class MyPage extends BasePaginationPage {
 *     @Override
 *     protected List<Icon> provideItems() { return items; }
 * }
 * 
 * // Old way - OkCancelPage (deprecated)
 * public class MyDialog extends OkCancelPage {
 *     public void onOk(InventoryClickEvent e) { }
 *     public void onCancel(InventoryClickEvent e) { }
 * }
 * 
 * // New way - BaseConfirmationPage with builder
 * BaseConfirmationPage.builder(player)
 *     .title("Confirm Action")
 *     .onConfirm(e -> doAction())
 *     .onCancel(e -> {})
 *     .open();
 * }</pre>
 * 
 * <h2>Benefits:</h2>
 * <ul>
 *   <li>Template Method pattern for consistent structure</li>
 *   <li>Builder pattern for quick dialog creation</li>
 *   <li>Reduced code duplication</li>
 *   <li>Better hook points for customization</li>
 * </ul>
 *
 * @since 6.2.0
 * @see com.ultikits.ultitools.abstracts.guis.PagingPage
 * @see com.ultikits.ultitools.abstracts.guis.OkCancelPage
 */
package com.ultikits.ultitools.abstracts.gui;
