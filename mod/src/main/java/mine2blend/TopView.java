package mine2blend;

import net.fabricmc.fabric.api.client.render.fluid.v1.FluidRenderHandler;
import net.fabricmc.fabric.api.client.render.fluid.v1.FluidRenderHandlerRegistry;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.texture.SpriteContents;
import net.minecraft.fluid.FluidState;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.BlockRenderView;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.ColorResolver;
import net.minecraft.world.chunk.light.LightingProvider;
import mine2blend.mixin.SpriteContentsAccessor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 生成游戏俯视地图图片：每列取顶面方块的真实贴图（含群系染色），并按高度差做地图式明暗。 */
final class TopView {
    record Result(byte[] png, int minH, int maxH) {}

    private record Top(NativeImage img, int w, int h, int tintIndex, boolean fluid, int avg) {}

    private static final Textures TEX = new Textures();
    private final BlockState[] palette;
    private final Top[] tops;
    private final Map<Long, Integer> tintCache = new HashMap<>();
    private final Map<RegistryEntry<Biome>, Integer> biomeIds = new HashMap<>();
    private final BiomeView view = new BiomeView();

    TopView(BlockState[] palette) {
        this.palette = palette;
        this.tops = new Top[palette.length];
        MinecraftClient mc = MinecraftClient.getInstance();
        Random rnd = Random.create(42L);
        for (int i = 1; i < palette.length; i++) {
            try {
                tops[i] = topOf(mc, palette[i], rnd);
            } catch (Throwable ignored) {
            }
        }
    }

    private Top topOf(MinecraftClient mc, BlockState st, Random rnd) {
        Sprite sp = null;
        int tint = -1;
        boolean fluid = false;
        FluidState fs = st.getFluidState();
        if (!fs.isEmpty() && !(st.getBlock() instanceof net.minecraft.block.Waterloggable)) {
            FluidRenderHandler h = FluidRenderHandlerRegistry.INSTANCE.get(fs.getFluid());
            if (h != null) {
                Sprite[] s = h.getFluidSprites(null, null, fs);
                if (s != null && s.length > 0) sp = s[0];
                fluid = true;
            }
        }
        if (sp == null) {
            BakedModel model = mc.getBlockRenderManager().getModel(st);
            // 从朝上的面和无剔除面里挑最高、朝上的那个；跳过面积为 0 和缺失贴图的面（有些模组模型里有看不见的退化面）
            BakedQuad best = null;
            float bestY = -1e9f;
            for (int pass = 0; pass < 2 && best == null; pass++) {
                for (Direction d : new Direction[]{Direction.UP, null}) {
                    for (BakedQuad q : model.getQuads(st, d, rnd)) {
                        if (BlockMesher.degenerate(q)) continue;
                        if (pass == 0 && BlockMesher.isMissing(TEX.quadSprite(q))) continue;
                        int[] vd = q.getVertexData();
                        int stride = vd.length / 4;
                        float y = 0;
                        for (int i = 0; i < 4; i++) y += Float.intBitsToFloat(vd[i * stride + 1]);
                        if (q.getFace() == Direction.UP) y += 100;
                        if (y > bestY) {
                            bestY = y;
                            best = q;
                        }
                    }
                }
            }
            if (best != null) {
                sp = TEX.quadSprite(best);
                if (best.hasColor()) tint = best.getColorIndex();
            } else {
                return null; // 空模型（如多格方块的 blank 部分）不画
            }
        }
        if (sp == null) return null;
        SpriteContents c = sp.getContents();
        NativeImage img = ((SpriteContentsAccessor) c).mine2blend$getImage();
        if (img == null) return null;
        int w = Math.min(c.getWidth(), img.getWidth()), h = Math.min(c.getHeight(), img.getHeight());
        long r = 0, g = 0, b = 0, n = 0;
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                int p = img.getColor(x, y);
                if ((p >>> 24) < 128) continue;
                r += p & 255;
                g += (p >> 8) & 255;
                b += (p >> 16) & 255;
                n++;
            }
        int avg = n == 0 ? 0xFF808080 : (0xFF << 24) | ((int) (b / n) << 16) | ((int) (g / n) << 8) | (int) (r / n);
        return new Top(img, w, h, fluid ? 0 : tint, fluid, avg);
    }

    private int tint(int pid, RegistryEntry<Biome> biome, int x, int z) {
        Top t = tops[pid];
        if (t == null || t.tintIndex < 0) return 0xFFFFFF;
        int bid = biomeIds.computeIfAbsent(biome, k -> biomeIds.size());
        long key = ((long) pid << 20) | bid;
        Integer c = tintCache.get(key);
        if (c != null) return c;
        view.biome = biome;
        BlockPos pos = new BlockPos(x, 64, z);
        int col = -1;
        try {
            if (t.fluid) {
                FluidRenderHandler h = FluidRenderHandlerRegistry.INSTANCE.get(palette[pid].getFluidState().getFluid());
                if (h != null) col = h.getFluidColor(view, pos, palette[pid].getFluidState());
            } else {
                col = MinecraftClient.getInstance().getBlockColors().getColor(palette[pid], view, pos, t.tintIndex);
            }
        } catch (Throwable ignored) {
        }
        if (col == -1) col = 0xFFFFFF;
        tintCache.put(key, col);
        return col;
    }

    Result render(short[] heights, short[] ids, RegistryEntry<Biome>[] biomes, int nx, int nz, int step, int ppb, int minX, int minZ, int bottom) throws java.io.IOException {
        int minH = Integer.MAX_VALUE, maxH = Integer.MIN_VALUE;
        for (short h : heights) {
            if (h == Short.MIN_VALUE || h < bottom) continue;
            minH = Math.min(minH, h);
            maxH = Math.max(maxH, h);
        }
        if (minH == Integer.MAX_VALUE) minH = maxH = 0;

        try (NativeImage out = new NativeImage(nx * ppb, nz * ppb, true)) {
            for (int iz = 0; iz < nz; iz++) {
                for (int ix = 0; ix < nx; ix++) {
                    int k = iz * nx + ix;
                    int hgt = heights[k];
                    Top t = hgt == Short.MIN_VALUE ? null : tops[ids[k] & 0xFFFF];
                    if (t == null) {
                        for (int j = 0; j < ppb; j++)
                            for (int i = 0; i < ppb; i++) out.setColor(ix * ppb + i, iz * ppb + j, 0);
                        continue;
                    }
                    int tint = tint(ids[k] & 0xFFFF, biomes[k], minX + ix * step, minZ + iz * step);
                    int north = iz > 0 && heights[k - nx] != Short.MIN_VALUE ? heights[k - nx] : hgt;
                    float shade = hgt > north ? 1.12f : hgt < north ? 0.80f : 1f;
                    float tr = ((tint >> 16) & 255) / 255f * shade, tg = ((tint >> 8) & 255) / 255f * shade, tb = (tint & 255) / 255f * shade;
                    for (int j = 0; j < ppb; j++) {
                        for (int i = 0; i < ppb; i++) {
                            int p = t.img.getColor(i * t.w / ppb, j * t.h / ppb);
                            if ((p >>> 24) < 128) p = t.avg;
                            int r = Math.min(255, (int) ((p & 255) * tr));
                            int g = Math.min(255, (int) (((p >> 8) & 255) * tg));
                            int b = Math.min(255, (int) (((p >> 16) & 255) * tb));
                            out.setColor(ix * ppb + i, iz * ppb + j, 0xFF000000 | (b << 16) | (g << 8) | r);
                        }
                    }
                }
            }
            return new Result(out.getBytes(), minH, maxH);
        }
    }

    /** 只提供群系颜色的视图（染色计算用） */
    private static final class BiomeView implements BlockRenderView {
        RegistryEntry<Biome> biome;

        @Override
        public float getBrightness(Direction direction, boolean shaded) {
            return 1f;
        }

        @Override
        public LightingProvider getLightingProvider() {
            return null;
        }

        @Override
        public int getColor(BlockPos pos, ColorResolver resolver) {
            return biome == null ? 0xFFFFFF : resolver.getColor(biome.value(), pos.getX(), pos.getZ());
        }

        @Override
        public BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            return SnapshotView.AIR;
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return SnapshotView.AIR.getFluidState();
        }

        @Override
        public int getHeight() {
            return 384;
        }

        @Override
        public int getBottomY() {
            return -64;
        }
    }
}
