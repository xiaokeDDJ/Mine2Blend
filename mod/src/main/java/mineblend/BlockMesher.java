package mineblend;

import net.fabricmc.fabric.api.client.render.fluid.v1.FluidRenderHandler;
import net.fabricmc.fabric.api.client.render.fluid.v1.FluidRenderHandlerRegistry;
import net.fabricmc.fabric.api.renderer.v1.model.FabricBakedModel;
import net.minecraft.block.AbstractPlantPartBlock;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.block.Block;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.CobwebBlock;
import net.minecraft.block.PlantBlock;
import net.minecraft.block.SugarCaneBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.block.BlockRenderManager;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.texture.Sprite;
import net.minecraft.fluid.FluidState;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.EmptyBlockView;
import net.minecraft.world.biome.Biome;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** 用游戏里真实的烘焙模型（含模组方块）生成方块网格；剔除规则与游戏相同（Block.shouldDrawSide）。 */
final class BlockMesher {
    static final Direction[] DIRS = Direction.values();
    /** 单位立方体每个面的 4 个角点（逆时针 = 朝外），按 Direction.ordinal(): DOWN UP NORTH SOUTH WEST EAST */
    static final float[][] FACE = {
            {0, 0, 0, 1, 0, 0, 1, 0, 1, 0, 0, 1},
            {0, 1, 0, 0, 1, 1, 1, 1, 1, 1, 1, 0},
            {0, 0, 0, 0, 1, 0, 1, 1, 0, 1, 0, 0},
            {0, 0, 1, 1, 0, 1, 1, 1, 1, 0, 1, 1},
            {0, 0, 0, 0, 0, 1, 0, 1, 1, 0, 1, 0},
            {1, 0, 0, 1, 1, 0, 1, 1, 1, 1, 0, 1},
    };

    record Opts(boolean plants, boolean fluids, boolean blockEntities, boolean entities, boolean mobs, boolean cullBoundary) {}

    private final SnapshotView view;
    private final MeshOut out;
    private final Textures tex;
    private final Opts opts;
    private final int ox, oy, oz; // 选区最小角（世界坐标）

    private final BlockRenderManager brm = MinecraftClient.getInstance().getBlockRenderManager();
    private final BlockColors colors = MinecraftClient.getInstance().getBlockColors();
    private final Map<Long, Integer> tintCache = new HashMap<>();
    private final Map<RegistryEntry<Biome>, Integer> biomeIds = new IdentityHashMap<>();
    private final float[] xyz = new float[12];
    private final float[] uvs = new float[8];

    int failed;

    BlockMesher(SnapshotView view, MeshOut out, Textures tex, Opts opts, int ox, int oy, int oz) {
        this.view = view;
        this.out = out;
        this.tex = tex;
        this.opts = opts;
        this.ox = ox;
        this.oy = oy;
        this.oz = oz;
    }

    /** 四边形面积几乎为 0（某个方向厚度为 0 的元素），游戏中不可见 */
    static boolean degenerate(BakedQuad q) {
        int[] vd = q.getVertexData();
        int st = vd.length / 4;
        float[] p = new float[12];
        for (int i = 0; i < 4; i++)
            for (int k = 0; k < 3; k++) p[i * 3 + k] = Float.intBitsToFloat(vd[i * st + k]);
        // 两个三角形的面积之和
        double a = triArea(p, 0, 1, 2) + triArea(p, 0, 2, 3);
        return a < 1e-6;
    }

    private static double triArea(float[] p, int a, int b, int c) {
        double ux = p[b * 3] - p[a * 3], uy = p[b * 3 + 1] - p[a * 3 + 1], uz = p[b * 3 + 2] - p[a * 3 + 2];
        double vx = p[c * 3] - p[a * 3], vy = p[c * 3 + 1] - p[a * 3 + 1], vz = p[c * 3 + 2] - p[a * 3 + 2];
        double cx = uy * vz - uz * vy, cy = uz * vx - ux * vz, cz = ux * vy - uy * vx;
        return Math.sqrt(cx * cx + cy * cy + cz * cz) / 2;
    }

    static boolean isMissing(Sprite sp) {
        return sp == null || sp.getContents().getId().equals(net.minecraft.client.texture.MissingSprite.getMissingSpriteId());
    }

    static boolean isPlant(Block b) {
        return b instanceof PlantBlock || b instanceof AbstractPlantPartBlock || b instanceof SugarCaneBlock || b instanceof CobwebBlock;
    }

    void run(int sx, int sy, int sz) {
        BlockState[] pal = view.palette;
        int n = pal.length;
        String[] blockIds = new String[n];
        String[] fluidIds = new String[n];
        for (int i = 0; i < n; i++) {
            blockIds[i] = net.minecraft.registry.Registries.BLOCK.getId(pal[i].getBlock()).toString();
            FluidState f = pal[i].getFluidState();
            fluidIds[i] = f.isEmpty() ? blockIds[i]
                    : net.minecraft.registry.Registries.BLOCK.getId(f.getBlockState().getBlock()).toString();
        }
        boolean[] opaque = new boolean[n];
        BakedModel[] models = new BakedModel[n];
        RenderLayer[] layers = new RenderLayer[n];
        for (int i = 0; i < n; i++) {
            BlockState st = pal[i];
            try {
                opaque[i] = !st.isAir() && st.isOpaqueFullCube(EmptyBlockView.INSTANCE, BlockPos.ORIGIN);
            } catch (Throwable t) {
                opaque[i] = false;
            }
            if (st.getRenderType() == BlockRenderType.MODEL) {
                models[i] = brm.getModel(st);
                layers[i] = RenderLayers.getBlockLayer(st);
            }
        }

        BlockPos.Mutable pos = new BlockPos.Mutable();
        BlockPos.Mutable npos = new BlockPos.Mutable();
        Random rnd = Random.create();
        short[] ids = view.ids;
        int psx = view.sx, psz = view.sz;
        int strideY = psx * psz;

        for (int y = 0; y < sy; y++) {
            for (int z = 0; z < sz; z++) {
                for (int x = 0; x < sx; x++) {
                    int idx = ((y + 1) * psz + (z + 1)) * psx + (x + 1);
                    int pid = ids[idx] & 0xFFFF;
                    BlockState st = pal[pid];
                    if (st.isAir()) continue;
                    if (opaque[pid]
                            && opaque[ids[idx + 1] & 0xFFFF] && opaque[ids[idx - 1] & 0xFFFF]
                            && opaque[ids[idx + psx] & 0xFFFF] && opaque[ids[idx - psx] & 0xFFFF]
                            && opaque[ids[idx + strideY] & 0xFFFF] && opaque[ids[idx - strideY] & 0xFFFF]) continue;

                    int wx = ox + x, wy = oy + y, wz = oz + z;
                    pos.set(wx, wy, wz);
                    try {
                        FluidState fs = st.getFluidState();
                        if (opts.fluids() && !fs.isEmpty()) {
                            out.at(fluidIds[pid], x, y, z);
                            fluid(fs, pos, npos, x, y, z);
                        }
                        out.at(blockIds[pid], x, y, z);

                        BakedModel model = models[pid];
                        if (model == null) continue;
                        if (model instanceof net.minecraft.client.render.model.WeightedBakedModel) out.markRandom();
                        if (!opts.plants() && isPlant(st.getBlock())) continue;
                        RenderLayer layer = layers[pid];
                        boolean alpha = layer != RenderLayer.getSolid();
                        boolean translucent = layer == RenderLayer.getTranslucent();
                        int light = st.getLuminance();
                        Vec3d off = st.getModelOffset(view, pos);
                        float bx = (float) (x + off.x), by = (float) (y + off.y), bz = (float) (z + off.z);
                        long seed = st.getRenderingSeed(pos);
                        boolean any = false;
                        for (int d = 0; d <= 6; d++) {
                            Direction dir = d < 6 ? DIRS[d] : null;
                            rnd.setSeed(seed);
                            List<BakedQuad> quads = model.getQuads(st, dir, rnd);
                            if (quads.isEmpty()) continue;
                            any = true;
                            if (dir != null && !Block.shouldDrawSide(st, view, pos, dir, npos.set(pos, dir))) continue;
                            for (BakedQuad q : quads) {
                                if (degenerate(q)) continue; // 面积为 0 的面游戏里看不见（常见于模组模型，且常带缺失贴图）
                                quad(q, st, pid, pos, bx, by, bz, alpha, translucent, light);
                            }
                        }
                        // 模型没有普通四边形：若是 Fabric 渲染 API 的动态模型，让游戏的方块渲染器画一遍再截取顶点；
                        // 否则（如 Yuushya 多格方块的空模型 blank）本来就不可见，什么都不生成。
                        if (!any && !((FabricBakedModel) model).isVanillaAdapter()) renderDynamic(st, model, pos, x, y, z, layer, seed);
                    } catch (Throwable t) {
                        if (failed++ < 5) MineBlendClient.LOG.warn("[MineBlend] 方块建模失败 {} @ {}", st, pos, t);
                    }
                }
            }
        }
    }

    private Capture dynCapture;

    private void renderDynamic(BlockState st, BakedModel model, BlockPos pos, int x, int y, int z, RenderLayer layer, long seed) {
        if (dynCapture == null) dynCapture = new Capture(out, tex, true);
        MatrixStack ms = new MatrixStack();
        ms.translate(x, y, z);
        brm.getModelRenderer().render(view, model, st, pos, ms, dynCapture.getBuffer(layer), true,
                Random.create(), seed, OverlayTexture.DEFAULT_UV);
        dynamic++;
    }

    int dynamic;

    private void quad(BakedQuad q, BlockState st, int pid, BlockPos pos, float bx, float by, float bz,
                      boolean alpha, boolean translucent, int light) {
        int[] vd = q.getVertexData();
        int stride = vd.length / 4;
        Sprite sp = tex.quadSprite(q);
        float u0 = sp.getMinU(), du = sp.getMaxU() - u0, v0 = sp.getMinV(), dv = sp.getMaxV() - v0;
        if (du == 0) du = 1;
        if (dv == 0) dv = 1;
        for (int i = 0; i < 4; i++) {
            int b = i * stride;
            xyz[i * 3] = Float.intBitsToFloat(vd[b]) + bx;
            xyz[i * 3 + 1] = Float.intBitsToFloat(vd[b + 1]) + by;
            xyz[i * 3 + 2] = Float.intBitsToFloat(vd[b + 2]) + bz;
            uvs[i * 2] = (Float.intBitsToFloat(vd[b + 4]) - u0) / du;
            uvs[i * 2 + 1] = (Float.intBitsToFloat(vd[b + 5]) - v0) / dv;
        }
        String name = tex.sprite(sp);
        int mat = out.mat(name, name, alpha, translucent, light);
        int color = q.hasColor() ? tint(st, pid, pos, q.getColorIndex()) : 0xFFFFFF;
        out.face(4, xyz, uvs, mat, color);
    }

    private int tint(BlockState st, int pid, BlockPos pos, int tintIndex) {
        RegistryEntry<Biome> b = view.biome(pos.getX(), pos.getZ());
        int bid = biomeIds.computeIfAbsent(b, k -> biomeIds.size());
        long key = ((long) pid << 32) | ((long) (tintIndex & 0xFFFF) << 16) | (bid & 0xFFFF);
        Integer c = tintCache.get(key);
        if (c != null) return c;
        int col;
        try {
            col = colors.getColor(st, view, pos, tintIndex);
        } catch (Throwable t) {
            col = -1;
        }
        if (col == -1) col = 0xFFFFFF;
        tintCache.put(key, col);
        return col;
    }

    private void fluid(FluidState fs, BlockPos pos, BlockPos.Mutable npos, int x, int y, int z) {
        FluidRenderHandler h = FluidRenderHandlerRegistry.INSTANCE.get(fs.getFluid());
        if (h == null) return;
        Sprite[] sprites = h.getFluidSprites(view, pos, fs);
        if (sprites == null || sprites.length == 0 || sprites[0] == null) return;
        int color = h.getFluidColor(view, pos, fs);
        if (color == -1) color = 0xFFFFFF;
        float height = fs.getHeight(view, pos);
        if (height <= 0) return;
        RenderLayer layer = RenderLayers.getFluidLayer(fs);
        boolean translucent = layer == RenderLayer.getTranslucent();
        int light = fs.getBlockState().getLuminance();
        for (Direction dir : DIRS) {
            npos.set(pos, dir);
            FluidState nf = view.getFluidState(npos);
            if (!nf.isEmpty() && nf.getFluid().matchesType(fs.getFluid())) continue;
            BlockState ns = view.getBlockState(npos);
            if (!(dir == Direction.UP && height < 1f) && ns.isOpaqueFullCube(view, npos)) continue;
            boolean side = dir.getAxis().isHorizontal();
            Sprite sp = side && sprites.length > 1 && sprites[1] != null ? sprites[1] : sprites[0];
            float uvScale = sp == sprites[0] ? 1f : 0.5f;
            float[] f = FACE[dir.ordinal()];
            for (int i = 0; i < 4; i++) {
                float cx = f[i * 3], cy = f[i * 3 + 1] * height, cz = f[i * 3 + 2];
                xyz[i * 3] = x + cx;
                xyz[i * 3 + 1] = y + cy;
                xyz[i * 3 + 2] = z + cz;
                faceUv(dir, cx, cy, cz, uvScale, i);
            }
            String name = tex.sprite(sp);
            int mat = out.mat(name, name, translucent, translucent, light);
            out.face(4, xyz, uvs, mat, color);
        }
    }

    /** 模型没有任何四边形（例如纯 Fabric 渲染 API 的模组模型）时，用轮廓碰撞箱 + 粒子贴图兜底 */
    private void fallbackBoxes(BlockState st, Sprite sprite, BlockPos pos, int x, int y, int z, boolean alpha, int light) {
        if (sprite == null) return;
        VoxelShape shape = st.getOutlineShape(view, pos);
        if (shape.isEmpty()) return;
        String name = tex.sprite(sprite);
        int mat = out.mat(name, name, alpha, false, light);
        int n = 0;
        for (Box b : shape.getBoundingBoxes()) {
            if (n++ > 32) break;
            for (Direction dir : DIRS) {
                float[] f = FACE[dir.ordinal()];
                for (int i = 0; i < 4; i++) {
                    float cx = (float) (f[i * 3] == 0 ? b.minX : b.maxX);
                    float cy = (float) (f[i * 3 + 1] == 0 ? b.minY : b.maxY);
                    float cz = (float) (f[i * 3 + 2] == 0 ? b.minZ : b.maxZ);
                    xyz[i * 3] = x + cx;
                    xyz[i * 3 + 1] = y + cy;
                    xyz[i * 3 + 2] = z + cz;
                    faceUv(dir, cx, cy, cz, 1f, i);
                }
                out.face(4, xyz, uvs, mat, 0xFFFFFF);
            }
        }
    }

    private void faceUv(Direction dir, float cx, float cy, float cz, float s, int i) {
        float u, v;
        switch (dir.getAxis()) {
            case Y -> {
                u = cx;
                v = cz;
            }
            case Z -> {
                u = cx;
                v = 1 - cy;
            }
            default -> {
                u = cz;
                v = 1 - cy;
            }
        }
        uvs[i * 2] = u * s;
        uvs[i * 2 + 1] = v * s;
    }
}
