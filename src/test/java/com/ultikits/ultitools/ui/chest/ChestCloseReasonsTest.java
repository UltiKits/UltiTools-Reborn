package com.ultikits.ultitools.ui.chest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.ultikits.ultitools.ui.CloseReason;
import java.util.Map;
import org.bukkit.event.inventory.InventoryCloseEvent.Reason;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(5)
class ChestCloseReasonsTest {
    @Test void everyPaperReasonUsesTheExistingVocabulary() {
        Map<Reason, CloseReason> table = Map.of(
                Reason.UNKNOWN, CloseReason.INTERRUPTED, Reason.TELEPORT, CloseReason.INTERRUPTED,
                Reason.CANT_USE, CloseReason.INTERRUPTED, Reason.UNLOADED, CloseReason.INTERRUPTED,
                Reason.OPEN_NEW, CloseReason.INTERRUPTED, Reason.PLAYER, CloseReason.PLAYER,
                Reason.DISCONNECT, CloseReason.DISCONNECTED, Reason.DEATH, CloseReason.INTERRUPTED,
                Reason.PLUGIN, CloseReason.INTERRUPTED);
        assertThat(table).hasSize(Reason.values().length);
        table.forEach((reason, expected) -> assertThat(ChestCloseReasons.map(reason)).isEqualTo(expected));
        assertThatNullPointerException().isThrownBy(() -> ChestCloseReasons.map(null));
    }
}
