package com.ultikits.ultitools.ui.runtime;

import com.ultikits.ultitools.scheduling.Scheduler;
import com.ultikits.ultitools.ui.Delivery;
import com.ultikits.ultitools.ui.SlotContainer;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.logging.LogRecord;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;

/**
 * Host for exactly one module copy, paired with the UiOwner passed to ContainerRuntime#create.
 * Never look up another host by module name: same-name module copies may coexist. Internal.
 */
@ApiStatus.Internal
public interface ContainerHost {
    /** Returns items to a contributor through UiService#deliver semantics (offline: all undelivered). */
    CompletionStage<Delivery> returnTo(UUID contributor, List<ItemStack> items);
    /** Contents or state of {@code container} changed: redraw every session that shows it before the next tick ends. */
    void changed(SlotContainer container);
    /** Logs {@code record} (WARNING or higher, message already naming the owner) through the runtime's logger. */
    void warn(LogRecord record);
    /** The scheduler the runtime uses (tests: the RecordingScheduler wrapper). */
    Scheduler scheduler();
}
