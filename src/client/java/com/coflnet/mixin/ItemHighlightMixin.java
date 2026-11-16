package com.coflnet.mixin;

import CoflCore.handlers.DescriptionHandler;
import com.coflnet.CoflModClient;
import com.coflnet.ahflipper.AhFlipperMod;
import com.coflnet.gui.RenderUtils;
import com.coflnet.gui.panel.ItemIconCache;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;


@Mixin(AbstractContainerScreen.class) // Target AbstractContainerScreen, which is the base for most container UIs
public abstract class ItemHighlightMixin {

    // You might need to shadow fields from AbstractContainerScreen to get context like x/y/width/height
    @Shadow protected int leftPos;
    @Shadow protected int topPos;
    @Shadow protected int imageWidth;
    @Shadow protected int imageHeight;

    @Shadow @Nullable protected Slot hoveredSlot;
    private static long lastBulkIconLearnAt;

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void onKeyPressed(KeyEvent input, CallbackInfoReturnable<Boolean> cir) {
        try {
            if (AhFlipperMod.handleToggleKeys(input)) {
                cir.setReturnValue(true);
                return;
            }
            AbstractContainerScreen<?> screen = (AbstractContainerScreen<?>)(Object)this;
            if (AhFlipperMod.blacklistKey() != null
                    && AhFlipperMod.blacklistKey().matches(input)
                    && screen.getFocused() == null) {
                if (hoveredSlot != null && hoveredSlot.hasItem()) {
                    AhFlipperMod.handleBlacklistKey(hoveredSlot.getItem());
                } else {
                    AhFlipperMod.handleBlacklistKey(null);
                }
                cir.setReturnValue(true);
                return;
            }
        } catch (Exception e) {
            System.out.println("[ItemHighlightMixin] keyPressed failed: " + e.getMessage());
        }
    }

    private static boolean orderColorScreen(AbstractContainerScreen<?> screen) {
        if (!pingplus.voicechat.client.PlayerSettings.orderColors || screen.getTitle() == null) {
            return false;
        }
        String title = screen.getTitle().getString().toLowerCase(java.util.Locale.ROOT);
        return title.contains("bazaar orders") || title.contains("manage auctions");
    }

    @Inject(method = "extractSlots", at = @At("HEAD"))
    private void yourmodid_onDrawBackground(GuiGraphicsExtractor context, int mouseX, int mouseY, CallbackInfo ci) {
        try {
            if (hoveredSlot != null && hoveredSlot.hasItem()) {
                CoflModClient.maybeUploadHoveredMapContent(hoveredSlot.getItem());
                ItemIconCache.learn(hoveredSlot.getItem());
            }

            AbstractContainerScreen<?> screen = (AbstractContainerScreen<?>)(Object)this;
            // Auction/bazaar product GUIs show the relevant item in slot 13.
            if (screen.getMenu().slots.size() > 13) {
                Slot center = screen.getMenu().slots.get(13);
                if (center != null && center.hasItem()) {
                    ItemIconCache.learn(center.getItem());
                }
            }
            long now = System.currentTimeMillis();
            if (now - lastBulkIconLearnAt > 500L) {
                lastBulkIconLearnAt = now;
                for (Slot slot : screen.getMenu().slots) {
                    if (slot.hasItem()) {
                        ItemIconCache.learn(slot.getItem());
                    }
                }
            }
            // Cofl's coloured slot highlights only where they are wanted: your Bazaar orders
            // (red = outbid, green = still the best price) and Manage Auctions (red = no longer
            // the lowest BIN, green = still lowest BIN). Everywhere else they stay off.
            for (Slot slot : orderColorScreen(screen) ? screen.getMenu().slots : java.util.List.<Slot>of()) {
                if (!slot.hasItem())
                    continue;
                DescriptionHandler.DescModification[] tooltips = DescriptionHandler.getTooltipData(CoflModClient.getIdFromStack(slot.getItem()));
                if(tooltips == null)
                    continue;
                for (DescriptionHandler.DescModification tooltip : tooltips) {
                    if (tooltip.type.equals("HIGHLIGHT")) {
                        int hexColor = Integer.parseInt(tooltip.value, 16) | 0xFF000000;
                        if (tooltip.line < 0) {
                            RenderUtils.drawRectOutline(context, slot.x, slot.y, 16, 16, 2, 0x00000000, hexColor);
                        } else {
                            RenderUtils.drawRect(context, slot.x, slot.y, 16, 16, hexColor);
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("[ItemHighlightMixin] extractSlots failed: " + e.getMessage());
        }
    }
}
