package mine2blend;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** config/mine2blend.json */
public class Config {
    /** Blender 插件连接的本地端口 */
    public int port = 25599;
    /** 监听地址：默认只允许本机连接；Blender 在另一台机器/虚拟机时可改为 "0.0.0.0" */
    public String bindAddress = "127.0.0.1";
    /** 除「选区棒」外额外当作选区工具的物品 id（留空 = 只用选区棒），例如 "minecraft:blaze_rod" */
    public String extraWandItem = "";
    /** 单次导出的最大方块数（防止误选超大区域卡死游戏） */
    public long maxVolume = 32_000_000L;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static Config load() {
        Path p = FabricLoader.getInstance().getConfigDir().resolve("mine2blend.json");
        Config c = new Config();
        try {
            if (Files.exists(p)) {
                c = GSON.fromJson(Files.readString(p, StandardCharsets.UTF_8), Config.class);
                if (c == null) c = new Config();
            }
            Files.writeString(p, GSON.toJson(c), StandardCharsets.UTF_8);
        } catch (Exception e) {
            Mine2BlendClient.LOG.warn("[Mine2Blend] 读取配置失败，使用默认值", e);
        }
        return c;
    }
}
