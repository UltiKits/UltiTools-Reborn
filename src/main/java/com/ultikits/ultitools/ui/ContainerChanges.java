package com.ultikits.ultitools.ui;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;

/** Changes since the baseline; copies; immutable. */
@ApiStatus.NonExtendable
public interface ContainerChanges {
    boolean isEmpty();
    /** Net items put in, merged by similarity. */
    List<ItemStack> putIn();
    /** Net items taken out of the baseline, merged by similarity. */
    List<ItemStack> takenOut();
    /** Net put-ins per contributor. */
    Map<UUID, List<ItemStack>> putInBy();
}
