package mine2blend;

import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.block.entity.BlockEntityRenderDispatcher;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.client.render.entity.EntityRenderDispatcher;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 通过一个“录制用”的 VertexConsumerProvider 让游戏把方块实体（箱子、告示牌、床、旗帜、模组家具……）
 * 和实体（物品展示框、盔甲架、掉落物、模组实体……）真正渲染一遍，把顶点截下来变成网格。
 * 必须在客户端渲染线程上调用。
 */
final class Capture implements VertexConsumerProvider {
    private static final Pattern TEXTURE = Pattern.compile("texture\\[Optional\\[([a-z0-9_.\\-]+:[a-z0-9_./\\-]+)\\]");
    private static final Pattern NAME = Pattern.compile("^RenderType\\[([^:\\]]+)");

    private final MeshOut out;
    private final Textures tex;
    private final Map<RenderLayer, VertexConsumer> consumers = new IdentityHashMap<>();
    int blockEntities, entities, failed;

    /** 方块渲染器输出的颜色里含有方向明暗和环境光遮蔽：灰色视为无染色 */
    private final boolean stripShade;

    Capture(MeshOut out, Textures tex) {
        this(out, tex, false);
    }

    Capture(MeshOut out, Textures tex, boolean stripShade) {
        this.out = out;
        this.tex = tex;
        this.stripShade = stripShade;
    }

    @Override
    public VertexConsumer getBuffer(RenderLayer layer) {
        return consumers.computeIfAbsent(layer, this::make);
    }

    private VertexConsumer make(RenderLayer layer) {
        VertexFormat.DrawMode mode = layer.getDrawMode();
        int prim = mode == VertexFormat.DrawMode.QUADS ? 4 : mode == VertexFormat.DrawMode.TRIANGLES ? 3 : 0;
        String desc = layer.toString();
        Matcher nm = NAME.matcher(desc);
        String name = nm.find() ? nm.group(1) : desc;
        String lname = name.toLowerCase();
        if (prim == 0 || lname.contains("text") || lname.contains("glint") || lname.contains("line")
                || lname.contains("crumbling") || lname.contains("outline") || lname.contains("shadow")
                || lname.contains("lightning") || lname.contains("end_portal") || lname.contains("end_gateway")) {
            return Noop.INSTANCE;
        }
        Matcher m = TEXTURE.matcher(desc);
        Identifier texId = m.find() ? Identifier.tryParse(m.group(1)) : null;
        if (texId != null && (texId.getPath().contains("font") || texId.getPath().contains("shadow"))) return Noop.INSTANCE;
        boolean translucent = lname.contains("translucent");
        return new Recorder(prim, texId, translucent, lname);
    }

    // ------------------------------------------------------------------ driving the renderers

    void captureBlockEntities(ClientWorld world, int x0, int y0, int z0, int x1, int y1, int z1) {
        MinecraftClient mc = MinecraftClient.getInstance();
        BlockEntityRenderDispatcher berd = mc.getBlockEntityRenderDispatcher();
        float td = mc.getTickDelta();
        for (int cx = x0 >> 4; cx <= x1 >> 4; cx++) {
            for (int cz = z0 >> 4; cz <= z1 >> 4; cz++) {
                if (!world.getChunkManager().isChunkLoaded(cx, cz)) continue;
                WorldChunk chunk = world.getChunk(cx, cz);
                List<BlockEntity> list = new ArrayList<>(chunk.getBlockEntities().values());
                for (BlockEntity be : list) {
                    BlockPos p = be.getPos();
                    if (p.getX() < x0 || p.getX() > x1 || p.getY() < y0 || p.getY() > y1 || p.getZ() < z0 || p.getZ() > z1) continue;
                    @SuppressWarnings("unchecked")
                    BlockEntityRenderer<BlockEntity> r = (BlockEntityRenderer<BlockEntity>) berd.get(be);
                    if (r == null) continue;
                    MatrixStack ms = new MatrixStack();
                    ms.translate(p.getX() - x0, p.getY() - y0, p.getZ() - z0);
                    out.at(net.minecraft.registry.Registries.BLOCK.getId(be.getCachedState().getBlock()).toString(),
                            p.getX() - x0, p.getY() - y0, p.getZ() - z0);
                    try {
                        r.render(be, td, ms, this, LightmapTextureManager.MAX_LIGHT_COORDINATE, OverlayTexture.DEFAULT_UV);
                        blockEntities++;
                    } catch (Throwable t) {
                        if (failed++ < 5) Mine2BlendClient.LOG.warn("[Mine2Blend] 方块实体渲染失败 {}", be.getType(), t);
                    }
                }
            }
        }
    }

    void captureEntities(ClientWorld world, int x0, int y0, int z0, int x1, int y1, int z1, boolean mobs) {
        MinecraftClient mc = MinecraftClient.getInstance();
        EntityRenderDispatcher erd = mc.getEntityRenderDispatcher();
        float td = mc.getTickDelta();
        Box box = new Box(x0, y0, z0, x1 + 1, y1 + 1, z1 + 1);
        List<Entity> list = world.getOtherEntities(null, box);
        boolean shadows = mc.options.getEntityShadows().getValue();
        erd.setRenderShadows(false);
        try {
            for (Entity e : list) {
                if (e instanceof PlayerEntity) continue;
                boolean decor = !(e instanceof LivingEntity) || e instanceof ArmorStandEntity;
                if (!decor && !mobs) continue;
                if (e.isInvisible() && !(e instanceof ArmorStandEntity)) continue;
                Vec3d p = e.getLerpedPos(td);
                MatrixStack ms = new MatrixStack();
                out.at("entity:" + net.minecraft.registry.Registries.ENTITY_TYPE.getId(e.getType()),
                        (int) Math.floor(p.x) - x0, (int) Math.floor(p.y) - y0, (int) Math.floor(p.z) - z0);
                try {
                    erd.render(e, p.x - x0, p.y - y0, p.z - z0, e.getYaw(td), td, ms, this, LightmapTextureManager.MAX_LIGHT_COORDINATE);
                    entities++;
                } catch (Throwable t) {
                    if (failed++ < 5) Mine2BlendClient.LOG.warn("[Mine2Blend] 实体渲染失败 {}", e.getType(), t);
                }
            }
        } finally {
            erd.setRenderShadows(shadows);
        }
    }

    // ------------------------------------------------------------------ vertex recorders

    private final class Recorder implements VertexConsumer {
        final int prim;
        final Identifier texId;
        final boolean translucent;
        final String layerName;
        final float[] px = new float[12], tuv = new float[8];
        final float[] xyz = new float[12], uvs = new float[8];
        int n;
        double x, y, z;
        int r = 255, g = 255, b = 255;
        float u, v;
        boolean fixed;
        int fr, fg, fb;
        int faceColor;
        boolean resolved;
        Textures.AtlasIndex atlas;
        String plainTexture;

        Recorder(int prim, Identifier texId, boolean translucent, String layerName) {
            this.prim = prim;
            this.texId = texId;
            this.translucent = translucent;
            this.layerName = layerName;
        }

        @Override
        public VertexConsumer vertex(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
            return this;
        }

        @Override
        public VertexConsumer color(int r, int g, int b, int a) {
            this.r = r;
            this.g = g;
            this.b = b;
            return this;
        }

        @Override
        public VertexConsumer texture(float u, float v) {
            this.u = u;
            this.v = v;
            return this;
        }

        @Override
        public VertexConsumer overlay(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer light(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer normal(float x, float y, float z) {
            return this;
        }

        @Override
        public void next() {
            if (n == 0) {
                faceColor = fixed ? (fr << 16 | fg << 8 | fb) : (r << 16 | g << 8 | b);
                if (stripShade) {
                    int cr = (faceColor >> 16) & 255, cg = (faceColor >> 8) & 255, cb = faceColor & 255;
                    int mx = Math.max(cr, Math.max(cg, cb)), mn = Math.min(cr, Math.min(cg, cb));
                    if (mx - mn < 12 || mx == 0) faceColor = 0xFFFFFF;
                    else { // 去掉明暗：把最亮通道拉回原值附近，并量化以减少材质数量
                        float k = 255f / mx;
                        cr = Math.min(255, Math.round(cr * k / 16) * 16);
                        cg = Math.min(255, Math.round(cg * k / 16) * 16);
                        cb = Math.min(255, Math.round(cb * k / 16) * 16);
                        faceColor = cr << 16 | cg << 8 | cb;
                    }
                }
            }
            px[n * 3] = (float) x;
            px[n * 3 + 1] = (float) y;
            px[n * 3 + 2] = (float) z;
            tuv[n * 2] = u;
            tuv[n * 2 + 1] = v;
            r = g = b = 255;
            if (++n == prim) {
                flush();
                n = 0;
            }
        }

        @Override
        public void fixedColor(int r, int g, int b, int a) {
            fixed = true;
            fr = r;
            fg = g;
            fb = b;
        }

        @Override
        public void unfixColor() {
            fixed = false;
        }

        private void flush() {
            if (!resolved) {
                resolved = true;
                if (texId != null) {
                    atlas = tex.atlas(texId);
                    if (atlas == null) plainTexture = tex.resource(texId);
                }
            }
            System.arraycopy(px, 0, xyz, 0, prim * 3);
            String name = null;
            if (atlas != null) {
                float cu = 0, cv = 0;
                for (int i = 0; i < prim; i++) {
                    cu += tuv[i * 2];
                    cv += tuv[i * 2 + 1];
                }
                Sprite sp = atlas.find(cu / prim, cv / prim);
                if (sp != null) {
                    name = tex.sprite(sp);
                    float u0 = sp.getMinU(), du = sp.getMaxU() - u0, v0 = sp.getMinV(), dv = sp.getMaxV() - v0;
                    for (int i = 0; i < prim; i++) {
                        uvs[i * 2] = du == 0 ? 0 : (tuv[i * 2] - u0) / du;
                        uvs[i * 2 + 1] = dv == 0 ? 0 : (tuv[i * 2 + 1] - v0) / dv;
                    }
                }
            } else if (plainTexture != null) {
                name = plainTexture;
                System.arraycopy(tuv, 0, uvs, 0, prim * 2);
            }
            int mat;
            if (name != null) {
                mat = out.mat(name, name, true, translucent, 0);
            } else {
                String key = "untextured:" + (texId != null ? texId : layerName);
                mat = out.mat(key, null, false, translucent, 0);
                System.arraycopy(tuv, 0, uvs, 0, prim * 2);
            }
            out.face(prim, xyz, uvs, mat, faceColor);
        }
    }

    private static final class Noop implements VertexConsumer {
        static final Noop INSTANCE = new Noop();

        @Override
        public VertexConsumer vertex(double x, double y, double z) {
            return this;
        }

        @Override
        public VertexConsumer color(int r, int g, int b, int a) {
            return this;
        }

        @Override
        public VertexConsumer texture(float u, float v) {
            return this;
        }

        @Override
        public VertexConsumer overlay(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer light(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer normal(float x, float y, float z) {
            return this;
        }

        @Override
        public void next() {}

        @Override
        public void fixedColor(int r, int g, int b, int a) {}

        @Override
        public void unfixColor() {}
    }
}
