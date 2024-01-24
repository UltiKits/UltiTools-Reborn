package com.ultikits.ultitools.interfaces.impl;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.interfaces.TempEventHandler;
import com.ultikits.ultitools.interfaces.TempListener;
import lombok.*;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerEvent;

/**
 * Player temp listener.
 * <p>
 * 玩家临时监听器。
 *
 * @param <E> PlayerEvent type
 * @see <a href="https://dev.ultikits.com/en/guide/essentials/event-listener.html#temporary-listener">Temporary Listener</a>
 */
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Setter
@Getter
public class PlayerTempListener<E extends PlayerEvent> implements TempListener {
    private Class<E> eventClass;
    private EventPriority priority = EventPriority.NORMAL;
    private TempEventHandler<E> eventHandler;
    private Player player;

    public PlayerTempListener(Class<E> eventClass, TempEventHandler<E> eventHandler) {
        this.eventClass = eventClass;
        this.eventHandler = eventHandler;
    }

    public PlayerTempListener(Class<E> eventClass, TempEventHandler<E> eventHandler, Player player) {
        this.eventClass = eventClass;
        this.eventHandler = eventHandler;
        this.player = player;
    }

    @SuppressWarnings("unchecked")
    public void register() {
        Bukkit.getServer().getPluginManager().registerEvent(eventClass, this, priority,
                (ignored, event) -> {
                    try {
                        if (player == null) {
                            if (eventHandler.handle((E) event)) {
                                unregister();
                            }
                        } else if (((E) event).getPlayer().equals(player)) {
                            if (eventHandler.handle((E) event)) {
                                unregister();
                            }
                        }
                    } catch (ClassCastException e) {
                        throw new RuntimeException(e);
                    }
                },
                UltiTools.getInstance()
        );
    }
}
