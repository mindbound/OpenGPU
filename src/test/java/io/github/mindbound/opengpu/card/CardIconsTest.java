package io.github.mindbound.opengpu.card;

import java.io.IOException;
import java.util.Arrays;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import io.github.mindbound.opengpu.fixtures.FakeIcons;

/**
 * The card's icons (S12 plan P9.3, P9.4; Q-S12-3 (a)): one per tier in the item atlas, each a 16 × 16 PNG on the
 * classpath, chosen by the damage. No atlas here: the icons come from a recording register.
 */
@Timeout(60)
class CardIconsTest {

    @Test
    void itRegistersOneIconPerTierEachASixteenTexelSquarePng() throws IOException {
        FakeIcons register = new FakeIcons();
        new CardItem().registerIcons(register);
        Assertions.assertEquals(Arrays.asList("opengpu:card_t2", "opengpu:card_t3"), register.names());
        Assertions.assertArrayEquals(new int[] { 16, 16 }, FakeIcons.pngSize(CardItem.T2_ICON, "items"));
        Assertions.assertArrayEquals(new int[] { 16, 16 }, FakeIcons.pngSize(CardItem.T3_ICON, "items"));
    }

    // A damage that neither the tab nor a recipe makes shows T2's icon, so no render path meets a missing one
    @Test
    void theIconFollowsTheTierAndAnyOtherDamageShowsTheTierTwoIcon() {
        CardItem item = new CardItem();
        item.registerIcons(new FakeIcons());
        Assertions.assertEquals(
            CardItem.T2_ICON,
            item.getIconFromDamage(CardItem.META_T2)
                .getIconName());
        Assertions.assertEquals(
            CardItem.T3_ICON,
            item.getIconFromDamage(CardItem.META_T3)
                .getIconName());
        for (int meta : new int[] { 0, 1, 4, 32767 }) {
            Assertions.assertEquals(
                CardItem.T2_ICON,
                item.getIconFromDamage(meta)
                    .getIconName(),
                "damage " + meta);
        }
    }
}
