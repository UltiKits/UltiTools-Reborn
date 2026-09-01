package com.ultikits.ultitools.abstracts.guis;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.entities.Colors;
import com.ultikits.ultitools.utils.XVersionUtils;
import mc.obliviate.inventory.Gui;
import mc.obliviate.inventory.Icon;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.jetbrains.annotations.NotNull;

import static com.ultikits.ultitools.utils.MessageUtils.coloredMsg;

/**
 * This abstract class represents a GUI page with OK and Cancel options.
 * It extends the Gui class from mc.obliviate.inventory package.
 * <p>
 * 这个抽象类代表了一个带有OK和Cancel选项的GUI页面。它继承了mc.obliviate.inventory包中的Gui类。
 *
 * @see com.ultikits.ultitools.abstracts.gui.BaseConfirmationPage
 * @deprecated Use {@link com.ultikits.ultitools.abstracts.gui.BaseConfirmationPage} instead,
 *             which provides better template method pattern, builder support, and code organization.
 *             <p>
 *             请使用 {@link com.ultikits.ultitools.abstracts.gui.BaseConfirmationPage}，
 *             它提供了更好的模板方法模式、构建器支持和代码组织。
 * @since 6.2.0 deprecated
 * @removeIn 6.3.0
 */
@Deprecated(since = "6.2.0", forRemoval = true)
public abstract class OkCancelPage extends Gui {

    /**
     * Constructor with player, id, title and number of rows as parameters.
     * <p>
     * 构造函数，参数为玩家、id、标题和行数。
     *
     * @param player The player who will see the GUI.
     * @param id     The id of the GUI.
     * @param title  The title of the GUI.
     * @param rows   The number of rows in the GUI.
     */
    public OkCancelPage(@NotNull Player player, @NotNull String id, String title, int rows) {
        super(player, id, title, rows);
    }

    /**
     * Constructor with player, id, title and inventory type as parameters.
     * <p>
     * 构造函数，参数为玩家、id、标题和背包类型。
     *
     * @param player        The player who will see the GUI.
     * @param id            The id of the GUI.
     * @param title         The title of the GUI.
     * @param inventoryType The type of the inventory.
     */
    public OkCancelPage(@NotNull Player player, @NotNull String id, String title, InventoryType inventoryType) {
        super(player, id, title, inventoryType);
    }

    /**
     * Constructor with player, id, title component and number of rows as parameters.
     * <p>
     * 构造函数，参数为玩家、id、标题组件和行数。
     *
     * @param player The player who will see the GUI.
     * @param id     The id of the GUI.
     * @param title  The title of the GUI as a Component.
     * @param rows   The number of rows in the GUI.
     */
    public OkCancelPage(@NotNull Player player, @NotNull String id, Component title, int rows) {
        super(player, id, title, rows);
    }

    /**
     * Constructor with player, id, title component and inventory type as parameters.
     * <p>
     * 构造函数，参数为玩家、id、标题组件和背包类型。
     *
     * @param player        The player who will see the GUI.
     * @param id            The id of the GUI.
     * @param title         The title of the GUI as a Component.
     * @param inventoryType The type of the inventory.
     */
    public OkCancelPage(@NotNull Player player, @NotNull String id, Component title, InventoryType inventoryType) {
        super(player, id, title, inventoryType);
    }

    /**
     * This method is called when the GUI is opened.
     * It sets up the OK and Cancel options in the GUI.
     * <p>
     * 当GUI被打开时调用此方法。此方法在GUI中设置了OK和Cancel选项。
     *
     * @param event The InventoryOpenEvent that triggers this method. <p> 触发此方法的InventoryOpenEvent。
     */
    @Override
    public void onOpen(InventoryOpenEvent event) {
        Icon lastRowBackground = new Icon(XVersionUtils.getColoredPlaneGlass(Colors.GRAY));
        lastRowBackground.setName(" ");
        this.fillRow(lastRowBackground, getSize() / 9 - 1);
        Icon ok = new Icon(XVersionUtils.getColoredPlaneGlass(Colors.GREEN));
        ok.setName(coloredMsg(getOkName()));
        ok.onClick((e) -> {
            onOk(e);
            player.closeInventory();
        });
        Icon cancel = new Icon(XVersionUtils.getColoredPlaneGlass(Colors.RED));
        cancel.setName(coloredMsg(getCancelName()));
        cancel.onClick((e) -> {
            onCancel(e);
            player.closeInventory();
        });
        this.addItem(getLastSlot() - 3, ok);
        this.addItem(getLastSlot() - 5, cancel);
    }

    public String getOkName() {
        return UltiTools.getInstance().i18n("OK");
    }

    public String getCancelName() {
        return UltiTools.getInstance().i18n("取消");
    }

    /**
     * This method is called when the OK option is clicked.
     * It should be overridden in subclasses to provide specific functionality.
     * <p>
     * 当点击OK选项时调用此方法。此方法应该在子类中被重写以提供特定的功能。
     *
     * @param clickEvent The InventoryClickEvent that triggers this method. <p> 触发此方法的InventoryClickEvent。
     */
    public abstract void onOk(InventoryClickEvent clickEvent);

    /**
     * This method is called when the Cancel option is clicked.
     * It should be overridden in subclasses to provide specific functionality.
     * <p>
     * 当点击Cancel选项时调用此方法。此方法应该在子类中被重写以提供特定的功能。
     *
     * @param clickEvent The InventoryClickEvent that triggers this method. <p> 触发此方法的InventoryClickEvent。
     */
    public abstract void onCancel(InventoryClickEvent clickEvent);
}
