package com.braffolk.dhvulkan.compat;

#if MC_VER>=MC_1_21_1

import net.vulkanmod.vulkan.memory.buffer.VertexBuffer;
import net.vulkanmod.vulkan.memory.buffer.IndexBuffer;
import net.vulkanmod.vulkan.memory.buffer.Buffer;#else
import net.vulkanmod.vulkan.memory.VertexBuffer;
import net.vulkanmod.vulkan.memory.IndexBuffer;
import net.vulkanmod.vulkan.memory.Buffer;#endif

import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.memory.MemoryTypes;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import net.vulkanmod.vulkan.texture.VulkanImage;
import net.vulkanmod.vulkan.texture.VTextureSelector;

import java.nio.ByteBuffer;

/**
 * Single source of truth for ALL version-specific API differences.
 * NO other file should contain #if blocks.
 */
public final class Compat {

    // ========================= //
    // Cached reflection fields  //
    // ========================= //

    private static java.lang.reflect.Field vulkanImageIdField;
    private static java.lang.reflect.Field vulkanImageViewField;
    private static java.lang.reflect.Field vulkanImageLayoutField;
    private static java.lang.reflect.Field rendererCmdBufferField;

    // Pre-allocated native memory for drawIndexed VM 0.4.2 path
    // Avoids per-draw nmemAlloc/nmemFree (was ~400-1000× per frame)
    private static final long drawBufPtr = org.lwjgl.system.MemoryUtil.nmemAllocChecked(8);
    private static final long drawOffPtr = org.lwjgl.system.MemoryUtil.nmemAllocChecked(8);

    // Cached method for setModelOffset (VM 0.6+ only)
    private static java.lang.reflect.Method setModelOffsetMethod;
    private static boolean setModelOffsetResolved = false;

    // Reusable float[3] for getCloudColorRGB — avoids per-frame allocation
    private static final float[] cloudColorResult = new float[3];
    private static java.lang.reflect.Field defaultMainPassAuxField;

    static {
        try {
            vulkanImageIdField = VulkanImage.class.getDeclaredField("id");
            vulkanImageIdField.setAccessible(true);
        } catch (Exception ignored) {}
        try {
            vulkanImageViewField = VulkanImage.class.getDeclaredField("mainImageView");
            vulkanImageViewField.setAccessible(true);
        } catch (Exception ignored) {}
        try {
            vulkanImageLayoutField = VulkanImage.class.getDeclaredField("currentLayout");
            vulkanImageLayoutField.setAccessible(true);
        } catch (Exception ignored) {}
        try {
            rendererCmdBufferField = Renderer.class.getDeclaredField("currentCmdBuffer");
            rendererCmdBufferField.setAccessible(true);
        } catch (Exception ignored) {}
        try {
            defaultMainPassAuxField = net.vulkanmod.vulkan.pass.DefaultMainPass.class.getDeclaredField("auxRenderPass");
            defaultMainPassAuxField.setAccessible(true);
        } catch (Exception ignored) {}
    }

    // ========================= //
    // VulkanMod detection       //
    // ========================= //

    private static final boolean VULKANMOD_ACTIVE = detectVulkanMod();

    private static boolean detectVulkanMod() {
        try {
            Class.forName("net.vulkanmod.vulkan.Renderer");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /** @return true if VulkanMod is loaded (no GL context available) */
    public static boolean isVulkanModActive() {
        return VULKANMOD_ACTIVE;
    }

    // ========================= //
    // Deferred composite hook   //
    // ========================= //

    /**
     * Static hook for Phase 2 deferred composite.
     * Set by MixinLodRenderer (dh24 module), called by MixinLevelRenderer (shared module).
     * This bridges the module boundary without requiring shared code to import dh24 types.
     */
    private static Runnable deferredCompositeHook;
    /** Per-frame flag: true if Phase 2a (addCloudsPass) already fired this frame. */
    private static boolean deferredCompositeRanThisFrame = false;

    public static void setDeferredCompositeHook(Runnable hook) {
        deferredCompositeHook = hook;
    }

    public static void runDeferredCompositeHook() {
        deferredCompositeRanThisFrame = true;
        Runnable hook = deferredCompositeHook;
        if (hook != null) hook.run();
    }

    /**
     * Static hook for Phase 2b late re-composite.
     * Set by MixinLodRenderer (dh24 module), called by MixinLevelRenderer (shared module)
     * at renderLevel @RETURN.
     *
     * On MC 1.20.6 where addCloudsPass doesn't exist, the deferred composite
     * hook is also called here as fallback.
     */
    private static Runnable lateCompositeHook;

    public static void setLateCompositeHook(Runnable hook) {
        lateCompositeHook = hook;
    }

    public static void runLateCompositeHook() {
        // If Phase 2a didn't fire (MC 1.20.6 — no addCloudsPass), run it now
        if (!deferredCompositeRanThisFrame) {
            Runnable deferred = deferredCompositeHook;
            if (deferred != null) deferred.run();
        }
        deferredCompositeRanThisFrame = false; // reset for next frame

        Runnable hook = lateCompositeHook;
        if (hook != null) hook.run();
    }

    // ========================= //
    // Buffer factories          //
    // ========================= //

    public static Object createVertexBuffer(int sizeBytes) {
        return new VertexBuffer(sizeBytes, MemoryTypes.HOST_MEM);
    }

    public static Object createGpuVertexBuffer(int sizeBytes) {
        return new VertexBuffer(sizeBytes, MemoryTypes.GPU_MEM);
    }

    public static Object createIndexBuffer(int sizeBytes) {
        #if MC_VER >= MC_1_21_1
        return new IndexBuffer(sizeBytes, MemoryTypes.HOST_MEM, IndexBuffer.IndexType.UINT32);
        #else
        return new IndexBuffer(sizeBytes, MemoryTypes.HOST_MEM);
        #endif
    }

    // ========================= //
    // Buffer operations //
    // ========================= //

    /**
     * Wait for the GPU to finish all in-flight work.
     * Must be called before freeing resources that may still be in use by
     * a previous frame. This is expensive — only use during cleanup/reinit,
     * never per-frame.
     */
    public static void waitDeviceIdle() {
        org.lwjgl.vulkan.VK10.vkDeviceWaitIdle(net.vulkanmod.vulkan.Vulkan.getVkDevice());
    }

    public static long getBufferId(Object buffer) {
        return ((Buffer) buffer).getId();
    }

    public static void scheduleFree(Object buffer) {
        #if MC_VER >= MC_1_21_1
        ((Buffer) buffer).scheduleFree();
        #else
        ((Buffer) buffer).freeBuffer();
        #endif
    }

    public static void copyBuffer(Object buffer, ByteBuffer data, int size) {
        #if MC_VER >= MC_1_21_1
        ((Buffer) buffer).copyBuffer(data, size);
        #else
        if (buffer instanceof VertexBuffer) {
            ((VertexBuffer) buffer).copyToVertexBuffer(size, 1, data);
        } else if (buffer instanceof IndexBuffer) {
            ((IndexBuffer) buffer).copyBuffer(data);
        }
        #endif
    }

    // ========================= //
    // Draw calls //
    // ========================= //

    public static void draw(Object vertexBuffer, int vertexCount) {
        Renderer.getDrawer().draw((VertexBuffer) vertexBuffer, vertexCount);
    }

    public static void drawIndexed(Object vertexBuffer, Object indexBuffer, int indexCount) {
        #if MC_VER >= MC_1_21_1
        Renderer.getInstance().getDrawer().drawIndexed(
                (Buffer) vertexBuffer, (IndexBuffer) indexBuffer, indexCount);
        #else
        // VM 0.4.2 Drawer.drawIndexed() hardcodes VK_INDEX_TYPE_UINT16 (= 0),
        // but DH uses UINT32 indices. Issue raw Vulkan commands with UINT32.
        org.lwjgl.vulkan.VkCommandBuffer cmd = Renderer.getCommandBuffer();
        VertexBuffer vb = (VertexBuffer) vertexBuffer;
        IndexBuffer ib = (IndexBuffer) indexBuffer;

        // Bind vertex buffer — uses pre-allocated static native pointers
        org.lwjgl.system.MemoryUtil.memPutLong(drawBufPtr, vb.getId());
        org.lwjgl.system.MemoryUtil.memPutLong(drawOffPtr, vb.getOffset());
        org.lwjgl.vulkan.VK10.nvkCmdBindVertexBuffers(cmd, 0, 1, drawBufPtr, drawOffPtr);

        // Bind index buffer with VK_INDEX_TYPE_UINT32 = 1
        org.lwjgl.vulkan.VK10.vkCmdBindIndexBuffer(cmd, ib.getId(), ib.getOffset(), 1);

        // Draw
        org.lwjgl.vulkan.VK10.vkCmdDrawIndexed(cmd, indexCount, 1, 0, 0, 0);
        #endif
    }

    // ========================= //
    // VertexFormatElement //
    // ========================= //

    public static VertexFormatElement vertexFormatElement(
            int id, int index,
            VertexFormatElement.Type type, VertexFormatElement.Usage usage,
            int count) {
        #if MC_VER >= MC_1_21_1
        return new VertexFormatElement(id, index, type, usage, count);
        #else
        // MC 1.20: VertexFormatElement(id, Type, Usage, count)
        // Non-UV usages MUST have id=0 (MC validates this at construction).
        // CRITICAL: Do NOT remap GENERIC→UV! VulkanMod's UV handler doesn't
        // support INT type, resulting in VK_FORMAT_UNDEFINED → DEVICE_LOST.
        // GENERIC with id=0 is valid and VulkanMod maps INT→VK_FORMAT_R32_SINT.
        if (usage == VertexFormatElement.Usage.UV) {
            return new VertexFormatElement(id, type, usage, count);
        }
        return new VertexFormatElement(0, type, usage, count);
        #endif
    }

    // ========================= //
    // VertexFormat builder //
    // ========================= //

    public static VertexFormat buildVertexFormat(String[] names, VertexFormatElement[] elements) {
        #if MC_VER >= MC_1_21_1
        VertexFormat.Builder builder = VertexFormat.builder();
        for (int i = 0; i < names.length; i++) {
            builder.add(names[i], elements[i]);
        }
        return builder.build();
        #else
        com.google.common.collect.ImmutableMap.Builder<String, VertexFormatElement> map =
                com.google.common.collect.ImmutableMap.builder();
        for (int i = 0; i < names.length; i++) {
            map.put(names[i], elements[i]);
        }
        return new VertexFormat(map.build());
        #endif
    }

    // ========================= //
    // Renderer / SwapChain //
    // ========================= //

    public static int getSwapChainWidth() {
        #if MC_VER >= MC_1_21_1
        return Renderer.getInstance().getSwapChain().getWidth();
        #else
        return net.vulkanmod.vulkan.Vulkan.getSwapChain().getWidth();
        #endif
    }

    public static int getSwapChainHeight() {
        #if MC_VER >= MC_1_21_1
        return Renderer.getInstance().getSwapChain().getHeight();
        #else
        return net.vulkanmod.vulkan.Vulkan.getSwapChain().getHeight();
        #endif
    }

    public static void beginRenderPass(
            net.vulkanmod.vulkan.framebuffer.RenderPass renderPass,
            net.vulkanmod.vulkan.framebuffer.Framebuffer framebuffer) {
        #if MC_VER >= MC_1_21_1
        Renderer.getInstance().beginRenderPass(renderPass, framebuffer);
        #else
        try {
            // End any currently active render pass first
            Renderer.getInstance().endRenderPass();

            org.lwjgl.vulkan.VkCommandBuffer cmdBuffer =
                    (org.lwjgl.vulkan.VkCommandBuffer) rendererCmdBufferField.get(Renderer.getInstance());
            org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush();
            framebuffer.beginRenderPass(cmdBuffer, renderPass, stack);
            stack.close();

            // CRITICAL: update Renderer's state so endRenderPass() works.
            // Without this, endRenderPass() sees boundFramebuffer==null and no-ops,
            // leaving nested render passes open → DEVICE_LOST on NVIDIA.
            Renderer.getInstance().setBoundFramebuffer(framebuffer);
            Renderer.getInstance().setBoundRenderPass(renderPass);
        } catch (Exception e) {
            throw new RuntimeException("[DH-Vulkan] Failed to begin render pass on VM 0.4.2", e);
        }
        #endif
    }

    /**
     * Rebind MC's main render target (swapchain) so the composite can draw into it.
     * On VM 0.6.1, DefaultMainPass.rebindMainTarget() handles state internally.
     * On VM 0.4.2, it calls swapChain.beginRenderPass() directly without updating
     * Renderer state — we must fix up boundFramebuffer/boundRenderPass afterward.
     */
    public static void rebindMainTarget() {
        net.vulkanmod.vulkan.pass.DefaultMainPass mainPass =
                (net.vulkanmod.vulkan.pass.DefaultMainPass) Renderer.getInstance().getMainPass();
        mainPass.rebindMainTarget();

        #if MC_VER < MC_1_21_1
        // VM 0.4.2: rebindMainTarget starts auxRenderPass on swapChain but doesn't
        // update Renderer state. Fix up so bindGraphicsPipeline/endRenderPass work.
        try {
            net.vulkanmod.vulkan.framebuffer.RenderPass auxPass =
                    (net.vulkanmod.vulkan.framebuffer.RenderPass) defaultMainPassAuxField.get(mainPass);
            Renderer.getInstance().setBoundFramebuffer(net.vulkanmod.vulkan.Vulkan.getSwapChain());
            Renderer.getInstance().setBoundRenderPass(auxPass);
        } catch (Exception e) {
            throw new RuntimeException("[DH-Vulkan] Failed to rebind main target on VM 0.4.2", e);
        }
        #endif
    }

    // ========================= //
    // Uniform setup //
    // ========================= //

    public static void addUniformWithBuffer(
            net.vulkanmod.vulkan.shader.layout.AlignedStruct.Builder builder,
            String type, String name, int count,
            java.util.function.Supplier<net.vulkanmod.vulkan.util.MappedBuffer> bufferSupplier) {
        #if MC_VER >= MC_1_21_1
        net.vulkanmod.vulkan.shader.layout.Uniform.Info info =
                net.vulkanmod.vulkan.shader.layout.Uniform.createUniformInfo(type, name, count);
        info.setBufferSupplier(bufferSupplier);
        builder.addUniformInfo(info);
        #else
        builder.addUniformInfo(type, name, count);
        #endif
    }

    // ========================= //
    // Push Constants            //
    // ========================= //

    /**
     * Compile-time constant: true on VM 0.6.1+, false on VM 0.4.2.
     * Used at init time only (shader preprocessing, pipeline creation).
     */
    public static boolean hasPushConstants() {
        #if MC_VER >= MC_1_21_1
        return true;
        #else
        return false;
        #endif
    }

    /**
     * Build a push constant block and attach it to the pipeline builder.
     * On VM 0.4.2: no-op (push constants not supported).
     *
     * @param pipelineBuilder the Pipeline.Builder being constructed
     * @param type   uniform type string (e.g. "float")
     * @param name   uniform name (e.g. "uModelOffset")
     * @param count  element count (e.g. 3 for vec3)
     * @param bufferSupplier supplier for the MappedBuffer backing this uniform
     */
    public static void buildAndSetPushConstants(
            net.vulkanmod.vulkan.shader.Pipeline.Builder pipelineBuilder,
            String type, String name, int count,
            java.util.function.Supplier<net.vulkanmod.vulkan.util.MappedBuffer> bufferSupplier) {
        #if MC_VER >= MC_1_21_1
        net.vulkanmod.vulkan.shader.layout.AlignedStruct.Builder pcBuilder =
                new net.vulkanmod.vulkan.shader.layout.AlignedStruct.Builder();
        net.vulkanmod.vulkan.shader.layout.Uniform.Info info =
                net.vulkanmod.vulkan.shader.layout.Uniform.createUniformInfo(type, name, count);
        info.setBufferSupplier(bufferSupplier);
        pcBuilder.addUniformInfo(info);
        try {
            java.lang.reflect.Field pcField =
                    net.vulkanmod.vulkan.shader.Pipeline.Builder.class.getDeclaredField("pushConstants");
            pcField.setAccessible(true);
            pcField.set(pipelineBuilder, pcBuilder.buildPushConstant());
        } catch (Exception e) {
            throw new RuntimeException("[DH-Vulkan] Failed to set push constants on pipeline builder", e);
        }
        #endif
    }

    /**
     * Per-draw state application:
     * - VM 0.6.1: issues vkCmdPushConstants (12 bytes, zero-copy to cmd buffer)
     * - VM 0.4.2: full uploadAndBindUBOs (descriptor set alloc + UBO copy)
     */
    public static void applyPerDrawState(net.vulkanmod.vulkan.shader.GraphicsPipeline pipeline) {
        #if MC_VER >= MC_1_21_1
        Renderer.getInstance().pushConstants(pipeline);
        #else
        Renderer.getInstance().uploadAndBindUBOs(pipeline);
        #endif
    }

    /**
     * On VM 0.4.2, Uniform.setSupplier() in the constructor only resolves MC's
     * built-in uniforms (ModelViewMat etc). Custom DH uniforms have values=null.
     * Call this AFTER buildUBO() to set suppliers on each Uniform.
     * On VM 0.6.1, this is a no-op (Info.setBufferSupplier handles it).
     */
    public static void setUniformSuppliers(
            net.vulkanmod.vulkan.shader.descriptor.UBO ubo,
            java.util.Map<String, net.vulkanmod.vulkan.util.MappedBuffer> bufferMap) {
        #if MC_VER < MC_1_21_1
        for (net.vulkanmod.vulkan.shader.layout.Uniform uniform : ubo.getUniforms()) {
            net.vulkanmod.vulkan.util.MappedBuffer mb = bufferMap.get(uniform.getName());
            if (mb != null) {
                uniform.setSupplier(() -> mb);
            }
        }
        #endif
    }

    // ========================= //
    // GlTexture / Lightmap //
    // ========================= //

    public static VulkanImage getLightmapVulkanImage() {
        try {
            #if MC_VER >= MC_1_21_1
            var lightmapView = net.minecraft.client.Minecraft.getInstance()
                    .gameRenderer.lightTexture().getTextureView();
            if (lightmapView == null) return null;
            com.mojang.blaze3d.opengl.GlTexture glTex =
                    (com.mojang.blaze3d.opengl.GlTexture) lightmapView.texture();
            net.vulkanmod.gl.VkGlTexture vkGlTex =
                    net.vulkanmod.gl.VkGlTexture.getTexture(glTex.glId());
            return vkGlTex != null ? vkGlTex.getVulkanImage() : null;
            #else
            // VM 0.4.2: MC already binds lightmap to slot 2 before our hook fires.
            // Just read it directly from VTextureSelector.
            return VTextureSelector.getBoundTexture(2);
            #endif
        } catch (Exception e) {
            return null;
        }
    }

    // ========================= //
    // Config value scaling //
    // ========================= //

    /**
     * DH 1.20.6 config returns noise intensity as an unscaled integer (e.g. 40 = 40%).
     * DH 1.21.1+ returns it as a pre-scaled 0-1 float. Normalize here.
     */
    public static float scaleNoiseIntensity(float raw) {
        #if MC_VER < MC_1_21_1
        return raw * 0.01f;
        #else
        return raw;
        #endif
    }

    // ========================= //
    // Swapchain access //
    // ========================= //

    /**
     * Get MC's swapchain depth attachment for depth-compared compositing.
     * Returns the VulkanImage backing MC's depth buffer.
     */
    public static VulkanImage getSwapChainDepthAttachment() {
        #if MC_VER >= MC_1_21_1
        return Renderer.getInstance().getSwapChain().getDepthAttachment();
        #else
        return net.vulkanmod.vulkan.Vulkan.getSwapChain().getDepthAttachment();
        #endif
    }

    // ========================= //
    // Depth-only sampling //
    // ========================= //

    /** Cached depth-only image view for D24S8 / D32S8 formats */
    private static long depthOnlyView = 0;
    private st
