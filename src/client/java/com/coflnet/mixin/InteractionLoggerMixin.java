package com.coflnet.mixin;

import com.coflnet.interactions.InteractionLogger;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Feeds every click, entity/block interaction and mining start to the Interaction Logger. */
@Mixin(MultiPlayerGameMode.class)
public class InteractionLoggerMixin {

    @Inject(method = "handleContainerInput", at = @At("HEAD"))
    private void interactions$click(int containerId, int slot, int button, ContainerInput input, Player player,
                                    CallbackInfo ci) {
        try {
            InteractionLogger.onContainerInput(containerId, slot, button, input);
        } catch (RuntimeException ignored) {
        }
    }

    @Inject(method = "interact", at = @At("HEAD"))
    private void interactions$interact(Player player, Entity entity, EntityHitResult hit, InteractionHand hand,
                                       CallbackInfoReturnable<InteractionResult> cir) {
        if (hand != InteractionHand.MAIN_HAND) return;
        try {
            InteractionLogger.onInteractEntity(entity);
        } catch (RuntimeException ignored) {
        }
    }

    @Inject(method = "attack", at = @At("HEAD"))
    private void interactions$attack(Player player, Entity entity, CallbackInfo ci) {
        try {
            InteractionLogger.onAttackEntity(entity);
        } catch (RuntimeException ignored) {
        }
    }

    @Inject(method = "useItemOn", at = @At("HEAD"))
    private void interactions$useItemOn(LocalPlayer player, InteractionHand hand, BlockHitResult hit,
                                        CallbackInfoReturnable<InteractionResult> cir) {
        if (hand != InteractionHand.MAIN_HAND) return;
        try {
            InteractionLogger.onUseItemOn(hit);
        } catch (RuntimeException ignored) {
        }
    }

    @Inject(method = "useItem", at = @At("HEAD"))
    private void interactions$useItem(Player player, InteractionHand hand, CallbackInfoReturnable<InteractionResult> cir) {
        if (hand != InteractionHand.MAIN_HAND) return;
        try {
            InteractionLogger.onUseItem();
        } catch (RuntimeException ignored) {
        }
    }

    @Inject(method = "startDestroyBlock", at = @At("HEAD"))
    private void interactions$mine(BlockPos pos, Direction face, CallbackInfoReturnable<Boolean> cir) {
        try {
            InteractionLogger.onStartDestroy(pos, face);
            com.coflnet.mining.MiningFeatures.onStartDestroy(pos);
        } catch (RuntimeException ignored) {
        }
    }
}
