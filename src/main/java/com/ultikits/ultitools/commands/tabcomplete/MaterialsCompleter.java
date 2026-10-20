package com.ultikits.ultitools.commands.tabcomplete;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.bukkit.Material;

/**
 * Completer that suggests Minecraft material names.
 *
 * @author wisdomme
 * @version 1.0.0
 * @since 6.2.0
 */
public class MaterialsCompleter implements TabCompleter {
    
    private final boolean blocksOnly;
    private final boolean itemsOnly;
    
    /**
     * Creates a completer that suggests all materials.
     */
    public MaterialsCompleter() {
        this(false, false);
    }
    
    /**
     * Creates a completer with filtering options.
     *
     * @param blocksOnly only suggest block materials
     * @param itemsOnly  only suggest item materials (ignored if blocksOnly is true)
     */
    public MaterialsCompleter(boolean blocksOnly, boolean itemsOnly) {
        this.blocksOnly = blocksOnly;
        this.itemsOnly = itemsOnly;
    }
    
    @Override
    public List<String> complete(TabCompletionContext context) {
        String input = context.getCurrentInput().toLowerCase();
        List<String> materials = new ArrayList<>();
        
        for (Material material : Material.values()) {
            // Skip legacy materials
            if (material.name().startsWith("LEGACY_")) {
                continue;
            }
            
            // Apply filters
            if (blocksOnly && !material.isBlock()) {
                continue;
            }
            if (itemsOnly && !blocksOnly && !material.isItem()) {
                continue;
            }
            
            String name = material.name().toLowerCase();
            if (name.startsWith(input)) {
                materials.add(material.name());
            }
        }
        
        Collections.sort(materials);
        return materials;
    }
    
    /**
     * Creates a completer for block materials only.
     *
     * @return a blocks-only completer
     */
    public static MaterialsCompleter blocksOnly() {
        return new MaterialsCompleter(true, false);
    }
    
    /**
     * Creates a completer for item materials only.
     *
     * @return an items-only completer
     */
    public static MaterialsCompleter itemsOnly() {
        return new MaterialsCompleter(false, true);
    }
}
