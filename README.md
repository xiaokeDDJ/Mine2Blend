# Mine2Blend

把**正在运行的 Minecraft 世界**实时导入 Blender：在 Blender 里打开游戏的俯视地图框选一块区域（或在游戏里用「选区棒」框选），一键生成带真实贴图的网格。

> English summary at the bottom.

Mine2Blend 由两部分组成：

| 部分 | 运行在 | 作用 |
|---|---|---|
| **Mine2Blend mod**（`mine2blend-<版本>.jar`） | Minecraft 1.20.1（Fabric） | 选区棒；在本机开一个只读接口，把游戏里的方块模型、贴图、方块实体和实体交给 Blender |
| **Mine2Blend Blender 插件**（`mine2blend-blender-<版本>.zip`） | Blender 4.0 ~ 5.3 | 地图选区窗口；生成网格和原理化 BSDF 材质 |

## 功能

- **实时读取**：直接从正在运行的游戏读取，不需要先导出存档。单人游戏可以读取存档中所有已生成的区块（不会生成新地形）。
- **真实模型与贴图**：使用游戏里实际的方块模型，原版和模组方块都支持；贴图来自游戏当前启用的资源包。
- **方块实体与实体**：箱子、告示牌、床、旗帜、头颅、模组家具；物品展示框里的物品、盔甲架、掉落物、画等；生物可选。
- **流体与动画贴图**：水、岩浆的高度与流动；动画贴图（水、岩浆、风扇等）按游戏里的帧时间展开成循环播放的图像序列。
- **材质**：图像纹理（最近邻插值，保持像素风）→ 原理化 BSDF，平直着色。群系染色（草、树叶、水）烘焙进贴图；镂空、半透明、发光分别按游戏的渲染层处理。
- **PBR**：资源包带 LabPBR 贴图（`_n` / `_s`）时，自动连接法线、凹凸、粗糙度、金属度和自发光。
- **资源包变化自动更新**：在游戏里换了资源包后重新导入，Blender 里的贴图会自动替换。
- **三种导入方式**：合并为一个物体 / 按方块类型拆分 / 按单个方块拆分（原点在方块底面中心，方便做动画）。
- **合并相邻同材质面**：大幅减少墙面、地面这类整块方块的面数。
- **两种框选方式**：Blender 里的独立地图选区窗口，或游戏内的选区棒。

## 安装

在 [Releases](../../releases) 下载最新版本。

### 1. Minecraft mod

- 环境：Minecraft **1.20.1** + **Fabric Loader** + **Fabric API**
- 把 `mine2blend-<版本>.jar` 放进游戏的 `mods` 文件夹。

### 2. Blender 插件

| Blender 版本 | 安装包 | 安装方法 |
|---|---|---|
| 4.2 ~ 5.x | `mine2blend-blender-<版本>.zip` | 编辑 → 偏好设置 → 获取扩展 → 右上角下拉菜单 → **从磁盘安装** |
| 4.0 / 4.1 | `mine2blend-blender-<版本>-legacy.zip` | 编辑 → 偏好设置 → 插件 → **安装**，然后勾选启用 |

## 使用

1. 启动 Minecraft，进入存档。
2. 在 Blender 的 3D 视图里按 **N**，打开侧栏的 **Mine2Blend** 标签页，点 **连接 / 刷新**。
3. 选择导入范围（两种方式任选其一）：
   - **地图选区窗口**：点「打开地图选区窗口」，会打开一个独立窗口，显示游戏的俯视地图。
     - 左键拖拽框选（可以反复框选）；滚轮缩放；中键或右键拖动平移；Home 回到玩家位置。
     - 框选时会按地形自动设置高度范围，也可以点顶部的 − / + 调整。
     - 右侧是导入设置面板。按 **Enter** 导入，导入后窗口自动关闭。
   - **游戏内选区棒**：在创造物品栏「工具」页找到「选区棒」，或输入 `/m2b wand`。左键方块设点 1，右键方块设点 2，然后在 Blender 里点「导入游戏内选区」。
4. 选区只保存为数值，不会创建任何辅助物体；主 3D 视图里会用一个橙色线框显示选区。

### 游戏内指令

| 指令 | 作用 |
|---|---|
| `/m2b wand` | 获取选区棒（单人游戏，或多人游戏的创造模式） |
| `/m2b pos1`、`/m2b pos2` | 把脚下位置设为点 1 / 点 2 |
| `/m2b expand <n>` | 把选区向各个方向扩大 n 格 |
| `/m2b clear` | 清除选区 |
| `/m2b info` | 查看接口状态和当前选区 |

### 导入选项

| 选项 | 说明 |
|---|---|
| 导入方式 | 合并为一个物体 / 按方块类型拆分 / 按单个方块拆分（有物体数上限，默认 20000） |
| 植物、流体、方块实体、物品/装饰实体、生物 | 选择要包含的内容 |
| 合并相邻同材质面 | 把相邻的整格方块面合并成大面；这些材质的贴图改为平铺。需要做贴图绘制或烘焙时可以关掉 |
| PBR 材质 | 资源包带 LabPBR 贴图时连接法线、凹凸、粗糙度、金属度、自发光 |
| 合并顶点、剔除选区边界面、移到原点、覆盖已有材质 | 网格和材质的细节选项 |

## 配置

- **Blender**：连接地址在 编辑 → 偏好设置 → 插件（扩展）→ Mine2Blend 中设置，默认为本机 `127.0.0.1:25599`，一般不需要修改。
- **Minecraft**：配置文件是 `config/mine2blend.json`。

| 键 | 默认值 | 说明 |
|---|---|---|
| `port` | `25599` | 接口端口 |
| `bindAddress` | `127.0.0.1` | 监听地址，默认只允许本机访问 |
| `maxVolume` | `32000000` | 单次导出的方块数上限 |
| `extraWandItem` | `""` | 额外当作选区工具的物品 ID，例如 `minecraft:blaze_rod` |

## 安全说明

- mod 的接口**默认只监听本机**（`127.0.0.1`），局域网和互联网上的其他设备都无法访问。
- 接口是**只读**的，只能读取世界数据和设置选区，不能修改世界，也不能执行指令。
- 来自浏览器的请求（带 `Origin`、`Referer`、`Sec-Fetch-*` 请求头）一律拒绝；`Host` 不是本机地址的请求也会拒绝（防止 DNS 重绑定），以免网页偷偷访问本机接口。
- 只接受 GET 请求。
- 如果把 `bindAddress` 改成 `0.0.0.0`（例如 Blender 在另一台电脑上），同一网络里的其他设备也能读取你的世界数据。请只在可信网络中这样做，用完后改回 `127.0.0.1`。

## 已知限制

- 方块实体和实体只能读取客户端已加载的部分（渲染距离内）。
- 多人服务器上只能读取客户端已加载的区块。
- 动态贴图（地图画、联网皮肤、告示牌文字）、光影包效果、OptiFine CTM 连接纹理不导出。
- 动画贴图是磁盘上的图像序列，无法打包进 `.blend`：已保存的 `.blend` 会写到它旁边的 `mc_anim/` 文件夹，移动工程时请一起拷贝。

## 从源码构建

- **mod**：需要 JDK 17 或更高版本（推荐 21）。

  ```bash
  cd mod
  ./gradlew build
  ```

  产物在 `mod/build/libs/`。

- **Blender 插件**：`blender/mine2blend/` 就是插件本身。
  - 4.2 及以上：把这个目录里的文件（`blender_manifest.toml` 在 zip 根目录）打成 zip，用「从磁盘安装」。
  - 4.0 / 4.1：把整个 `mine2blend` 文件夹打成 zip，用「插件 → 安装」。

## 协议

[GPL-3.0-or-later](LICENSE)

---

## English summary

**Mine2Blend** imports a region of a *running* Minecraft world into Blender as a textured mesh.

- **Mod** (Minecraft 1.20.1, Fabric + Fabric API): adds a selection wand (`/m2b wand`) and a read-only local HTTP interface (`127.0.0.1:25599` by default) that serves block models, textures, block entities and entities.
- **Blender add-on** (Blender 4.0 – 5.3): opens a standalone top-down map window of the live world. Drag to select an area, press Enter to import. You can also import the in-game wand selection.
- Real block models, including modded blocks. Textures come from the game's active resource packs, with LabPBR normal/specular support. Animated textures are imported as image sequences. Materials are Image Texture → Principled BSDF with flat shading.
- Three import modes (merged, split by block type, split per block). An optional greedy merge of adjacent faces with the same material cuts the face count.
- Security: the interface binds to localhost only. It rejects browser-originated requests and non-local `Host` headers (DNS rebinding), and accepts GET requests only. It cannot modify the world or run commands.

Install: put `mine2blend-<version>.jar` into `mods`. In Blender 4.2+, install `mine2blend-blender-<version>.zip` via *Get Extensions → Install from Disk*. In Blender 4.0/4.1, install `…-legacy.zip` via *Add-ons → Install*.

License: GPL-3.0-or-later.
