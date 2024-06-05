package com.ultikits.ultitools.interfaces;

import cn.hutool.core.lang.func.VoidFunc0;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.utils.MessageUtils;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.entity.Player;

public class ChatConfirm implements Confirm{
    private String confirmText;
    private String cancelText;

    private final Player player;
    private final String title;
    private final String description;
    private final VoidFunc0 onConfirm;
    private final VoidFunc0 onCancel;

    public ChatConfirm(Player player, String title, String description, VoidFunc0 onConfirm, VoidFunc0 onCancel) {
        this.player = player;
        this.title = title;
        this.description = description;
        this.onConfirm = onConfirm;
        this.onCancel = onCancel;
    }

    public ChatConfirm(Player player, String title, String description, String confirmText, String cancelText, VoidFunc0 onConfirm, VoidFunc0 onCancel) {
        this(player, title, description, onConfirm, onCancel);
        this.confirmText = confirmText;
        this.cancelText = cancelText;
    }

    @Override
    public void show() {
        TextComponent titleText = Component
                .text(title)
                .decorate(TextDecoration.BOLD)
                .color(TextColor.color(255, 222, 55));
        TextComponent descText = Component
                .text(description);
        TextComponent actionBtn = Component.empty()
                .append(Component
                        .text("[ " + getConfirmText() + " ]")
                        .color(TextColor.color(0, 255, 0))
                        .clickEvent(ClickEvent.callback((ignored) -> {
                            try {
                                onConfirm.call();
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        }))
                )
                .append(Component.text("     "))
                .append(Component
                        .text("[ " + getCancelText() + " ]")
                        .color(TextColor.color(255, 0, 0))
                        .clickEvent(ClickEvent.callback((ignored) -> {
                            try {
                                onCancel.call();
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        }))
                );

        MessageUtils.sendMessage(player, titleText);
        MessageUtils.sendMessage(player, descText);
        MessageUtils.sendMessage(player, actionBtn);
    }

    @Override
    public String getConfirmText() {
        return confirmText == null ? UltiTools.getInstance().i18n("OK") : confirmText;
    }

    @Override
    public String getCancelText() {
        return cancelText == null ? UltiTools.getInstance().i18n("取消") : cancelText;
    }
}
