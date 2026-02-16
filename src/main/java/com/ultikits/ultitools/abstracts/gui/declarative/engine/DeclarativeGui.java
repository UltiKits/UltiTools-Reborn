package com.ultikits.ultitools.abstracts.gui.declarative.engine;

import com.ultikits.ultitools.abstracts.gui.declarative.core.BuildContext;
import com.ultikits.ultitools.abstracts.gui.declarative.core.Element;
import com.ultikits.ultitools.abstracts.gui.declarative.core.Widget;
import mc.obliviate.inventory.Gui;
import mc.obliviate.inventory.Icon;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.jetbrains.annotations.NotNull;

/**
 * DeclarativeGui 是声明式 GUI 框架的基类。
 * <p>
 * 它继承自 obliviate-invs 的 {@link Gui}，提供声明式 UI 的能力。
 * 子类只需要实现 {@link #build(BuildContext)} 方法，返回 Widget 树即可。
 * <p>
 * <b>使用示例：</b>
 * <pre>{@code
 * public class ShopPage extends DeclarativeGui {
 *     private final List<ItemStack> items;
 *     
 *     public ShopPage(Player player, List<ItemStack> items) {
 *         super(player, "shop", "Shop", 6);
 *         this.items = items;
 *     }
 *     
 *     @Override
 *     public Widget build(BuildContext context) {
 *         return Column.builder()
 *             .children(
 *                 // 标题行
 *                 Center.builder()
 *                     .child(TextDisplay.title("Item Shop"))
 *                     .build(),
 *                 
 *                 // 物品网格
 *                 GridView.builder()
 *                     .items(items)
 *                     .itemBuilder(item -> ItemButton.builder()
 *                         .item(item)
 *                         .onClick(() -> buyItem(item))
 *                         .build())
 *                     .build(),
 *                 
 *                 // 分页控制
 *                 Row.builder()
 *                     .children(
 *                         PrevPageButton.builder().build(),
 *                         PageIndicator.builder().build(),
 *                         NextPageButton.builder().build()
 *                     )
 *                     .build()
 *             )
 *             .build();
 *     }
 *     
 *     private void buyItem(ItemStack item) {
 *         // 购买逻辑
 *         player.sendMessage("Bought " + item.getType());
 *     }
 * }
 * }</pre>
 *
 * @author UltiTools Team
 * @version 1.0.0
 * @since 6.2.0
 */
public abstract class DeclarativeGui extends Gui {

    @NotNull
    protected final String id;
    private final GuiRenderer renderer;
    private boolean initialized = false;

    /**
     * 创建 DeclarativeGui。
     *
     * @param player 玩家
     * @param id     GUI ID
     * @param title  标题
     * @param rows   行数
     */
    public DeclarativeGui(@NotNull Player player, @NotNull String id, @NotNull String title, int rows) {
        super(player, id, title, rows);
        this.id = id;
        this.renderer = new GuiRenderer(this, player);
    }

    /**
     * 创建 DeclarativeGui。
     *
     * @param player        玩家
     * @param id            GUI ID
     * @param title         标题
     * @param inventoryType 背包类型
     */
    public DeclarativeGui(@NotNull Player player, @NotNull String id, @NotNull String title, 
                          @NotNull InventoryType inventoryType) {
        super(player, id, title, inventoryType);
        this.id = id;
        this.renderer = new GuiRenderer(this, player);
    }

    /**
     * 创建 DeclarativeGui，使用 Component 标题。
     *
     * @param player 玩家
     * @param id     GUI ID
     * @param title  标题（Component）
     * @param rows   行数
     */
    public DeclarativeGui(@NotNull Player player, @NotNull String id, @NotNull Component title, int rows) {
        super(player, id, title, rows);
        this.id = id;
        this.renderer = new GuiRenderer(this, player);
    }

    /**
     * 创建 DeclarativeGui，使用 Component 标题。
     *
     * @param player        玩家
     * @param id            GUI ID
     * @param title         标题（Component）
     * @param inventoryType 背包类型
     */
    public DeclarativeGui(@NotNull Player player, @NotNull String id, @NotNull Component title, 
                          @NotNull InventoryType inventoryType) {
        super(player, id, title, inventoryType);
        this.id = id;
        this.renderer = new GuiRenderer(this, player);
    }

    /**
     * 构建 Widget 树。
     * <p>
     * 子类必须实现此方法，返回描述 UI 的 Widget 树。
     * 每次状态变化时，这个方法会被重新调用。
     *
     * @param context 构建上下文，包含玩家、GUI 配置等信息
     * @return Widget 树
     */
    @NotNull
    public abstract Widget build(@NotNull BuildContext context);

    @Override
    public final void onOpen(@NotNull InventoryOpenEvent event) {
        if (!initialized) {
            // 初始化渲染器
            BuildContext context = BuildContext.root(player, getId(), getSize() / 9);
            Widget rootWidget = build(context);
            renderer.initialize(rootWidget, context);
            initialized = true;
        }

        // 调用子类的钩子
        onGuiOpen(event);
    }

    @Override
    public final void onClose(@NotNull InventoryCloseEvent event) {
        // 调用子类的钩子
        onGuiClose(event);

        // 清理资源
        renderer.dispose();
        initialized = false;
    }

    @Override
    public final boolean onClick(@NotNull InventoryClickEvent event) {
        // 传递给渲染器处理
        renderer.handleClick(event);

        // 调用子类的钩子
        return onGuiClick(event);
    }

    /**
     * 标记需要重建。
     * <p>
     * 通常在子类中，当数据变化时调用此方法触发 UI 更新。
     */
    protected void markNeedsBuild() {
        if (initialized) {
            renderer.scheduleBuild();
        }
    }

    /**
     * 设置状态并触发重建。
     * <p>
     * 这是 Flutter 风格的状态管理方式。在回调中修改状态，
     * 框架会自动触发重建。
     * <p>
     * <b>使用示例：</b>
     * <pre>{@code
     * setState(() -> {
     *     counter++;          // 修改状态
     *     selectedItem = item;
     * });
     * }</pre>
     *
     * @param action 状态修改操作
     */
    protected void setState(@NotNull Runnable action) {
        action.run();
        markNeedsBuild();
    }

    /**
     * GUI 打开时的钩子方法。
     * <p>
     * 子类可以重写此方法执行额外的初始化工作。
     *
     * @param event 打开事件
     */
    protected void onGuiOpen(@NotNull InventoryOpenEvent event) {
        // 子类重写
    }

    /**
     * GUI 关闭时的钩子方法。
     * <p>
     * 子类可以重写此方法执行清理工作。
     *
     * @param event 关闭事件
     */
    protected void onGuiClose(@NotNull InventoryCloseEvent event) {
        // 子类重写
    }

    /**
     * GUI 点击时的钩子方法。
     * <p>
     * 注意：点击事件首先由声明式框架处理，然后才调用此方法。
     *
     * @param event 点击事件
     * @return 是否取消默认行为（true 取消）
     */
    protected boolean onGuiClick(@NotNull InventoryClickEvent event) {
        // 子类重写
        return true;
    }

    /**
     * 获取渲染器。
     *
     * @return GuiRenderer
     */
    @NotNull
    protected GuiRenderer getRenderer() {
        return renderer;
    }

    /**
     * 检查是否已初始化。
     *
     * @return 如果已初始化返回 true
     */
    public boolean isInitialized() {
        return initialized;
    }

    /**
     * 获取玩家。
     *
     * @return 玩家
     */
    @NotNull
    public Player getPlayer() {
        return player;
    }
}

