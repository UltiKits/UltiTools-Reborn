package com.ultikits.ultitools.abstracts.gui;

import java.util.function.Consumer;

import javax.annotation.Nullable;

import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.entities.Colors;
import com.ultikits.ultitools.utils.XVersionUtils;

import mc.obliviate.inventory.Gui;
import mc.obliviate.inventory.Icon;
import net.kyori.adventure.text.Component;

/**
 * Base class for inventory GUI pages with common functionality.
 * Uses Template Method pattern to allow customization while providing standard behaviors.
 * <p>
 * 带有通用功能的背包 GUI 页面基类。
 * 使用模板方法模式允许自定义，同时提供标准行为。
 *
 * @author wisdomme
 * @version 2.0.0
 * @since 6.2.0
 */
public abstract class BaseInventoryPage extends Gui {
    
    /**
     * Default slot for the last row background
     */
    protected static final int DEFAULT_BOTTOM_ROW = -1;
    
    /**
     * Whether to show the bottom toolbar
     */
    private boolean showBottomToolbar = true;
    
    /**
     * Custom close handler
     */
    @Nullable
    private Consumer<InventoryCloseEvent> closeHandler;
    
    // Constructors matching Gui class
    
    public BaseInventoryPage(@NotNull Player player, @NotNull String id, String title, int rows) {
        super(player, id, title, rows);
    }
    
    public BaseInventoryPage(@NotNull Player player, @NotNull String id, String title, InventoryType inventoryType) {
        super(player, id, title, inventoryType);
    }
    
    public BaseInventoryPage(@NotNull Player player, @NotNull String id, Component title, int rows) {
        super(player, id, title, rows);
    }
    
    public BaseInventoryPage(@NotNull Player player, @NotNull String id, Component title, InventoryType inventoryType) {
        super(player, id, title, inventoryType);
    }
    
    /**
     * Template method called when the GUI is opened.
     * Subclasses should override {@link #setupContent(InventoryOpenEvent)} to add content.
     * <p>
     * GUI 打开时调用的模板方法。
     * 子类应重写 {@link #setupContent(InventoryOpenEvent)} 来添加内容。
     */
    @Override
    public final void onOpen(InventoryOpenEvent event) {
        // Setup bottom toolbar if enabled
        if (showBottomToolbar) {
            setupBottomToolbar();
        }
        
        // Call subclass implementation
        setupContent(event);
        
        // Post-setup hook
        afterSetup(event);
    }
    
    /**
     * Sets up the GUI content. Override this method to add custom content.
     * <p>
     * 设置 GUI 内容。重写此方法以添加自定义内容。
     *
     * @param event the inventory open event
     */
    protected abstract void setupContent(InventoryOpenEvent event);
    
    /**
     * Hook called after setup is complete. Override for additional customization.
     * <p>
     * 设置完成后调用的钩子。重写以进行额外自定义。
     *
     * @param event the inventory open event
     */
    protected void afterSetup(InventoryOpenEvent event) {
        // Override in subclass if needed
    }
    
    /**
     * Sets up the standard bottom toolbar with gray background.
     * <p>
     * 设置带有灰色背景的标准底部工具栏。
     */
    protected void setupBottomToolbar() {
        Icon background = createBackgroundIcon();
        fillRow(background, getSize() / 9 - 1);
    }
    
    /**
     * Creates the background icon for the toolbar.
     * Override to customize the background.
     * <p>
     * 创建工具栏的背景图标。
     * 重写以自定义背景。
     *
     * @return the background icon
     */
    protected Icon createBackgroundIcon() {
        ItemStack glass = XVersionUtils.getColoredPlaneGlass(Colors.GRAY);
        Icon icon = new Icon(glass);
        icon.setName(" ");
        return icon;
    }
    
    /**
     * Creates an action button with the specified color and name.
     * <p>
     * 使用指定的颜色和名称创建操作按钮。
     *
     * @param color   the button color
     * @param name    the button name
     * @param onClick the click handler
     * @return the created icon
     */
    protected Icon createActionButton(Colors color, String name, Consumer<InventoryClickEvent> onClick) {
        ItemStack glass = XVersionUtils.getColoredPlaneGlass(color);
        Icon icon = new Icon(glass);
        icon.setName(name);
        if (onClick != null) {
            icon.onClick(onClick::accept);
        }
        return icon;
    }
    
    /**
     * Adds an item to the last row at the specified column.
     * Column 0 is the leftmost slot.
     * <p>
     * 在最后一行的指定列添加项目。
     * 列 0 是最左边的槽位。
     *
     * @param column the column (0-8 for 9-slot rows)
     * @param icon   the icon to add
     */
    protected void addToBottomRow(int column, Icon icon) {
        int lastRowStart = getSize() - 9;
        addItem(lastRowStart + column, icon);
    }
    
    /**
     * Gets the slot number at the center of the last row.
     * <p>
     * 获取最后一行中心的槽位编号。
     *
     * @return the center slot of the last row
     */
    protected int getBottomCenterSlot() {
        return getSize() - 5;
    }
    
    /**
     * Gets the slot number at the specified position from the end.
     * <p>
     * 获取从末尾开始指定位置的槽位编号。
     *
     * @param fromEnd position from the end (1 = last slot)
     * @return the slot number
     */
    protected int getSlotFromEnd(int fromEnd) {
        return getSize() - fromEnd;
    }
    
    /**
     * Sets whether to show the bottom toolbar.
     * <p>
     * 设置是否显示底部工具栏。
     *
     * @param show true to show, false to hide
     * @return this page for method chaining
     */
    public BaseInventoryPage setShowBottomToolbar(boolean show) {
        this.showBottomToolbar = show;
        return this;
    }
    
    /**
     * Sets a custom close handler.
     * <p>
     * 设置自定义关闭处理器。
     *
     * @param handler the close handler
     * @return this page for method chaining
     */
    public BaseInventoryPage onClose(Consumer<InventoryCloseEvent> handler) {
        this.closeHandler = handler;
        return this;
    }
    
    @Override
    public void onClose(InventoryCloseEvent event) {
        if (closeHandler != null) {
            closeHandler.accept(event);
        }
        super.onClose(event);
    }
    
    /**
     * Refreshes the GUI by re-opening it.
     * <p>
     * 通过重新打开 GUI 来刷新它。
     */
    public void refresh() {
        open();
    }
    
    /**
     * Fills a rectangular area with the specified icon.
     * <p>
     * 使用指定的图标填充矩形区域。
     *
     * @param icon      the icon to fill with
     * @param startSlot the starting slot (top-left)
     * @param endSlot   the ending slot (bottom-right)
     */
    protected void fillArea(Icon icon, int startSlot, int endSlot) {
        int startRow = startSlot / 9;
        int startCol = startSlot % 9;
        int endRow = endSlot / 9;
        int endCol = endSlot % 9;
        
        for (int row = startRow; row <= endRow; row++) {
            int colStart = (row == startRow) ? startCol : 0;
            int colEnd = (row == endRow) ? endCol : 8;
            
            for (int col = colStart; col <= colEnd; col++) {
                addItem(row * 9 + col, icon);
            }
        }
    }
    
    /**
     * Fills the border of the inventory with the specified icon.
     * <p>
     * 使用指定的图标填充背包的边框。
     *
     * @param icon the icon to use for the border
     */
    protected void fillBorder(Icon icon) {
        int rows = getSize() / 9;
        
        // Top and bottom rows
        for (int col = 0; col < 9; col++) {
            addItem(col, icon);
            addItem((rows - 1) * 9 + col, icon);
        }
        
        // Left and right columns
        for (int row = 1; row < rows - 1; row++) {
            addItem(row * 9, icon);
            addItem(row * 9 + 8, icon);
        }
    }
    
    /**
     * Gets the content area slots (excluding the last row if toolbar is shown).
     * <p>
     * 获取内容区域槽位（如果显示工具栏则排除最后一行）。
     *
     * @return array of content slot indices
     */
    protected int[] getContentSlots() {
        int contentSize = showBottomToolbar ? getSize() - 9 : getSize();
        int[] slots = new int[contentSize];
        for (int i = 0; i < contentSize; i++) {
            slots[i] = i;
        }
        return slots;
    }
}
