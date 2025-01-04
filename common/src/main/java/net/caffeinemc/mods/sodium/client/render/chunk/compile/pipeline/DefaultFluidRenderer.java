package net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline;


import net.caffeinemc.mods.sodium.api.util.ColorARGB;
import net.caffeinemc.mods.sodium.api.util.NormI8;
import net.caffeinemc.mods.sodium.client.model.color.ColorProvider;
import net.caffeinemc.mods.sodium.client.model.light.LightMode;
import net.caffeinemc.mods.sodium.client.model.light.LightPipeline;
import net.caffeinemc.mods.sodium.client.model.light.LightPipelineProvider;
import net.caffeinemc.mods.sodium.client.model.light.data.QuadLightData;
import net.caffeinemc.mods.sodium.client.model.quad.ModelQuad;
import net.caffeinemc.mods.sodium.client.model.quad.ModelQuadView;
import net.caffeinemc.mods.sodium.client.model.quad.ModelQuadViewMutable;
import net.caffeinemc.mods.sodium.client.model.quad.properties.ModelQuadFacing;
import net.caffeinemc.mods.sodium.client.model.quad.properties.ModelQuadFlags;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.buffers.ChunkModelBuilder;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.material.Material;
import net.caffeinemc.mods.sodium.client.render.chunk.translucent_sorting.TranslucentGeometryCollector;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import net.caffeinemc.mods.sodium.client.services.PlatformBlockAccess;
import net.caffeinemc.mods.sodium.client.util.DirectionUtil;
import net.caffeinemc.mods.sodium.client.world.LevelSlice;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.apache.commons.lang3.mutable.MutableFloat;
import org.apache.commons.lang3.mutable.MutableInt;

public class DefaultFluidRenderer {
    // TODO: allow this to be changed by vertex format, WARNING: make sure TranslucentGeometryCollector knows about EPSILON
    // TODO: move fluid rendering to a separate render pass and control glPolygonOffset and glDepthFunc to fix this properly
    public static final float EPSILON = 0.001f;
    private static final float ALIGNED_EQUALS_EPSILON = 0.011f;

    private final BlockPos.MutableBlockPos scratchPos = new BlockPos.MutableBlockPos();
    private final MutableFloat scratchHeight = new MutableFloat(0);
    private final MutableInt scratchSamples = new MutableInt();

    private final BlockOcclusionCache occlusionCache = new BlockOcclusionCache();

    private final ModelQuadViewMutable quad = new ModelQuad();

    private final LightPipelineProvider lighters;

    private final QuadLightData quadLightData = new QuadLightData();
    private final int[] quadColors = new int[4];
    private final float[] brightness = new float[4];

    private final ChunkVertexEncoder.Vertex[] vertices = ChunkVertexEncoder.Vertex.uninitializedQuad();

    public DefaultFluidRenderer(LightPipelineProvider lighters) {
        this.quad.setLightFace(Direction.UP);

        this.lighters = lighters;
    }

    private boolean isFullBlockFluidOccluded(BlockAndTintGetter world, BlockPos pos, Direction dir, BlockState blockState, FluidState fluid) {
        // check if this face of the fluid, assuming a full-block cull shape, is occluded by the block it's in or a neighboring block.
        // it doesn't do a voxel shape comparison with the neighboring blocks since that is already done by isSideExposed
        return !this.occlusionCache.shouldDrawFullBlockFluidSide(blockState, world, pos, dir, fluid, Shapes.block());
    }

    private boolean isSideExposed(BlockAndTintGetter world, int x, int y, int z, Direction dir, float height) {
        BlockPos pos = this.scratchPos.set(
            x + dir.getStepX(), 
            y + dir.getStepY(), 
            z + dir.getStepZ()
        );
        BlockState blockState = world.getBlockState(pos);

        // Early exit for non-occluding blocks
        if (!blockState.canOcclude()) {
            return true;
        }

        VoxelShape shape = blockState.getOcclusionShape();
        // Quick empty shape check
        if (shape.isEmpty()) {
            return true;
        }

        // Inline shape comparison to reduce allocations
        VoxelShape threshold = Shapes.box(0.0D, 0.0D, 0.0D, 1.0D, height, 1.0D);
        return !Shapes.blockOccudes(threshold, shape, dir);
    }

    public void render(LevelSlice level, BlockState blockState, FluidState fluidState, 
                   BlockPos blockPos, BlockPos offset, 
                   TranslucentGeometryCollector collector, 
                   ChunkModelBuilder meshBuilder, Material material, 
                   ColorProvider<FluidState> colorProvider, 
                   TextureAtlasSprite[] sprites) {
    // Extract coordinates using bitwise operations
    final int packedPos = packBlockPos(blockPos);
    final int posX = extractX(packedPos);
    final int posY = extractY(packedPos);
    final int posZ = extractZ(packedPos);

    Fluid fluid = fluidState.getType();

    // Calculate culling flags using bitwise operations
    int cullFlags = calculateCullFlags(level, blockPos, blockState, fluidState);
    
    // Early exit if fully occluded
    if (isTotallyOccluded(cullFlags)) {
        return;
    }

    boolean isWater = fluidState.is(FluidTags.WATER);
    float fluidHeight = this.fluidHeight(level, fluid, blockPos, Direction.UP);
    float[] heights = new float[4];

    // Calculate heights based on fluid state
    if (fluidHeight >= 1.0f) {
        Arrays.fill(heights, 1.0f);
    } else {
        heights[0] = calculateFluidCornerHeight(level, fluid, fluidHeight, blockPos);
        heights[1] = calculateFluidCornerHeight(level, fluid, fluidHeight, blockPos.south());
        heights[2] = calculateFluidCornerHeight(level, fluid, fluidHeight, blockPos.east());
        heights[3] = calculateFluidCornerHeight(level, fluid, fluidHeight, blockPos.north());
    }

    float yOffset = (cullFlags & 0b10) != 0 ? 0.0F : EPSILON; // Check cull down

    final ModelQuadViewMutable quad = this.quad;
    LightMode lightMode = isWater && Minecraft.useAmbientOcclusion() ? LightMode.SMOOTH : LightMode.FLAT;
    LightPipeline lighter = this.lighters.getLighter(lightMode);

    quad.setFlags(0);

    // Render the top face if not culled
    if ((cullFlags & 0b01) == 0 && this.isSideExposed(level, posX, posY, posZ, Direction.UP, 
        Math.min(Math.min(heights[0], heights[1]), Math.min(heights[2], heights[3])))) {
        
        // Adjust heights for rendering
        for (int i = 0; i < heights.length; i++) {
            heights[i] -= EPSILON;
        }

        Vec3 velocity = fluidState.getFlow(level, blockPos);
        float[] textureCoords = calculateTextureCoords(velocity, sprites);

        quad.setSprite(sprites[velocity.equals(Vec3.ZERO) ? 0 : 1]);

        // Set vertices based on calculated heights and texture coordinates
        setQuadVertices(quad, heights, textureCoords);

        this.updateQuad(quad, level, blockPos, lighter, Direction.UP, ModelQuadFacing.POS_Y, 1.0F, colorProvider, fluidState);
        this.writeQuad(meshBuilder, collector, material, offset, quad, ModelQuadFacing.POS_Y, false);

        if (fluidState.shouldRenderBackwardUpFace(level, this.scratchPos.set(posX, posY + 1, posZ))) {
            this.writeQuad(meshBuilder, collector, material, offset, quad, ModelQuadFacing.NEG_Y, true);
        }
    }

    // Render the bottom face if not culled
    if ((cullFlags & 0b10) == 0) {
        TextureAtlasSprite sprite = sprites[0];
        quad.setSprite(sprite);

        setVertex(quad, 0, 0.0f, yOffset, 1.0F, sprite.getU0(), sprite.getV1());
        setVertex(quad, 1, 0.0f, yOffset, 0.0f, sprite.getU0(), sprite.getV0());
        setVertex(quad, 2, 1.0F, yOffset, 0.0f, sprite.getU1(), sprite.getV0());
        setVertex(quad, 3, 1.0F, yOffset, 1.0F, sprite.getU1(), sprite.getV1());

        this.updateQuad(quad, level, blockPos, lighter, Direction.DOWN, ModelQuadFacing.NEG_Y, 1.0F, colorProvider, fluidState);
        this.writeQuad(meshBuilder, collector, material, offset, quad, ModelQuadFacing.NEG_Y, false);
    }

    quad.setFlags(ModelQuadFlags.IS_PARALLEL | ModelQuadFlags.IS_ALIGNED);

    for (Direction dir : DirectionUtil.HORIZONTAL_DIRECTIONS) {
        float c1, c2, x1, z1, x2, z2;

        switch (dir) {
            case NORTH -> {
                if ((cullFlags & 0b100) != 0) continue;
                c1 = heights[3]; // north-west height
                c2 = heights[0]; // north-east height
                x1 = 0.0f;
                x2 = 1.0F;
                z1 = EPSILON;
                z2 = z1;
            }
            case SOUTH -> {
                if ((cullFlags & 0b1000) != 0) continue;
                c1 = heights[1]; // south-east height
                c2 = heights[2]; // south-west height
                x1 = 1.0F;
                x2 = 0.0f;
                z1 = 1.0f - EPSILON;
                z2 = z1;
            }
            case WEST -> {
                if ((cullFlags & 0b10000) != 0) continue;
                c1 = heights[3]; // south-west height
                c2 = heights[0]; // north-west height
                x1 = EPSILON;
                x2 = x1;
                z1 = 1.0F;
                z2 = 0.0f;
            }
            case EAST -> {
                if ((cullFlags & 0b100000) != 0) continue;
                c1 = heights[2]; // north-east height
                c2 = heights[1]; // south-east height
                x1 = 1.0f - EPSILON;
                x2 = x1;
                z1 = 0.0f;
                z2 = 1.0F;
            }
        }

        if (this.isSideExposed(level, posX, posY, posZ, dir, Math.max(c1, c2))) {
            int adjX = posX + dir.getStepX();
            int adjY = posY + dir.getStepY();
            int adjZ = posZ + dir.getStepZ();

            TextureAtlasSprite sprite = sprites[1];
            boolean isOverlay = false;

            if (sprites.length > 2 && sprites[2] != null) {
                BlockPos adjPos = this.scratchPos.set(adjX, adjY, adjZ);
                BlockState adjBlock = level.getBlockState(adjPos);

                if (PlatformBlockAccess.getInstance().shouldShowFluidOverlay(adjBlock, level, adjPos, fluidState)) {
                    sprite = sprites[2];
                    isOverlay = true;
                }
            }

            float u1 = sprite.getU(0.0F);
            float u2 = sprite.getU(0.5F);
            float v1 = sprite.getV((1.0F - c1) * 0.5F);
            float v2 = sprite.getV((1.0F - c2) * 0.5F);
            float v3 = sprite.getV(0.5F);

            quad.setSprite(sprite);

            setVertex(quad, 0, x2, c2, z2, u2, v2);
            setVertex(quad, 1, x2, yOffset, z2, u2, v3);
            setVertex(quad, 2, x1, yOffset, z1, u1, v3);
            setVertex(quad, 3, x1, c1, z1, u1, v1);

            float br = dir.getAxis() == Direction.Axis.Z ? 0.8F : 0.6F;

            ModelQuadFacing facing = ModelQuadFacing.fromDirection(dir);

            this.updateQuad(quad, level, blockPos, lighter, dir, facing, br, colorProvider, fluidState);
            this.writeQuad(meshBuilder, collector, material, offset, quad, facing, false);

            if (!isOverlay) {
                this.writeQuad(meshBuilder, collector, material, offset, quad, facing.getOpposite(), true);
            }
        }
    }

    private static boolean isAlignedEquals(float a, float b) {
        return Math.abs(a - b) <= ALIGNED_EQUALS_EPSILON;
    }

    private void updateQuad(ModelQuadViewMutable quad, LevelSlice level, BlockPos pos, LightPipeline lighter, Direction dir, ModelQuadFacing facing, float brightness,
                            ColorProvider<FluidState> colorProvider, FluidState fluidState) {

        int normal;
        if (facing.isAligned()) {
            normal = facing.getPackedAlignedNormal();
        } else {
            normal = quad.calculateNormal();
        }

        quad.setFaceNormal(normal);

        QuadLightData light = this.quadLightData;

        lighter.calculate(quad, pos, light, null, dir, false, false);

        colorProvider.getColors(level, pos, scratchPos, fluidState, quad, this.quadColors);

        // multiply the per-vertex color against the combined brightness
        // the combined brightness is the per-vertex brightness multiplied by the block's brightness
        for (int i = 0; i < 4; i++) {
            this.quadColors[i] = ColorARGB.toABGR(this.quadColors[i]);
            this.brightness[i] = light.br[i] * brightness;
        }
    }

    private void writeQuad(ChunkModelBuilder builder, TranslucentGeometryCollector collector, Material material, BlockPos offset, ModelQuadView quad,
                           ModelQuadFacing facing, boolean flip) {
        var vertices = this.vertices;

        for (int i = 0; i < 4; i++) {
            var out = vertices[flip ? (3 - i + 1) & 0b11 : i];
            out.x = offset.getX() + quad.getX(i);
            out.y = offset.getY() + quad.getY(i);
            out.z = offset.getZ() + quad.getZ(i);

            out.color = this.quadColors[i];
            out.ao = this.brightness[i];
            out.u = quad.getTexU(i);
            out.v = quad.getTexV(i);
            out.light = this.quadLightData.lm[i];
        }

        TextureAtlasSprite sprite = quad.getSprite();

        if (sprite != null) {
            builder.addSprite(sprite);
        }

        if (material.isTranslucent() && collector != null) {
            int normal;

            if (facing.isAligned()) {
                normal = facing.getPackedAlignedNormal();
            } else {
                // This was updated earlier in updateQuad. There is no situation where the normal vector should have changed.
                normal = quad.getFaceNormal();
            }

            if (flip) {
                normal = NormI8.flipPacked(normal);
            }

            collector.appendQuad(normal, vertices, facing);
        }

        var vertexBuffer = builder.getVertexBuffer(facing);
        vertexBuffer.push(vertices, material);
    }

    private static void setVertex(ModelQuadViewMutable quad, int i, float x, float y, float z, float u, float v) {
        quad.setX(i, x);
        quad.setY(i, y);
        quad.setZ(i, z);
        quad.setTexU(i, u);
        quad.setTexV(i, v);
    }

    private float fluidCornerHeight(BlockAndTintGetter world, Fluid fluid, float fluidHeight, float fluidHeightX, float fluidHeightY, BlockPos blockPos) {
        if (fluidHeightY >= 1.0f || fluidHeightX >= 1.0f) {
            return 1.0f;
        }

        if (fluidHeightY > 0.0f || fluidHeightX > 0.0f) {
            float height = this.fluidHeight(world, fluid, blockPos, Direction.UP);

            if (height >= 1.0f) {
                return 1.0f;
            }

            this.modifyHeight(this.scratchHeight, this.scratchSamples, height);
        }

        this.modifyHeight(this.scratchHeight, this.scratchSamples, fluidHeight);
        this.modifyHeight(this.scratchHeight, this.scratchSamples, fluidHeightY);
        this.modifyHeight(this.scratchHeight, this.scratchSamples, fluidHeightX);

        float result = this.scratchHeight.floatValue() / this.scratchSamples.intValue();
        this.scratchHeight.setValue(0);
        this.scratchSamples.setValue(0);

        return result;
    }

    private void modifyHeight(MutableFloat totalHeight, MutableInt samples, float target) {
        if (target >= 0.8f) {
            totalHeight.add(target * 10.0f);
            samples.add(10);
        } else if (target >= 0.0f) {
            totalHeight.add(target);
            samples.increment();
        }
    }

    private float fluidHeight(BlockAndTintGetter world, Fluid fluid, BlockPos blockPos, Direction direction) {
        // Reduce method call overhead
        FluidState fluidState = world.getFluidState(blockPos);

        // Quick same fluid type check
        if (!fluid.isSame(fluidState.getType())) {
            return fluidState.getOwnHeight();
        }

        // Check adjacent fluid levels
        FluidState adjacentFluidState = world.getFluidState(blockPos.relative(direction));
    
        // Bitwise-friendly height determination
        return fluid.isSame(adjacentFluidState.getType()) ? 1.0f : fluidState.getOwnHeight();
    }
}
