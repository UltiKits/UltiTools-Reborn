package com.ultikits.ultitools.abstracts.gui.declarative.engine;

import com.ultikits.ultitools.abstracts.gui.declarative.core.BuildContext;
import com.ultikits.ultitools.abstracts.gui.declarative.core.Widget;
import mc.obliviate.inventory.Gui;
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
            // 初始化渲染器。传入的是一个 Supplier，而不是构建好的 Widget——
            // GuiRenderer 会在每一帧的开头重新调用 build(context)，这样状态变化才能
            // 真正重新派生出 Widget 树（D-09 item 1；GuiRenderer 的这半改动来自 05-11
            // 计划的 Task 1，这里的调用点更新属于 Task 2 的范围，为了保持编译通过
            // 提前完成）。
            BuildContext context = BuildContext.root(player, getId(), getSize() / 9);
            renderer.initialize(() -> build(context), context);
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

    /**
     * This is the framework's own bounds-check site (D-09 item 4), not a post-filter hook. A
     * subclass overriding this method needs to know that obliviate-invs' {@code InvListener}
     * calls this method unconditionally, BEFORE it applies any bounds check of its own — so
     * every click reaches here, including a click in the player's own inventory. The actual
     * check happens one call down, in {@link GuiRenderer#handleClick}: it rejects (returns
     * without dispatching) any click whose raw slot falls outside the GUI's own inventory, so
     * a click on the player's own inventory can never reach a GUI handler here.
     * <p>
     * 这是框架自己做越界检查的地方（D-09 item 4），不是一个"后置过滤"的钩子。子类如果重写这个
     * 方法，需要知道 obliviate-invs 的 {@code InvListener} 是无条件调用这个方法的——在它自己做
     * 任何过滤之前——所以每一次点击都会到达这里，包括玩家点击自己背包的情形。真正的越界检查
     * 在下一层，{@link GuiRenderer#handleClick} 里：它会拒绝（直接返回、不派发）任何原始槽位
     * 落在 GUI 自身 Inventory 范围之外的点击，所以玩家点击自己背包永远不会在这里触发 GUI 的
     * 处理器。
     *
     * @param event 点击事件
     * @return 见 {@link #onGuiClick(InventoryClickEvent)}
     */
    @Override
    public final boolean onClick(@NotNull InventoryClickEvent event) {
        // 传递给渲染器处理——渲染器自己做越界检查，见上面的 javadoc 和
        // GuiRenderer.handleClick。
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
     * <p>
     * <b>返回值语义与直觉相反，务必看清：</b>返回 {@code false} 表示保持事件被取消，
     * 玩家<b>拿不走</b>格子里的物品；返回 {@code true} 表示放行，玩家<b>可以取走</b>物品。
     * 这一层语义来自 obliviate-invs —— 其 {@code InvListener} 在 {@code Gui.onClick}
     * 返回 true 时调用 {@code setCancelled(false)}，返回 false 时调用
     * {@code setCancelled(true)}。默认值 {@code false} 与库基类 {@code Gui.onClick}
     * 的默认值保持一致，也是安全的一侧。
     * <p>
     * The return value reads backwards, so read it carefully: returning {@code false}
     * keeps the event cancelled and the player <b>cannot</b> take the clicked item;
     * returning {@code true} lets the click through and the item <b>can</b> be taken.
     * The semantics come from obliviate-invs, whose {@code InvListener} calls
     * {@code setCancelled(false)} when {@code Gui.onClick} returns true and
     * {@code setCancelled(true)} when it returns false. The default of {@code false}
     * matches the library base class and is the safe side.
     *
     * @param event 点击事件
     * @return {@code false} 保持事件取消（默认，物品拿不走）；{@code true} 放行
     */
    protected boolean onGuiClick(@NotNull InventoryClickEvent event) {
        // 子类重写。默认保持事件取消——这是安全的一侧。
        // Subclasses override. The default keeps the event cancelled, which is the safe side.
        return false;
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

