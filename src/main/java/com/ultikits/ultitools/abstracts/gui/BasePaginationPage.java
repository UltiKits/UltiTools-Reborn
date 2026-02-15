package com.ultikits.ultitools.abstracts.gui;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.entities.Colors;
import mc.obliviate.inventory.Icon;
import mc.obliviate.inventory.pagination.PaginationManager;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.jetbrains.annotations.NotNull;

import java.util.List;

import static com.ultikits.ultitools.utils.MessageUtils.info;
import static com.ultikits.ultitools.utils.MessageUtils.warning;

/**
 * Base class for paginated inventory pages.
 * Provides automatic pagination with next/previous buttons.
 * <p>
 * 分页背包页面的基类。
 * 提供带有下一页/上一页按钮的自动分页。
 *
 * @author wisdomme
 * @version 2.0.0
 * @since 6.2.0
 */
public abstract class BasePaginationPage extends BaseInventoryPage {
    
    private final PaginationManager paginationManager;
    
    /**
     * Default column for the "previous" button (0-based)
     */
    protected static final int PREV_BUTTON_COLUMN = 3;
    
    /**
     * Default column for the "next" button (0-based)
     */
    protected static final int NEXT_BUTTON_COLUMN = 5;
    
    public BasePaginationPage(@NotNull Player player, @NotNull String id, String title, int rows) {
        super(player, id, title, rows);
        this.paginationManager = new PaginationManager(this);
        initPagination();
    }
    
    public BasePaginationPage(@NotNull Player player, @NotNull String id, String title, InventoryType inventoryType) {
        super(player, id, title, inventoryType);
        this.paginationManager = new PaginationManager(this);
        initPagination();
    }
    
    public BasePaginationPage(@NotNull Player player, @NotNull String id, Component title, int rows) {
        super(player, id, title, rows);
        this.paginationManager = new PaginationManager(this);
        initPagination();
    }
    
    public BasePaginationPage(@NotNull Player player, @NotNull String id, Component title, InventoryType inventoryType) {
        super(player, id, title, inventoryType);
        this.paginationManager = new PaginationManager(this);
        initPagination();
    }
    
    /**
     * Initializes the pagination manager.
     * <p>
     * 初始化分页管理器。
     */
    private void initPagination() {
        paginationManager.registerPageSlotsBetween(0, getLastSlot() - 9);
    }
    
    @Override
    protected final void setupContent(InventoryOpenEvent event) {
        updatePaginatedContent();
    }
    
    /**
     * Updates the paginated content.
     * <p>
     * 更新分页内容。
     */
    protected void updatePaginatedContent() {
        // Clear existing items
        paginationManager.getItems().clear();
        
        // Get items from subclass
        List<Icon> items = provideItems();
        for (Icon item : items) {
            paginationManager.addItem(item);
        }
        
        // Setup navigation buttons
        setupNavigationButtons();
        
        // Update pagination
        paginationManager.update();
    }
    
    /**
     * Sets up the navigation buttons.
     * <p>
     * 设置导航按钮。
     */
    protected void setupNavigationButtons() {
        // Previous button
        Icon prevButton = createPreviousButton();
        addToBottomRow(PREV_BUTTON_COLUMN, prevButton);
        
        // Next button
        Icon nextButton = createNextButton();
        addToBottomRow(NEXT_BUTTON_COLUMN, nextButton);
    }
    
    /**
     * Creates the "previous page" button.
     * Override to customize.
     * <p>
     * 创建"上一页"按钮。
     * 重写以自定义。
     *
     * @return the previous button icon
     */
    protected Icon createPreviousButton() {
        Icon icon = createActionButton(Colors.GREEN, 
                info(UltiTools.getInstance().i18n("上一页")), 
                e -> {
                    if (!paginationManager.isFirstPage()) {
                        paginationManager.goPreviousPage();
                        updatePaginatedContent();
                    }
                });
        
        if (paginationManager.isFirstPage()) {
            icon.setLore(warning(UltiTools.getInstance().i18n("已经是第一页了")));
        }
        
        return icon;
    }
    
    /**
     * Creates the "next page" button.
     * Override to customize.
     * <p>
     * 创建"下一页"按钮。
     * 重写以自定义。
     *
     * @return the next button icon
     */
    protected Icon createNextButton() {
        Icon icon = createActionButton(Colors.GREEN, 
                info(UltiTools.getInstance().i18n("下一页")), 
                e -> {
                    if (!paginationManager.isLastPage()) {
                        paginationManager.goNextPage();
                        updatePaginatedContent();
                    }
                });
        
        if (paginationManager.isLastPage()) {
            icon.setLore(warning(UltiTools.getInstance().i18n("已经是最后一页了")));
        }
        
        return icon;
    }
    
    /**
     * Provides the items for pagination.
     * Override this method to provide the items to display.
     * <p>
     * 提供分页的项目。
     * 重写此方法以提供要显示的项目。
     *
     * @return list of icons to paginate
     */
    protected abstract List<Icon> provideItems();
    
    /**
     * Gets the pagination manager.
     * <p>
     * 获取分页管理器。
     *
     * @return the pagination manager
     */
    protected PaginationManager getPaginationManager() {
        return paginationManager;
    }
    
    /**
     * Gets the current page number (1-based).
     * <p>
     * 获取当前页码（从 1 开始）。
     *
     * @return the current page number
     */
    public int getCurrentPage() {
        return paginationManager.getCurrentPage() + 1;
    }
    
    /**
     * Gets the total number of pages.
     * <p>
     * 获取总页数。
     *
     * @return the total page count
     */
    public int getTotalPages() {
        return Math.max(1, paginationManager.getLastPage() + 1);
    }
    
    /**
     * Checks if there are more pages after the current one.
     * <p>
     * 检查当前页之后是否还有更多页。
     *
     * @return true if not on the last page
     */
    public boolean hasNextPage() {
        return !paginationManager.isLastPage();
    }
    
    /**
     * Checks if there are pages before the current one.
     * <p>
     * 检查当前页之前是否还有页。
     *
     * @return true if not on the first page
     */
    public boolean hasPreviousPage() {
        return !paginationManager.isFirstPage();
    }
    
    /**
     * Goes to a specific page.
     * <p>
     * 跳转到指定页。
     *
     * @param page the page number (1-based)
     */
    public void goToPage(int page) {
        int targetPage = Math.max(0, Math.min(page - 1, paginationManager.getLastPage()));
        while (paginationManager.getCurrentPage() < targetPage) {
            paginationManager.goNextPage();
        }
        while (paginationManager.getCurrentPage() > targetPage) {
            paginationManager.goPreviousPage();
        }
        updatePaginatedContent();
    }
}
