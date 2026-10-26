package io.github.mindbound.opengpu.card;

import java.util.List;

import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import io.github.mindbound.opengpu.OpenGPU;
import li.cil.oc.api.CreativeTab;

/**
 * The card item, {@code opengpu:card} (Q-S11-2): one item whose damage is OpenGPU's tier, 2 for T2 and 3 for T3, with 1
 * kept free for M1's T1 (28:37: M0 builds T2 and T3 only). One per stack, in OC's creative tab (RM:89's pack basics,
 * brought forward by Q-S11-5), and no recipe before M4 (RM:89, :184). Textures come with S12's rendering. Its
 * client-only members carry {@code @SideOnly(CLIENT)}, so a dedicated server strips them and jarTest's server view
 * never sees a client type (24 §3.1 check 1).
 */
public class CardItem extends Item {

    /** The damage of a tier 2 card. */
    public static final int META_T2 = 2;
    /** The damage of a tier 3 card. */
    public static final int META_T3 = 3;

    /** Builds the item; a plain JVM can, with OC's tab null there. */
    public CardItem() {
        setHasSubtypes(true);
        setMaxDamage(0);
        setMaxStackSize(1);
        setUnlocalizedName(OpenGPU.MODID + ".card");
        // OC makes its tab in its own preInit, which runs before OpenGPU's (required-after)
        setCreativeTab(CreativeTab.instance);
    }

    /** OpenGPU's tier of a card stack, 2 or 3; 0 for a null stack, another item or another damage. */
    public static int tierOf(ItemStack stack) {
        if (stack == null || !(stack.getItem() instanceof CardItem)) {
            return 0;
        }
        int meta = stack.getItemDamage();
        return meta == META_T2 || meta == META_T3 ? meta : 0;
    }

    /** "item.opengpu.card.t2" or ".t3", the lang file's keys; "item.opengpu.card" for another damage. */
    @Override
    public String getUnlocalizedName(ItemStack stack) {
        int tier = tierOf(stack);
        return tier == 0 ? getUnlocalizedName() : getUnlocalizedName() + ".t" + tier;
    }

    @SideOnly(Side.CLIENT)
    @Override
    public void getSubItems(Item item, CreativeTabs tab, List<ItemStack> list) {
        list.add(new ItemStack(item, 1, META_T2));
        list.add(new ItemStack(item, 1, META_T3));
    }
}
