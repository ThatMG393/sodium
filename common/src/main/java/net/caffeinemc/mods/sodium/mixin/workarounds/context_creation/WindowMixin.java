package net.caffeinemc.mods.sodium.mixin.workarounds.context_creation;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.platform.DisplayData;
import com.mojang.blaze3d.platform.ScreenManager;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.platform.WindowEventHandler;
import net.caffeinemc.mods.sodium.client.compatibility.checks.PostLaunchChecks;
import net.caffeinemc.mods.sodium.client.compatibility.checks.ModuleScanner;
import net.caffeinemc.mods.sodium.client.gl.GlContextInfo;
import net.caffeinemc.mods.sodium.client.compatibility.workarounds.Workarounds;
import net.caffeinemc.mods.sodium.client.compatibility.workarounds.nvidia.NvidiaWorkarounds;
import net.caffeinemc.mods.sodium.client.platform.NativeWindowHandle;
import net.caffeinemc.mods.sodium.client.services.PlatformRuntimeInformation;
import net.minecraft.Util;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.WGL;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;


@Mixin(Window.class)
public class WindowMixin {
    @Shadow
    @Final
    private static Logger LOGGER;

    @Shadow
    @Final
    private long window;

    @Unique
    private long wglPrevContext = MemoryUtil.NULL;

    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwCreateWindow(IILjava/lang/CharSequence;JJ)J"), expect = 0, require = 0)
    private long wrapGlfwCreateWindow(int width, int height, CharSequence title, long monitor, long share) {
        final boolean applyNvidiaWorkarounds = Workarounds.isWorkaroundEnabled(Workarounds.Reference.NVIDIA_THREADED_OPTIMIZATIONS);

        if (applyNvidiaWorkarounds) {
            NvidiaWorkarounds.install();
        }

        try {
            return GLFW.glfwCreateWindow(width, height, title, monitor, share);
        } finally {
            if (applyNvidiaWorkarounds) {
                NvidiaWorkarounds.uninstall();
            }
        }
    }

    @SuppressWarnings("all")
    @WrapOperation(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/neoforged/fml/loading/ImmediateWindowHandler;setupMinecraftWindow(Ljava/util/function/IntSupplier;Ljava/util/function/IntSupplier;Ljava/util/function/Supplier;Ljava/util/function/LongSupplier;)J"), expect = 0, require = 0)
    private long wrapGlfwCreateWindowForge(final IntSupplier width, final IntSupplier height, final Supplier<String> title, final LongSupplier monitor, Operation<Long> op) {
        final boolean applyNvidiaWorkarounds = Workarounds.isWorkaroundEnabled(Workarounds.Reference.NVIDIA_THREADED_OPTIMIZATIONS);

        if (applyNvidiaWorkarounds && !PlatformRuntimeInformation.getInstance().platformHasEarlyLoadingScreen()) {
            NvidiaWorkarounds.install();
        }

        try {
            return op.call(width, height, title, monitor);
        } finally {
            if (applyNvidiaWorkarounds) {
                NvidiaWorkarounds.uninstall();
            }
        }
    }

    @Inject(method = "<init>", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL;createCapabilities()Lorg/lwjgl/opengl/GLCapabilities;", shift = At.Shift.AFTER))
    private void postContextReady(WindowEventHandler eventHandler, ScreenManager monitorTracker, DisplayData settings, String videoMode, String title, CallbackInfo ci) {
        GlContextInfo driver = GlContextInfo.create();

        if (driver == null) {
            LOGGER.warn("Could not retrieve identifying strings for OpenGL implementation");
        } else {
            LOGGER.info("OpenGL Vendor: {}", driver.vendor());
            LOGGER.info("OpenGL Renderer: {}", driver.renderer());
            LOGGER.info("OpenGL Version: {}", driver.version());
        }

        // Capture the current WGL context so that we can detect it being replaced later.
        if (Util.getPlatform() == Util.OS.WINDOWS) {
            this.wglPrevContext = WGL.wglGetCurrentContext();
        } else {
            this.wglPrevContext = MemoryUtil.NULL;
        }

        PostLaunchChecks.onContextInitialized();
        ModuleScanner.checkModules((NativeWindowHandle) this);
    }

    @Inject(method = "updateDisplay", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;flipFrame(JLcom/mojang/blaze3d/TracyFrameCapture;)V", shift = At.Shift.AFTER))
    private void preSwapBuffers(CallbackInfo ci) {
        if (this.wglPrevContext == MemoryUtil.NULL) {
            // There is no prior recorded context.
            return;
        }

        var context = WGL.wglGetCurrentContext();

        if (this.wglPrevContext == context) {
            // The context has not changed.
            return;
        }

        // Something has decided to replace the OpenGL context, which is not a good sign
        LOGGER.warn("The OpenGL context appears to have been suddenly replaced! Something has likely just injected into the game process.");

        // Likely, this indicates a module was injected into the current process. We should check that
        // nothing problematic was just installed.
        ModuleScanner.checkModules((NativeWindowHandle) this);

        // If we didn't find anything problematic (which would have thrown an exception), then let's just record
        // the new context pointer and carry on.
        this.wglPrevContext = context;
    }
}
