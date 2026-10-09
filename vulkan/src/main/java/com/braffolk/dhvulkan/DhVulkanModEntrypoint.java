package com.braffolk.dhvulkan;

import com.braffolk.dhvulkan.bridge.DhIntegration;
import com.braffolk.dhvulkan.config.DhVulkanConfig;
import com.braffolk.dhvulkan.core.VulkanBackend;
import com.braffolk.dhvulkan.api.ApiDhIntegration;
import com.braffolk.dhvulkan.core.VulkanRenderEngine;
import com.braffolk.dhvulkan.compat.Compat;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.network.chat.Component;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Main entrypoint for the DH-VulkanMod extension mod.
 * Targets Distant Horizons 3.3.x (render API integration).
 */
public class DhVulkanModEntrypoint implements ClientModInitializer {

    private static final Logger LOGGER = LogManager.getLogger("DH-VulkanMod");

    private static final String[] MODE_NAMES = {
            "Normal", "DH Depth", "SSAO", "Fog Alpha", "Fog Color", "Normals", "MC Depth"
    };

    /** The active integration, set during init */
    private static DhIntegration activeIntegration;

    @Override
    public void onInitializeClient() {
        LOGGER.info("[DH-VulkanMod] Extension mod initializing...");

        DhVulkanConfig config = DhVulkanConfig.get();
        LOGGER.info("[DH-VulkanMod] Config loaded. vulkanRenderMode={}", config.vulkanRenderMode);

        // Register /dh-debug <0-6> client command
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommandManager.literal("dh-debug")
                    .executes(ctx -> {
                        int mode = DhVulkanConfig.get().vulkanRenderMode;
                        String name = mode >= 0 && mode < MODE_NAMES.length ? MODE_NAMES[mode] : "Unknown";
                        ctx.getSource().sendFeedback(Component.literal(
                                "\u00a7b[DH-Vulkan]\u00a7r Render mode: " + mode + " (" + name + ")"));
                        return 1;
                    })
                    .then(ClientCommandManager.argument("mode", IntegerArgumentType.integer(0, 6))
                            .executes(ctx -> {
                                int mode = IntegerArgumentType.getInteger(ctx, "mode");
                                DhVulkanConfig.get().vulkanRenderMode = mode;
                                DhVulkanConfig.get().save();
                                String name = mode >= 0 && mode < MODE_NAMES.length ? MODE_NAMES[mode] : "Unknown";
                                ctx.getSource().sendFeedback(Component.literal(
                                        "\u00a7b[DH-Vulkan]\u00a7r Render mode set to: " + mode + " (" + name + ")"));
                                return 1;
                            })));
        });

        if (!Compat.isVulkanModActive()) {
            LOGGER.warn("[DH-VulkanMod] VulkanMod is NOT detected. Extension will be inactive.");
            return;
        }

        LOGGER.info("[DH-VulkanMod] VulkanMod detected. Vulkan rendering backend will be used.");

        // Create the shared Vulkan backend
        VulkanBackend backend = new VulkanRenderEngine();

        // Create the DH render-API integration. The actual binding into DH happens
        // later, when MixinDependencySetup intercepts DH's setRenderingApiBindings().
        ApiDhIntegration apiIntegration = new ApiDhIntegration();
        apiIntegration.initialize(backend);
        activeIntegration = apiIntegration;

        LOGGER.info("[DH-VulkanMod] Using integration: {}", activeIntegration.getName());
    }

    /** Get the active integration (for mixins that need it) */
    public static DhIntegration getActiveIntegration() {
        return activeIntegration;
    }

    /**
     * Called from MixinLevelRenderer at renderLevel @RETURN to read MC's depth
     * buffer while the swapchain depth is in a known-good Vulkan image layout.
     * The result is cached and used by the next deferredComposite() call.
     */
    public static void readAndCacheMcDepth() {
        if (activeIntegration == null) return;
        VulkanBackend backend = activeIntegration.getBackend();
        if (backend != null) {
            backend.readAndCacheMcDepth();
        }
    }
}
