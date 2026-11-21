package mineblend;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;
import net.minecraft.util.Rarity;

public class MineBlend implements ModInitializer {
    public static final Item SELECTION_WAND = new SelectionWandItem(new Item.Settings().maxCount(1).rarity(Rarity.RARE));

    @Override
    public void onInitialize() {
        Registry.register(Registries.ITEM, new Identifier("mineblend", "selection_wand"), SELECTION_WAND);
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.TOOLS).register(e -> e.add(SELECTION_WAND));
    }
}
