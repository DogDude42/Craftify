package net.gravtech.mixin;

import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.gravtech.ui.YtmHud;

/**
 * Lets the Craftify HUD control buttons eat clicks before they reach the
 * game / current Screen. Port of upstream MouseHandlerMixin to MC 26.1.2.
 *
 * onButton(long window, MouseButtonInfo info, int action):
 *   action == 1 = GLFW_PRESS
 */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {

    @Unique
    private double craftify$scaledX;

    @Unique
    private double craftify$scaledY;

    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void craftify$onScroll(long window, double deltaX, double deltaY,
                                   CallbackInfo ci) {
        // Scroll over the HUD = volume adjust; consume so the hotbar doesn't
        // cycle while the user is tuning volume.
        if (YtmHud.onMouseScrolled(deltaY)) {
            ci.cancel();
        }
    }

    @Inject(method = "onButton", at = @At("HEAD"), cancellable = true)
    private void craftify$onButton(long window, MouseButtonInfo info, int action,
                                   CallbackInfo ci) {
        // action: 0 = release, 1 = press (GLFW)
        if (action == 0) {
            // ALWAYS notify on release so drag/resize can never get stuck
            YtmHud.onMouseReleased();
            return;
        }
        if (action != 1) return;

        MouseHandler self = (MouseHandler) (Object) this;
        Minecraft mc = Minecraft.getInstance();
        Window win = mc.getWindow();

        double sx = self.getScaledXPos(win);
        double sy = self.getScaledYPos(win);

        if (YtmHud.onMouseClicked(sx, sy, info.button())) {
            ci.cancel();
        }
    }
}
