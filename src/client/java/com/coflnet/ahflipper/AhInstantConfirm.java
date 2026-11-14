package com.coflnet.ahflipper;

import com.coflnet.flipper.trading.ScreenUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;

import java.util.Locale;

/**
 * Clicks "Confirm Purchase" the moment its items arrive. Called from the container packet
 * handlers on the client thread (see {@code AhInstantClickMixin}), so the click does not wait
 * for the buy worker, which needs a frame per screen read (50 ms with a background window).
 * <p>
 * The buy flow arms it with the highest confirm price that is still a profitable buy; a higher
 * price is never clicked and is left to the worker, which then cancels.
 */
public final class AhInstantConfirm {
    private static volatile long armedUntil;
    private static volatile long maxPrice;
    private static volatile long clickedAt;
    private static volatile long clickedPrice;
    /** Client thread only: one instant click per confirm window. */
    private static int clickedContainer = Integer.MIN_VALUE;

    private AhInstantConfirm() {
    }

    /** @param maxPrice highest confirm price to click, 0 or less for no limit */
    public static void arm(long maxPrice, long timeoutMs) {
        AhInstantConfirm.maxPrice = maxPrice;
        clickedAt = 0;
        clickedPrice = 0;
        ScreenUtil.onClient(() -> clickedContainer = Integer.MIN_VALUE);
        armedUntil = System.currentTimeMillis() + timeoutMs;
    }

    public static void disarm() {
        armedUntil = 0;
    }

    /** When the instant click went out, 0 if it did not (yet). */
    public static long clickedAt() {
        return clickedAt;
    }

    /** Confirm price the instant click was made at. */
    public static long clickedPrice() {
        return clickedPrice;
    }

    /** Client thread, after a container content / slot packet was applied. */
    public static void onContainerUpdate() {
        long until = armedUntil;
        if (until == 0) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now > until) {
            armedUntil = 0;
            return;
        }
        Screen screen = Minecraft.getInstance().gui.screen();
        if (!(screen instanceof AbstractContainerScreen<?> container)) {
            return;
        }
        String title = ScreenUtil.cleanName(container.getTitle().getString()).toLowerCase(Locale.ROOT);
        if (!title.contains("confirm purchase")) {
            return;
        }
        int id = container.getMenu().containerId;
        if (id == clickedContainer) {
            return;
        }
        int slot = confirmSlot(container);
        if (slot < 0) {
            return; // items not there yet
        }
        long price = AhScreenUtil.priceAt(container, slot);
        if (price <= 0) {
            price = AhScreenUtil.priceAt(container, 13);
        }
        if (price <= 0) {
            return; // no price to check: the worker decides
        }
        long limit = maxPrice;
        if (limit > 0 && price > limit) {
            armedUntil = 0;
            System.out.println("[AH-Flipper] Instant-Confirm: Preis " + price + " > max " + limit
                    + " - nicht geklickt");
            return;
        }
        if (!ScreenUtil.middleClickNow(slot)) {
            return;
        }
        clickedContainer = id;
        clickedPrice = price;
        clickedAt = now;
        armedUntil = 0;
        System.out.println("[AH-Flipper] Instant-Confirm: Slot " + slot + " geklickt, Preis " + price);
    }

    /** The green "Confirm" item, normally slot 11. */
    private static int confirmSlot(AbstractContainerScreen<?> container) {
        int size = container.getMenu() instanceof ChestMenu chest
                ? chest.getContainer().getContainerSize()
                : Math.max(0, container.getMenu().slots.size() - 36);
        if (size > 11 && isConfirm(container.getMenu().getSlot(11).getItem())) {
            return 11;
        }
        for (int i = 0; i < size; i++) {
            if (isConfirm(container.getMenu().getSlot(i).getItem())) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isConfirm(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        String name = ScreenUtil.cleanName(stack.getHoverName().getString()).toLowerCase(Locale.ROOT);
        return name.contains("confirm") && !name.contains("cancel");
    }
}
