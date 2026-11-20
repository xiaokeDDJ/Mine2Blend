package mine2blend;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import it.unimi.dsi.fastutil.bytes.ByteArrayList;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 导出的多边形网格（MC 坐标，相对选区最小角）。每个面的顶点连续存放，UV 为贴图局部坐标（v 向下）。 */
final class MeshOut {
    final FloatArrayList pos = new FloatArrayList();
    final FloatArrayList uv = new FloatArrayList();
    final ByteArrayList faceSize = new ByteArrayList();
    final IntArrayList faceMat = new IntArrayList();
    final IntArrayList faceColor = new IntArrayList(); // 0xRRGGBB 染色
    final IntArrayList faceBlock = new IntArrayList(); // 所属方块/实体类型（blocks 下标）
    final IntArrayList facePos = new IntArrayList();   // 所属方块坐标（相对选区最小角），3 个一组

    /** 方块/实体类型 id 列表，例如 minecraft:stone、entity:minecraft:armor_stand */
    final List<String> blocks = new ArrayList<>();
    private final Map<String, Integer> blockIndex = new HashMap<>();
    private int curBlock = -1, curX, curY, curZ;

    /** 之后生成的面都属于这个方块（或实体） */
    void at(String id, int x, int y, int z) {
        Integer i = blockIndex.get(id);
        if (i == null) {
            i = blocks.size();
            blocks.add(id);
            blockIndex.put(id, i);
        }
        curBlock = i;
        curX = x;
        curY = y;
        curZ = z;
    }

    static final class Mat {
        String key;      // 材质键 = 贴图名（或 "untextured:<id>"）
        String texture;  // 贴图名，null = 无贴图
        boolean alpha;   // 镂空/半透明：连接贴图 Alpha
        boolean translucent;
        int light;       // 发光等级 0..15
        int count;
    }

    final Map<String, Integer> matIndex = new HashMap<>();
    final List<Mat> mats = new ArrayList<>();

    /** 同一贴图在不同渲染层（实心/镂空/半透明）或不同发光等级下是不同的材质，互不影响 */
    int mat(String key, String texture, boolean alpha, boolean translucent, int light) {
        String layer = translucent ? "translucent" : alpha ? "cutout" : "solid";
        String full = key + "|" + layer + "|" + light;
        Integer i = matIndex.get(full);
        if (i == null) {
            Mat m = new Mat();
            m.key = key;
            m.texture = texture;
            m.alpha = alpha || translucent;
            m.translucent = translucent;
            m.light = light;
            i = mats.size();
            mats.add(m);
            matIndex.put(full, i);
        }
        return i;
    }

    /** n = 3 或 4；xyz 长度 3n；uvs 长度 2n */
    void face(int n, float[] xyz, float[] uvs, int mat, int color) {
        for (int i = 0; i < n * 3; i++) pos.add(xyz[i]);
        for (int i = 0; i < n * 2; i++) uv.add(uvs[i]);
        faceSize.add((byte) n);
        faceMat.add(mat);
        faceColor.add(color & 0xFFFFFF);
        faceBlock.add(curBlock);
        facePos.add(curX);
        facePos.add(curY);
        facePos.add(curZ);
        mats.get(mat).count++;
    }

    int faces() {
        return faceSize.size();
    }

    JsonArray materialsJson() {
        JsonArray a = new JsonArray();
        for (Mat m : mats) {
            JsonObject o = new JsonObject();
            o.addProperty("name", m.key);
            if (m.texture != null) o.addProperty("texture", m.texture);
            o.addProperty("alpha", m.alpha);
            o.addProperty("translucent", m.translucent);
            o.addProperty("layer", m.translucent ? "translucent" : m.alpha ? "cutout" : "solid");
            if (m.light > 0) o.addProperty("light", m.light);
            a.add(o);
        }
        return a;
    }
}
