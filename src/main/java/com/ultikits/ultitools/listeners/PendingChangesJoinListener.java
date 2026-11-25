package com.ultikits.ultitools.listeners;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.jetbrains.annotations.ApiStatus;

import com.ultikits.ultitools.abstracts.command.CommandRuntime;
import com.ultikits.ultitools.text.Arg;
import com.ultikits.ultitools.utils.ModuleFileTransactions;
import com.ultikits.ultitools.utils.ModuleFileTransactions.StartOutcome;
import com.ultikits.ultitools.utils.PendingChanges;
import com.ultikits.ultitools.utils.UpmKeys;

/** Reminds operators at join time, without pushing more messages to already online players. */
@ApiStatus.Internal
public final class PendingChangesJoinListener implements Listener {
    private final ModuleFileTransactions transactions;
    private final Map<UUID, Long> remindedAt = new ConcurrentHashMap<>();

    public PendingChangesJoinListener(ModuleFileTransactions transactions) {
        this.transactions = transactions;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (player.isOp()) {
            CommandRuntime.scheduler().entity(player).runLater(Duration.ofMillis(50), () -> notifyOperator(player));
        }
    }

    private void notifyOperator(Player player) {
        if (!player.isOnline() || !player.isOp()) {
            return;
        }
        UUID id = player.getUniqueId();
        Long last = remindedAt.get(id);
        if (last != null && transactions.pendingGeneration() <= last) {
            return;
        }
        var pending = new PendingChanges(transactions).snapshot();
        List<StartOutcome> outcomes = last == null ? transactions.startOutcomes() : List.of();
        if (pending.total() == 0 && outcomes.isEmpty()) {
            return;
        }
        var messages = CommandRuntime.messages();
        if (pending.total() != 0) {
            messages.send(player, UpmKeys.PENDING_NOTICE_WAITING, Arg.number("count", pending.total()));
        }
        if (!outcomes.isEmpty()) {
            long problems = outcomes.stream().filter(outcome -> outcome.result() != StartOutcome.Result.APPLIED).count();
            if (problems != 0) {
                messages.send(player, UpmKeys.PENDING_NOTICE_LAST_START_PROBLEMS, Arg.number("count", problems));
            } else {
                messages.send(player, UpmKeys.PENDING_NOTICE_LAST_START_OK, Arg.number("count", outcomes.size()));
            }
        }
        remindedAt.put(id, pending.generation());
    }
}
