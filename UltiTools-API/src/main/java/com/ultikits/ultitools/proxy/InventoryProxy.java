package com.ultikits.ultitools.proxy;

import cn.hutool.core.util.IdUtil;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.entities.Buttons;
import com.ultikits.ultitools.entities.Colors;
import com.ultikits.ultitools.entities.ViewType;
import com.ultikits.ultitools.interfaces.InventoryAPI;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.Map;

/**
 * The type Inventory manager.
 */
public class InventoryProxy implements InventoryAPI {

    private final Player owner;
    private final int slots;
    private final String groupTitle;
    private final String uuid = IdUtil.fastSimpleUUID();
    private Inventory inventory;
    private int pageNumber = 0;
    private String title;
    private boolean isPageButtonEnabled = false;
    private boolean isOkCancelEnabled = false;
    private boolean isLastLineDisabled = false;
    private boolean autoAddPage = true;
    private Colors backGroundColor;
    private Buttons middleButton = Buttons.QUIT;
    private InventoryProxy nextPage;
    private InventoryProxy lastPage;

    /**
     * Instantiates a new Inventory manager.
     *
     * @param owner the owner
     * @param slots the slots
     * @param groupTitle the groupTitle
     */
    public InventoryProxy(Player owner, int slots, String groupTitle) {
        this.owner = owner;
        this.slots = slots;
        this.groupTitle = groupTitle;
        create();
    }

    private InventoryProxy(Player owner, int slots, String groupTitle, int pageNumber) {
        this.owner = owner;
        this.slots = slots;
        this.pageNumber = pageNumber;
        this.title = groupTitle + String.format(" " + UltiTools.getInstance().i18n("第%d页"), pageNumber);
        this.groupTitle = groupTitle;
        this.isPageButtonEnabled = true;
        this.isLastLineDisabled = true;
        create();
    }

    /**
     * Instantiates a new Inventory manager.
     *
     * @param owner the owner
     * @param slots the slots
     * @param block the block
     * @param groupTitle the groupTitle
     */
    public InventoryProxy(Player owner, int slots, String groupTitle, boolean block) {
        this.owner = owner;
        this.slots = slots;
        this.groupTitle = groupTitle;
        if (!block) {
            create();
        }
    }

    @Override
    public void create() {
        if (title == null) {
            title = groupTitle;
        }
        inventory = Bukkit.createInventory(owner, slots, title);
        if (isPageButtonEnabled) {
            setPageButtons();
        } else if (isOkCancelEnabled) {
            setOkCancelButtons();
        } else if (isLastLineDisabled) {
            fillLastLine();
            forceSetItem(inventory.getSize() - 5, middleButton.getItemStack());
        }
        if (this.backGroundColor != null) {
            setBackgroundColor(this.backGroundColor);
        }
    }

    @Override
    public void presetPage(ViewType type) {
        this.isLastLineDisabled = type.isLastLineEnabled();
        if (isLastLineDisabled) {
            this.isPageButtonEnabled = type.isPageButtonEnabled();
            this.isOkCancelEnabled = type.isOkCancelEnabled();
            if (isPageButtonEnabled) {
                this.autoAddPage = type.isAutoAddPage();
                this.pageNumber = type.getPageNumber();
                this.middleButton = type.getMiddleButton();
            }
        }
        this.backGroundColor = type.getBackGroundColor();
    }

    @Override
    public void setItem(int position, ItemStack item) {
        if (position >= this.getSize()) {
            return;
        }
        if (position < 0) {
            return;
        }
        if (inventory.getItem(position) == null) {
            inventory.setItem(position, item);
        } else if (isBackGround(inventory.getItem(position))) {
            forceSetItem(position, item);
        } else {
            setItem(position + 1, item);
        }
    }

    @Override
    public void forceSetItem(int position, ItemStack item) throws IndexOutOfBoundsException {
        inventory.setItem(position, item);
    }

    @Override
    public Map<Integer, ItemStack> addItem(ItemStack item) {
        clearBackGround();
        Map<Integer, ItemStack> items = inventory.addItem(item);
        if (items.values().size() == 0) {
            setBackgroundColor(this.backGroundColor);
            return items;
        }
        if (!isPageButtonEnabled) {
            return items;
        }
        if (nextPage == null && autoAddPage) {
            nextPage = new InventoryProxy(this.owner, this.slots, getGroupTitle(), getPageNumber() + 1);
            nextPage.setLastPage(this);
            if (this.backGroundColor != null) {
                nextPage.setBackgroundColor(this.backGroundColor);
            }
            UltiTools.getInstance().getViewManager().registerView(nextPage);
            nextPage.addItem(item);
        } else if (nextPage != null) {
            nextPage.addItem(item);
        }
        setBackgroundColor(this.backGroundColor);
        return items;
    }

    @Override
    public Map<Integer, ItemStack> addItem(ItemStackProxy itemStackProxy) {
        return addItem(itemStackProxy.getItem());
    }


    @Override
    public Inventory getInventory() {
        return inventory;
    }

    @Override
    public String getTitle() {
        return title;
    }

    @Override
    public String getGroupTitle() {
        if (groupTitle == null) {
            return title;
        }
        return groupTitle;
    }

    @Override
    public int getSize() {
        if (isLastLineDisabled) {
            return inventory.getSize() - 9;
        }
        return inventory.getSize();
    }

    private void setPageButtons() {
        this.title = groupTitle + " " + String.format(UltiTools.getInstance().i18n("第%d页"), pageNumber);
        inventory = Bukkit.createInventory(owner, slots, title);
        fillLastLine();
        try {
            ItemStackProxy back = new ItemStackProxy(Buttons.PREVIOUS.getItemStack(), Buttons.PREVIOUS.getName());
            ItemStackProxy quit = new ItemStackProxy(middleButton.getItemStack(), middleButton.getName());
            ItemStackProxy next = new ItemStackProxy(Buttons.NEXT.getItemStack(), Buttons.NEXT.getName());
            this.forceSetItem(getSize() + 3, back.getItem());
            this.forceSetItem(getSize() + 4, quit.getItem());
            this.forceSetItem(getSize() + 5, next.getItem());
        } catch (IndexOutOfBoundsException ignored) {
        }
    }

    private void setOkCancelButtons() {
        fillLastLine();
        try {
            ItemStackProxy ok = new ItemStackProxy(Buttons.OK.getItemStack(), Buttons.OK.getName());
            ItemStackProxy cancel = new ItemStackProxy(Buttons.CANCEL.getItemStack(), Buttons.CANCEL.getName());
            this.forceSetItem(getSize() + 3, ok.getItem());
            this.forceSetItem(getSize() + 5, cancel.getItem());
        } catch (IndexOutOfBoundsException ignored) {
        }
    }

    private void fillLastLine() {
        ItemStack blackGlass = UltiTools.getInstance().getVersionWrapper().getColoredPlaneGlass(Colors.BLACK);
        ItemStackProxy blank = new ItemStackProxy(blackGlass, "");
        for (int i = getSize(); i < inventory.getSize(); i++) {
            if (inventory.getItem(i) != null) {
                continue;
            }
            inventory.setItem(i, blank.getItem());
        }
    }

    @Override
    public int getPageNumber() {
        return pageNumber;
    }

    @Override
    public boolean isPageButtonEnabled() {
        return isPageButtonEnabled;
    }

    @Override
    public void setPageButtonEnabled(boolean isPageButtonEnabled) {
        this.isPageButtonEnabled = isPageButtonEnabled;
    }

    @Override
    public boolean isLastLineDisabled() {
        return isLastLineDisabled;
    }

    @Override
    public void setLastLineDisabled(boolean disabled) {
        this.isLastLineDisabled = disabled;
    }

    @Override
    public void clearView() {
        for (int i = 0; i < getSize(); i++) {
            forceSetItem(i, null);
        }
    }

    @Override
    public void setBackgroundColor(Colors backgroundColor) {
        if (backgroundColor == null) {
            return;
        }
        ItemStackProxy itemStackProxy = new ItemStackProxy(UltiTools.getInstance().getVersionWrapper().getColoredPlaneGlass(backgroundColor), "");
        while (inventory.firstEmpty() > -1) {
            forceSetItem(inventory.firstEmpty(), itemStackProxy.getItem());
        }
        this.backGroundColor = backgroundColor;
    }

    @Override
    public void clearBackGround() {
        for (int i = 0; i < getSize(); i++) {
            if (isBackGround(inventory.getItem(i))) {
                forceSetItem(i, null);
            }
        }
    }

    @Override
    public boolean isBackGround(ItemStack item) {
        if (item == null) return false;
        if (this.backGroundColor == null) return false;
        if (item.getType() == UltiTools.getInstance().getVersionWrapper().getColoredPlaneGlass(Colors.BLACK).getType())
            return true;
        return item.getType().equals(UltiTools.getInstance().getVersionWrapper().getColoredPlaneGlass(this.backGroundColor).getType()) && ChatColor.stripColor(item.getItemMeta().getDisplayName()).equals("");
    }

    @Override
    public void setMiddleButton(Buttons middleButton) {
        this.middleButton = middleButton;
    }

    @Override
    public String getUuid() {
        return uuid;
    }

    public InventoryProxy getLastPage() {
        return lastPage;
    }

    public void setLastPage(InventoryProxy lastPage) {
        this.lastPage = lastPage;
    }

    public InventoryProxy getNextPage() {
        return nextPage;
    }

    public void setNextPage(InventoryProxy nextPage) {
        this.nextPage = nextPage;
    }

    public Player getOwner(){
        return owner;
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof InventoryProxy)){
            return false;
        }
        return this.uuid.equals(((InventoryProxy) obj).uuid);
    }

    @Override
    public String toString() {
        return "{"
                + "\"groupTitle\":\""
                + groupTitle + '\"'
                + ",\"uuid\":\""
                + uuid + '\"'
                + ",\"pageNumber\":"
                + pageNumber
                + ",\"title\":\""
                + title + '\"'
                + "}";
    }
}
