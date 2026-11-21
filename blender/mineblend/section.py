"""选区剖面：mod 的 /columns 一次性给出选区内每列的方块段，这里在本地按任意高度范围即时算出俯视剖面图。

只依赖 numpy（不依赖 bpy），调高度时每次重算只需几毫秒，所以地图窗口里拖动数值时剖面是实时变化的。
"""
import numpy as np

YSPAN = 4096  # 每列在排序键里占的高度跨度（远大于世界高度）


class ColumnSection:
    def __init__(self, header, data, key, base=0):
        h = header
        sec = h["sections"]
        self.key = key
        self.nx, self.nz = int(h["nx"]), int(h["nz"])
        self.step, self.ppb = int(h["step"]), int(h["ppb"])
        self.bottom = int(h["bottom"])
        n = self.nx * self.nz
        self.n = n

        def arr(name, dt, count):
            off, _ = sec[name]
            return np.frombuffer(data, dtype=dt, count=count, offset=base + off)

        offs = arr("b0", "<i4", n + 1).astype(np.int64)
        runs = int(offs[-1])
        ys = arr("b1", "<i2", runs).astype(np.int64)
        ids = arr("b2", "<u2", runs).astype(np.int64)
        self.biome = arr("b3", "<u2", n).astype(np.int64)
        P, B = int(h["palette"]), int(h["biomes"])
        ppb = self.ppb
        self.tiles = arr("b4", "u1", P * ppb * ppb * 4).reshape(P, ppb, ppb, 4).astype(np.float32) / 255.0
        tint = arr("b5", "<i4", P * B).reshape(P, B).astype(np.int64)
        self.tint = np.stack([(tint >> 16) & 255, (tint >> 8) & 255, tint & 255], axis=-1).astype(np.float32) / 255.0
        empty = np.zeros(P, bool)
        empty[np.asarray(h.get("empty", []), np.int64)] = True

        col = np.repeat(np.arange(n), np.diff(offs))
        self.offs = offs
        self.ids = ids
        self.has = np.diff(offs) > 0
        self.sortkey = col * YSPAN + (ys - self.bottom)
        # 每段的结束高度：下一段开始前一格；每列最后一段（地表上方的空气）一直延伸到顶
        end = np.full(runs, 1 << 30, np.int64)
        if runs > 1:
            end[:-1] = ys[1:] - 1
        last = offs[1:][self.has] - 1
        end[last] = 1 << 30
        self.run_end = end
        # 每段「本段或下方最近的不透明段」下标，同列内没有则为 -1
        idx = np.where(~empty[ids], np.arange(runs), -1)
        acc = np.maximum.accumulate(idx) if runs else idx
        self.solid_le = np.where(acc >= offs[col], acc, -1) if runs else acc
        # 每列原本的地表高度（不切时从上往下看到的方块），用来计算「被切开后往下看了多深」
        _, surf, ok = self.heights(-(1 << 30), 1 << 30)
        self.surface = np.where(ok, surf, -(1 << 30))

    def display_ppb(self, limit=1024):
        """显示用的每格像素：保证整张图不超过 limit 像素宽"""
        for dp in (8, 4, 2, 1):
            if dp <= self.ppb and self.ppb % dp == 0 and max(self.nx, self.nz) * dp <= limit:
                return dp
        return 1

    def heights(self, y0, y1):
        """每列在 [y0, y1] 内从上往下看到的第一个方块：(调色板下标, 高度, 是否有方块)"""
        n = self.n
        if len(self.ids) == 0:
            return np.zeros(n, np.int64), np.zeros(n, np.int64), np.zeros(n, bool)
        cols = np.arange(n)
        yq = int(np.clip(y1 - self.bottom, 0, YSPAN - 1))
        i = np.searchsorted(self.sortkey, cols * YSPAN + yq, side="right") - 1
        valid = self.has & (i >= self.offs[:-1])
        i = np.where(valid, i, 0)
        r = self.solid_le[i]
        valid &= r >= 0
        r = np.where(valid, r, 0)
        hgt = np.minimum(y1, self.run_end[r])
        valid &= hgt >= y0
        return self.ids[r], hgt, valid

    def image(self, y0, y1, dp):
        """RGBA float32 图像（行从下往上，供 GPUTexture 使用），尺寸 (nz*dp, nx*dp, 4)"""
        nx, nz = self.nx, self.nz
        pid, hgt, valid = self.heights(y0, y1)
        hh = np.where(valid, hgt, -10 ** 6).reshape(nz, nx)
        vv = valid.reshape(nz, nx)
        north = np.vstack([hh[:1], hh[:-1]])
        nv = np.vstack([vv[:1], vv[:-1]])
        north = np.where(nv, north, hh)
        shade = np.where(hh > north, 1.12, np.where(hh < north, 0.80, 1.0)).astype(np.float32)
        # 越深越暗：相对这一列原本的地表（被切到时相对切面）往下的深度；没被切开的地形保持原亮度
        depth = np.maximum(0, np.minimum(y1, self.surface) - hgt).reshape(nz, nx)
        shade *= np.maximum(0.35, 1.0 - depth * 0.035).astype(np.float32)
        color = self.tint[pid, self.biome] * shade.reshape(-1, 1)
        k = self.ppb // dp
        tiles = self.tiles[:, ::k, ::k]
        img = tiles[pid]                                     # (n, dp, dp, 4)
        img[..., :3] *= color[:, None, None, :]
        np.minimum(img, 1.0, out=img)
        img[~valid] = 0.0
        img = img.reshape(nz, nx, dp, dp, 4).transpose(0, 2, 1, 3, 4).reshape(nz * dp, nx * dp, 4)
        return np.ascontiguousarray(img[::-1])
