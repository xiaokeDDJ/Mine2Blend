package mineblend.mixin;

import mineblend.Selection;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 手持选区棒按左键：沿视线远距离选点 1（不需要对准触及范围内的方块），并取消原本的攻击/挖掘。 */
@Mixin(MinecraftClient.class)
public abstract class MinecraftClientMixin {
    @Inject(method = "doAttack", at = @At("HEAD"), cancellable = true)
    private void mineblend$wandAttack(CallbackInfoReturnable<Boolean> cir) {
        if (Selection.onWandAttack()) cir.setReturnValue(true);
    }
}
