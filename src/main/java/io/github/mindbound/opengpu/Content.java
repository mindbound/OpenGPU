package io.github.mindbound.opengpu;

import net.minecraft.item.ItemBlock;

import cpw.mods.fml.common.registry.GameRegistry;
import io.github.mindbound.opengpu.card.CardItem;
import io.github.mindbound.opengpu.display.DisplayBlock;
import io.github.mindbound.opengpu.display.DisplayTile;

/**
 * OpenGPU's items, block and tile entity, registered in preInit on both sides under Q-S11-2's names: the item
 * {@code opengpu:card}, the block {@code opengpu:screen} with a plain {@link ItemBlock}, and the tile entity
 * {@code opengpu:screen} (S11 plan P5.1). OC's driver for the card is added in init, once OC's registry exists.
 */
public final class Content {

    /** The card item, set in preInit. */
    public static CardItem card;
    /** The display block, set in preInit. */
    public static DisplayBlock screen;

    private Content() {}

    static void register() {
        card = new CardItem();
        GameRegistry.registerItem(card, "card");
        screen = new DisplayBlock();
        GameRegistry.registerBlock(screen, ItemBlock.class, "screen");
        GameRegistry.registerTileEntity(DisplayTile.class, OpenGPU.MODID + ":screen");
    }
}
