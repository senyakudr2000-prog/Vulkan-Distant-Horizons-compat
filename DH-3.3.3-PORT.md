# Port to Distant Horizons 3.3.3 (MC 1.21.11, Fabric)

NOT tested in-game. Build it and check latest.log.

## Build
1. Create `jars/` in the project root and put in it:
   - `DistantHorizons-3.3.3-1.21.11-fabric-neoforge.jar`  (name must match `DistantHorizons-*-1.21.11-*.jar`)
   - `VulkanMod_1.21.11-<version>.jar`
2. `./gradlew :vulkan:build -PmcVer="1.21.11"`  ->  `vulkan/build/libs/`

## What changed vs. 2.4.0-3.0.0+vm.2
- DH 2.4 support removed (dh24 package/mixins, DhVersionDetector): those classes no longer exist in DH 3.3.x.
- VkRenderApiDefinition: getApiName() -> getEngineName(); added getDepthDirection (FORWARD_Z), getDepthRange
  (NEG_ONE_TO_POS_ONE), getRenderApi (VULKAN), getRenderingEngine (BLAZE_3D), isNativeRenderer (false),
  getAntiAliasRenderer (stub).
- IDhFogRenderer.render(RenderParams, DhApiFogRenderParam); IDhMetaRenderer.applyToMcTexture -> copyToMcTexture;
  ILodContainerUniformBufferWrapper.tryUpload(LodBufferContainer); IVertexBufferWrapper.getVertexCount();
  IDhGenericRenderer.close().
- Mat4f/Vec3f/Vec3d -> DhMat4f/DhVec3f/DhVec3d; RenderUniforms takes DhApiMat4f (no unsafe casts).
- Config: Graphics.Ssao.enableSsao -> Graphics.enableSsao; removed entries showRenderSectionToggling,
  enableInstancedRendering, glUploadMode; ConfigEntry.setApiValue(value, apiUserName).
- Mixin targets: loaderCommon.fabric.com.seibel... -> com.seibel... (DependencySetup, LightMapWrapper).
- fabric.mod.json: distanthorizons >=3.3.3; mod_version 3.3.3+vm.1.

## Known gaps
- New in DH 3.3: textured LODs (irisData / texture tile id), TAA/sharpen: ignored (flat-colour LODs).
- Possible conflicts between DH 3.3.3 mixins and VulkanMod (MixinProjectionMatrixBuffer,
  MixinChunkSectionsToRender, MixinLightTexture) were not checked.
