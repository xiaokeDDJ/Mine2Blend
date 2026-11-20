package mine2blend;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.SharedConstants;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.BiomeKeys;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.dimension.DimensionType;
import mine2blend.BridgeServer.ApiException;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;

/** 从正在运行的世界读取方块。单人游戏读集成服务器的世界（可加载存档中未加载的区块），否则读客户端已加载区块。 */
public final class WorldReader {
    private WorldReader() {}

    static MinecraftClient mc() {
        return MinecraftClient.getInstance();
    }

    static <T> T onClient(Supplier<T> fn) throws Exception {
        return mc().submit(fn).get(600, TimeUnit.SECONDS);
    }

    /** 在拥有该世界的线程上执行（集成服务器线程或客户端渲染线程） */
    static <T> T onWorld(Function<World, T> fn) throws Exception {
        MinecraftClient mc = mc();
        RegistryKey<World> key = onClient(() -> mc.world == null ? null : mc.world.getRegistryKey());
        if (key == null) throw new ApiException("游戏当前不在世界中，请先进入存档");
        IntegratedServer srv = mc.getServer();
        if (srv != null) {
            ServerWorld sw = srv.getWorld(key);
            if (sw != null) return srv.submit(() -> fn.apply(sw)).get(600, TimeUnit.SECONDS);
        }
        return mc.submit(() -> fn.apply(mc.world)).get(600, TimeUnit.SECONDS);
    }

    // ------------------------------------------------------------------ status / selection

    static JsonObject status() throws Exception {
        return onClient(() -> {
            MinecraftClient mc = mc();
            JsonObject o = new JsonObject();
            o.addProperty("ok", true);
            o.addProperty("mod", "mine2blend");
            o.addProperty("modVersion", "1.0.0");
            o.addProperty("mcVersion", SharedConstants.getGameVersion().getName());
            o.addProperty("inWorld", mc.world != null && mc.player != null);
            if (mc.world != null && mc.player != null) {
                o.addProperty("dimension", mc.world.getRegistryKey().getValue().toString());
                o.addProperty("singleplayer", mc.getServer() != null);
                if (mc.getServer() != null) o.addProperty("worldName", mc.getServer().getSaveProperties().getLevelName());
                o.addProperty("minY", mc.world.getBottomY());
                o.addProperty("maxY", mc.world.getTopY() - 1);
                o.add("player", arr(mc.player.getBlockPos()));
                o.addProperty("yaw", mc.player.getYaw());
            }
            return o;
        });
    }

    static JsonObject selection() {
        JsonObject o = new JsonObject();
        o.addProperty("ok", true);
        BlockPos a = Selection.pos1, b = Selection.pos2;
        o.add("pos1", a == null ? null : arr(a));
        o.add("pos2", b == null ? null : arr(b));
        o.addProperty("dimension", Selection.dimension);
        return o;
    }

    static JsonObject setSelection(int x1, int y1, int z1, int x2, int y2, int z2) throws Exception {
        return onClient(() -> {
            MinecraftClient mc = mc();
            if (mc.world == null) throw new ApiException("游戏当前不在世界中");
            Selection.setBoth(new BlockPos(x1, y1, z1), new BlockPos(x2, y2, z2), mc.world.getRegistryKey().getValue().toString());
            if (mc.player != null) {
                int[] s = Selection.size();
                mc.player.sendMessage(Text.literal(String.format("§6[Mine2Blend]§r Blender 已设置选区 §b%d %d %d §r→ §b%d %d %d §7(%d×%d×%d)",
                        x1, y1, z1, x2, y2, z2, s[0], s[1], s[2])), false);
            }
            return selection();
        });
    }

    static JsonArray arr(BlockPos p) {
        JsonArray a = new JsonArray();
        a.add(p.getX());
        a.add(p.getY());
        a.add(p.getZ());
        return a;
    }

    // ------------------------------------------------------------------ chunk access

    /** 已加载 → 直接用；单人存档里已存在（region 文件里有）→ 从磁盘加载；从未生成 → null（当空气），不触发地形生成 */
    static final class ChunkSource {
        final World world;
        final Map<Long, Chunk> cache = new HashMap<>();
        final Map<Long, int[]> regionHeaders = new HashMap<>();
        Path regionDir;
        int missing;

        ChunkSource(World world) {
            this.world = world;
            if (world instanceof ServerWorld sw) {
                Path root = sw.getServer().getSavePath(WorldSavePath.ROOT);
                regionDir = DimensionType.getSaveDirectory(sw.getRegistryKey(), root).resolve("region");
            }
        }

        Chunk get(int cx, int cz) {
            long key = ((long) cx << 32) ^ (cz & 0xffffffffL);
            if (cache.containsKey(key)) return cache.get(key);
            Chunk c = load(cx, cz);
            if (c == null) missing++;
            cache.put(key, c);
            return c;
        }

        private Chunk load(int cx, int cz) {
            if (world instanceof ServerWorld sw) {
                Chunk loaded = sw.getChunkManager().getWorldChunk(cx, cz);
                if (loaded != null) return loaded;
                if (!existsOnDisk(cx, cz)) return null;
                return sw.getChunk(cx, cz);
            }
            return world.getChunkManager().isChunkLoaded(cx, cz) ? world.getChunk(cx, cz) : null;
        }

        private boolean existsOnDisk(int cx, int cz) {
            if (regionDir == null) return false;
            int rx = cx >> 5, rz = cz >> 5;
            long key = ((long) rx << 32) ^ (rz & 0xffffffffL);
            int[] header = regionHeaders.computeIfAbsent(key, k -> readHeader(regionDir.resolve("r." + rx + "." + rz + ".mca")));
            return header.length > 0 && header[(cx & 31) + (cz & 31) * 32] != 0;
        }

        private static int[] readHeader(Path p) {
            if (!Files.isRegularFile(p)) return new int[0];
            try (RandomAccessFile f = new RandomAccessFile(p.toFile(), "r")) {
                if (f.length() < 4096) return new int[0];
                int[] h = new int[1024];
                for (int i = 0; i < 1024; i++) h[i] = f.readInt();
                return h;
            } catch (IOException e) {
                return new int[0];
            }
        }

        RegistryEntry<Biome> biome(int x, int y, int z, RegistryEntry<Biome> fallback) {
            Chunk c = get(x >> 4, z >> 4);
            if (c == null) return fallback;
            try {
                return c.getBiomeForNoiseGen(x >> 2, y >> 2, z >> 2);
            } catch (Throwable t) {
                return fallback;
            }
        }
    }

    static RegistryEntry<Biome> plains(World w) {
        return w.getRegistryManager().get(RegistryKeys.BIOME).entryOf(BiomeKeys.PLAINS);
    }

    static final class PaletteBuilder {
        final Map<BlockState, Integer> index = new IdentityHashMap<>();
        final List<BlockState> states = new ArrayList<>();

        PaletteBuilder() {
            states.add(Blocks.AIR.getDefaultState());
        }

        int idOf(BlockState st) {
            if (st.isAir()) return 0;
            Integer i = index.get(st);
            if (i != null) return i;
            int id = states.size();
            if (id >= 65535) throw new ApiException("方块种类过多（超过 65535）");
            states.add(st);
            index.put(st, id);
            return id;
        }
    }

    // ------------------------------------------------------------------ export

    static byte[] export(int x1, int y1, int z1, int x2, int y2, int z2, BlockMesher.Opts opts) throws Exception {
        int minX = Math.min(x1, x2), maxX = Math.max(x1, x2);
        int minZ = Math.min(z1, z2), maxZ = Math.max(z1, z2);
        int minYReq = Math.min(y1, y2), maxYReq = Math.max(y1, y2);
        long t0 = System.currentTimeMillis();

        // 1) 世界线程：拍快照（外扩 1 格用于剔除相邻面）
        Object[] snap = onWorld(w -> {
            int minY = Math.max(minYReq, w.getBottomY());
            int maxY = Math.min(maxYReq, w.getTopY() - 1);
            if (maxY < minY) throw new ApiException("选区的 Y 范围不在世界高度内");
            int sx = maxX - minX + 1, sy = maxY - minY + 1, sz = maxZ - minZ + 1;
            long vol = (long) sx * sy * sz;
            if (vol > Mine2BlendClient.CONFIG.maxVolume)
                throw new ApiException(String.format("选区过大: %,d 方块（上限 %,d，可在 config/mine2blend.json 修改 maxVolume）", vol, Mine2BlendClient.CONFIG.maxVolume));

            int px0 = minX - 1, py0 = minY - 1, pz0 = minZ - 1;
            int psx = sx + 2, psy = sy + 2, psz = sz + 2;
            short[] ids = new short[psx * psy * psz];
            PaletteBuilder pal = new PaletteBuilder();
            ChunkSource src = new ChunkSource(w);
            BlockPos.Mutable mp = new BlockPos.Mutable();
            int wBottom = w.getBottomY(), wTop = w.getTopY() - 1;
            for (int cx = px0 >> 4; cx <= (px0 + psx - 1) >> 4; cx++) {
                for (int cz = pz0 >> 4; cz <= (pz0 + psz - 1) >> 4; cz++) {
                    Chunk c = src.get(cx, cz);
                    if (c == null) continue;
                    int xa = Math.max(px0, cx << 4), xb = Math.min(px0 + psx - 1, (cx << 4) + 15);
                    int za = Math.max(pz0, cz << 4), zb = Math.min(pz0 + psz - 1, (cz << 4) + 15);
                    for (int y = Math.max(py0, wBottom); y <= Math.min(py0 + psy - 1, wTop); y++) {
                        boolean padY = y < minY || y > maxY;
                        int si = c.getSectionIndex(y);
                        if (si >= 0 && si < c.getSectionArray().length && c.getSectionArray()[si].isEmpty()) continue;
                        for (int z = za; z <= zb; z++) {
                            boolean padZ = z < minZ || z > maxZ;
                            int base = ((y - py0) * psz + (z - pz0)) * psx - px0;
                            for (int x = xa; x <= xb; x++) {
                                boolean pad = padY || padZ || x < minX || x > maxX;
                                if (pad && !opts.cullBoundary()) continue;
                                BlockState st = c.getBlockState(mp.set(x, y, z));
                                if (st.isAir()) continue;
                                ids[base + x] = (short) pal.idOf(st);
                            }
                        }
                    }
                }
            }
            // 群系（每 4×4 列一个，取选区中部高度）
            int bx = (psx + 3) / 4, bz = (psz + 3) / 4;
            @SuppressWarnings("unchecked")
            RegistryEntry<Biome>[] biomes = new RegistryEntry[bx * bz];
            RegistryEntry<Biome> fallback = plains(w);
            int midY = (minY + maxY) / 2;
            for (int j = 0; j < bz; j++)
                for (int i = 0; i < bx; i++)
                    biomes[j * bx + i] = src.biome(px0 + i * 4 + 1, midY, pz0 + j * 4 + 1, fallback);

            SnapshotView view = new SnapshotView(px0, py0, pz0, psx, psy, psz, ids,
                    pal.states.toArray(new BlockState[0]), biomes, bx, bz, w.getBottomY(), w.getHeight(), w.getLightingProvider());
            return new Object[]{view, new int[]{minY, sx, sy, sz, src.missing}};
        });
        SnapshotView view = (SnapshotView) snap[0];
        int[] dims = (int[]) snap[1];
        int minY = dims[0], sx = dims[1], sy = dims[2], sz = dims[3];
        long t1 = System.currentTimeMillis();

        // 2) 当前（接口工作）线程：用烘焙模型建网格，不占用游戏线程
        MeshOut out = new MeshOut();
        Textures tex = new Textures();
        BlockMesher mesher = new BlockMesher(view, out, tex, opts, minX, minY, minZ);
        mesher.run(sx, sy, sz);
        int blockFaces = out.faces();
        long t2 = System.currentTimeMillis();

        // 3) 渲染线程：录制方块实体和实体
        int[] capStats = new int[3];
        if (opts.blockEntities() || opts.entities()) {
            onClient(() -> {
                MinecraftClient mc = mc();
                if (mc.world == null) return null;
                Capture cap = new Capture(out, tex);
                int mx = minX + sx - 1, my = minY + sy - 1, mz = minZ + sz - 1;
                if (opts.blockEntities()) cap.captureBlockEntities(mc.world, minX, minY, minZ, mx, my, mz);
                if (opts.entities()) cap.captureEntities(mc.world, minX, minY, minZ, mx, my, mz, opts.mobs());
                capStats[0] = cap.blockEntities;
                capStats[1] = cap.entities;
                capStats[2] = cap.failed;
                return null;
            });
        }
        long t3 = System.currentTimeMillis();

        JsonObject h = new JsonObject();
        h.addProperty("ok", true);
        JsonArray origin = new JsonArray();
        origin.add(minX);
        origin.add(minY);
        origin.add(minZ);
        h.add("origin", origin);
        JsonArray size = new JsonArray();
        size.add(sx);
        size.add(sy);
        size.add(sz);
        h.add("size", size);
        JsonObject stats = new JsonObject();
        stats.addProperty("blockFaces", blockFaces);
        stats.addProperty("capturedFaces", out.faces() - blockFaces);
        stats.addProperty("blockEntities", capStats[0]);
        stats.addProperty("entities", capStats[1]);
        stats.addProperty("failed", mesher.failed + capStats[2]);
        stats.addProperty("spriteFixed", tex.mismatched);
        stats.addProperty("dynamicModels", mesher.dynamic);
        stats.addProperty("missingChunks", dims[4]);
        stats.addProperty("msSnapshot", t1 - t0);
        stats.addProperty("msMesh", t2 - t1);
        stats.addProperty("msCapture", t3 - t2);
        h.add("stats", stats);
        h.add("materials", out.materialsJson());
        JsonArray blockArr = new JsonArray();
        for (String b : out.blocks) blockArr.add(b);
        h.add("blocks", blockArr);
        return packMesh(h, out, tex);
    }

    private static byte[] packMesh(JsonObject h, MeshOut out, Textures tex) {
        int nv = out.pos.size() / 3, nf = out.faces();
        Map<String, byte[]> pngs = new java.util.LinkedHashMap<>();
        for (MeshOut.Mat m : out.mats)
            if (m.texture != null && tex.png.get(m.texture) != null) pngs.put(m.texture, tex.png.get(m.texture));

        long off = 0;
        JsonObject sec = new JsonObject();
        off = section(sec, "pos", off, nv * 12L);
        off = section(sec, "uv", off, nv * 8L);
        off = section(sec, "faceSize", off, align4(nf));
        off = section(sec, "faceMat", off, nf * 4L);
        off = section(sec, "faceColor", off, nf * 4L);
        off = section(sec, "faceBlock", off, nf * 4L);
        off = section(sec, "facePos", off, nf * 12L);
        JsonArray texArr = new JsonArray();
        List<byte[]> blobs = new ArrayList<>();
        for (Map.Entry<String, byte[]> e : pngs.entrySet()) {
            JsonObject t = new JsonObject();
            t.addProperty("name", e.getKey());
            t.addProperty("offset", off);
            t.addProperty("length", e.getValue().length);
            off += align4(e.getValue().length);
            blobs.add(e.getValue());
            byte[] strip = tex.animPng.get(e.getKey());
            for (String kind : new String[]{"normal", "specular"}) {
                byte[] extra = (kind.equals("normal") ? tex.normalPng : tex.specularPng).get(e.getKey());
                if (extra == null) continue;
                JsonObject x = new JsonObject();
                x.addProperty("offset", off);
                x.addProperty("length", extra.length);
                off += align4(extra.length);
                blobs.add(extra);
                t.add(kind, x);
            }
            if (strip != null) {
                JsonObject a = tex.animInfo.get(e.getKey()).deepCopy();
                a.addProperty("offset", off);
                a.addProperty("length", strip.length);
                off += align4(strip.length);
                blobs.add(strip);
                t.add("anim", a);
            }
            texArr.add(t);
        }
        h.add("sections", sec);
        h.add("textures", texArr);
        h.addProperty("verts", nv);
        h.addProperty("faces", nf);
        byte[] hb = h.toString().getBytes(StandardCharsets.UTF_8);
        int hlen = align4(hb.length);
        long total = 4L + hlen + off;
        if (total > Integer.MAX_VALUE - 16) throw new ApiException("导出数据超过 2GB，请缩小选区");

        ByteBuffer buf = ByteBuffer.allocate((int) total).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(hlen);
        buf.put(hb);
        while (buf.position() < 4 + hlen) buf.put((byte) ' ');
        for (int i = 0; i < nv * 3; i++) buf.putFloat(out.pos.getFloat(i));
        for (int i = 0; i < nv * 2; i++) buf.putFloat(out.uv.getFloat(i));
        for (int i = 0; i < nf; i++) buf.put(out.faceSize.getByte(i));
        for (int i = nf; i < align4(nf); i++) buf.put((byte) 0);
        for (int i = 0; i < nf; i++) buf.putInt(out.faceMat.getInt(i));
        for (int i = 0; i < nf; i++) buf.putInt(out.faceColor.getInt(i));
        for (int i = 0; i < nf; i++) buf.putInt(out.faceBlock.getInt(i));
        for (int i = 0; i < nf * 3; i++) buf.putInt(out.facePos.getInt(i));
        for (byte[] png : blobs) {
            buf.put(png);
            for (int i = png.length; i < align4(png.length); i++) buf.put((byte) 0);
        }
        return buf.array();
    }

    static int align4(int n) {
        return (n + 3) & ~3;
    }

    static long section(JsonObject sec, String name, long off, long len) {
        JsonArray a = new JsonArray();
        a.add(off);
        a.add(len);
        sec.add(name, a);
        return off + len;
    }

    /** 头部 JSON + 若干二进制段 */
    static byte[] pack(JsonObject h, byte[]... blobs) {
        JsonObject sec = new JsonObject();
        long off = 0;
        for (int i = 0; i < blobs.length; i++) off = section(sec, "b" + i, off, align4(blobs[i].length));
        h.add("sections", sec);
        byte[] hb = h.toString().getBytes(StandardCharsets.UTF_8);
        int hlen = align4(hb.length);
        ByteBuffer buf = ByteBuffer.allocate((int) (4 + hlen + off)).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(hlen);
        buf.put(hb);
        while (buf.position() < 4 + hlen) buf.put((byte) ' ');
        for (byte[] b : blobs) {
            buf.put(b);
            for (int i = b.length; i < align4(b.length); i++) buf.put((byte) 0);
        }
        return buf.array();
    }

    // ------------------------------------------------------------------ top-down map

    static byte[] topview(int x1, int z1, int x2, int z2, int step, int ppb) throws Exception {
        int minX = Math.min(x1, x2), maxX = Math.max(x1, x2);
        int minZ = Math.min(z1, z2), maxZ = Math.max(z1, z2);
        int nx = (maxX - minX) / step + 1, nz = (maxZ - minZ) / step + 1;
        ppb = Math.max(1, Math.min(16, ppb));
        if ((long) nx * nz * ppb * ppb > 64L * 1024 * 1024) throw new ApiException("预览图过大，请减小半径、增大采样间隔或减小每格像素");

        Object[] res = onWorld(w -> {
            PaletteBuilder pal = new PaletteBuilder();
            short[] heights = new short[nx * nz];
            short[] ids = new short[nx * nz];
            @SuppressWarnings("unchecked")
            RegistryEntry<Biome>[] biomes = new RegistryEntry[nx * nz];
            ChunkSource src = new ChunkSource(w);
            RegistryEntry<Biome> fallback = plains(w);
            BlockPos.Mutable mp = new BlockPos.Mutable();
            int bottom = w.getBottomY();
            for (int iz = 0; iz < nz; iz++) {
                for (int ix = 0; ix < nx; ix++) {
                    int x = minX + ix * step, z = minZ + iz * step;
                    int k = iz * nx + ix;
                    Chunk c = src.get(x >> 4, z >> 4);
                    if (c == null) {
                        heights[k] = Short.MIN_VALUE;
                        continue;
                    }
                    int top = c.sampleHeightmap(Heightmap.Type.WORLD_SURFACE, x & 15, z & 15);
                    BlockState st = top >= bottom ? c.getBlockState(mp.set(x, top, z)) : Blocks.AIR.getDefaultState();
                    // 跳过顶上的花草等无碰撞装饰，取下面的地面
                    for (int i = 0; i < 4 && top > bottom && st.getFluidState().isEmpty()
                            && st.getCollisionShape(w, mp).isEmpty() && !st.isAir(); i++) {
                        BlockState below = c.getBlockState(mp.set(x, top - 1, z));
                        if (below.isAir()) break;
                        top--;
                        st = below;
                    }
                    heights[k] = (short) top;
                    ids[k] = (short) pal.idOf(st);
                    biomes[k] = src.biome(x, top, z, fallback);
                }
            }
            return new Object[]{pal.states.toArray(new BlockState[0]), heights, ids, biomes, bottom};
        });
        BlockState[] palette = (BlockState[]) res[0];
        short[] heights = (short[]) res[1];
        short[] ids = (short[]) res[2];
        @SuppressWarnings("unchecked")
        RegistryEntry<Biome>[] biomes = (RegistryEntry<Biome>[]) res[3];
        int bottom = (int) res[4];

        TopView.Result img = new TopView(palette).render(heights, ids, biomes, nx, nz, step, ppb, minX, minZ, bottom);

        JsonObject h = new JsonObject();
        h.addProperty("ok", true);
        h.addProperty("minX", minX);
        h.addProperty("minZ", minZ);
        h.addProperty("nx", nx);
        h.addProperty("nz", nz);
        h.addProperty("step", step);
        h.addProperty("ppb", ppb);
        h.addProperty("bottom", bottom);
        h.addProperty("minH", img.minH());
        h.addProperty("maxH", img.maxH());
        // b1: 每列顶面高度 int16（无数据 = -32768），供 Blender 地图窗口自动计算选区高度
        ByteBuffer hb = ByteBuffer.allocate(heights.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (short v : heights) hb.putShort(v);
        return pack(h, img.png(), hb.array());
    }
}
