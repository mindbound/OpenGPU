package io.github.mindbound.opengpu.card;

import net.minecraft.item.ItemStack;

import li.cil.oc.api.driver.EnvironmentProvider;

/**
 * Reports the card's environment class for a card stack, which NEI and OC's manual show (05:100; S11 plan P6.3). A
 * class of its own, as OC's DriverGraphicsCard.Provider is (DriverGraphicsCard.scala:38-43), so {@code Driver.add}
 * is not ambiguous for the driver (S11 spike E1).
 */
public final class CardProvider implements EnvironmentProvider {

    /** The one provider, registered in init beside {@link CardDriver}. */
    public static final CardProvider INSTANCE = new CardProvider();

    private CardProvider() {}

    /** {@link CardEnvironment} for a T2 or T3 card, else null. */
    @Override
    public Class<?> getEnvironment(ItemStack stack) {
        return CardItem.tierOf(stack) == 0 ? null : CardEnvironment.class;
    }

    @Override
    public String toString() {
        return "CardProvider";
    }
}
