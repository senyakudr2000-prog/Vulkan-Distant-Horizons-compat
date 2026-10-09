package com.braffolk.dhvulkan.core.data;

import com.seibel.distanthorizons.api.objects.math.DhApiMat4f;
import com.seibel.distanthorizons.core.util.math.DhMat4f;
package com.braffolk.dhvulkan.bridge;

import com.braffolk.dhvulkan.core.VulkanBackend;

/**
 * Interface for DH version-specific integration.
 * Each DH version has its own implementation that wires
 * the VulkanBackend into DH's rendering pipeline.
 */
public interface DhIntegration {

    /** Called once at mod init to set up this integration path */
    void initialize(VulkanBackend backend);

    /** Get the underlying Vulkan backend */
    VulkanBackend getBackend();

    /** Human-readable name for logging */
    String getName();
}

/**
 * DH-agnostic uniform data for a single render frame.
 * The DH 3.x integration layer populates this from DH's render parameters.
 */
public class RenderUniforms {
    /** DH's projection matrix (extended near clip for high altitudes) */
    public final DhMat4f dhProjectionMatrix = new DhMat4f();

    /** DH's model-view matrix */
    public final DhMat4f dhModelViewMatrix = new DhMat4f();

    /** MC's projection matrix (for depth remapping in composite) */
    public final DhMat4f mcProjectionMatrix = new DhMat4f();

    /** World Y offset for terrain rendering */
    public double worldYOffset;

    /** Partial tick time for fog interpolation */
    public float partialTicks;

    /**
     * Set all fields from source matrices.
     * Callers should set worldYOffset and partialTicks directly.
     */
    public void set(DhApiMat4f dhProj, DhApiMat4f dhModelView, DhApiMat4f mcProj) {
        this.dhProjectionMatrix.set(dhProj);
        this.dhModelViewMatrix.set(dhModelView);
        this.mcProjectionMatrix.set(mcProj);
    }
}
