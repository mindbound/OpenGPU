package io.github.mindbound.opengpu.card;

import java.util.List;

import net.minecraft.client.renderer.texture.IIconRegister;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.IIcon;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import io.github.mindbound.opengpu.OpenGPU;
import li.cil.oc.api.CreativeTab;

/**
 * The card item, {@code opengpu:card} (Q-S11-2): one item whose damage is OpenGPU's tier, 2 for T2 and 3 for T3, with 1
 * kept free for M1's T1 (28:37: M0 builds T2 and T3 only). One per stack, in OC's creative tab (RM:89's pack basics,
 * brought forward by Q-S11-5), and no recipe before M4 (RM:89, :184). Its icons, one per tier, are the item atlas's
 * {@value #T2_ICON} and {@value #T3_ICON}, generated placeholders that other art can replace at the same names and
 * square power-of-two sizes (S12 plan P9.3, P9.4; Q-S12-3 (a)). Its client-only members carry
 * {@code @SideOnly(CLIENT)}, so a dedicated server strips them and jarTest's server view never sees a client type
 * (24 §3.1 check 1).
 */
public class CardItem extends Item {

    /** The damage of a tier 2 card. */
    public static final int META_T2 = 2;
    /** The damage of a tier 3 card. */
    public static final int META_T3 = 3;
    /** The tier 2 card's icon, 16 × 16 (S12 plan P9.3). */
    public static final String T2_ICON = OpenGPU.MODID + ":card_t2";
    /** The tier 3 card's icon, 16 × 16 (S12 plan P9.3). */
    public static final String T3_ICON = OpenGPU.MODID + ":card_t3";

    /** The tier 3 icon; null until the item atlas registers its icons, on a client only. T2's is {@code itemIcon}. */
    @SideOnly(Side.CLIENT)
    private IIcon t3Icon;

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

    /**
     * Registers the two icons in the item atlas, at {@code assets/opengpu/textures/items/<name>.png}
     * (TextureMap.java:316-319, :381), on the client thread at every stitch. T2's is also the item's own icon.
     */
    @SideOnly(Side.CLIENT)
    @Override
    public void registerIcons(IIconRegister register) {
        itemIcon = register.registerIcon(T2_ICON);
        t3Icon = register.registerIcon(T3_ICON);
    }

    /**
     * T3's icon for damage {@value #META_T3}, T2's for any other: a card of another damage, which neither the tab nor a
     * recipe makes, then looks like a T2 under the plain card's name key, so no render path meets a missing icon
     * [proposal; S12 build, U3].
     */
    @SideOnly(Side.CLIENT)
    @Override
    public IIcon getIconFromDamage(int meta) {
        return meta == META_T3 ? t3Icon : itemIcon;
    }
}
