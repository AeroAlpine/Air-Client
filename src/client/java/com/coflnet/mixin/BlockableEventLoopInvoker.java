package com.coflnet.mixin;

import net.minecraft.util.thread.BlockableEventLoop;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Turbo mode handles queued tasks (packets) once per tick on client and integrated server. */
@Mixin(BlockableEventLoop.class)
public interface BlockableEventLoopInvoker {
    @Invoker("runAllTasks")
    void airclient$runAllTasks();
}
