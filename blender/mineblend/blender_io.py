"""bpy 相关：贴图、材质、网格对象、选区框。"""
import hashlib
import os
import tempfile

import bpy
import numpy as np
from mathutils import Vector

ROOT_COLLECTION = "MineBlend"


def get_collection(name=ROOT_COLLECTION):
    col = bpy.data.collections.get(name)
    if col is None:
        col = bpy.data.collections.new(name)
    if not _linked(col):
        bpy.context.scene.collection.children.link(col)
    return col


def _linked(col):
    def walk(c):
        if col.name in c.children:
            return True
        return any(walk(ch) for ch in c.children)
    return walk(bpy.context.scene.collection)


def _short(name, limit=60):
    """Blender 数据块名最长 63 字节，超长时截断并加哈希"""
    if len(name.encode("utf-8")) <= limit:
        return name
    h = hashlib.md5(name.encode("utf-8")).hexdigest()[:6]
    return name[: limit - 8] + "~" + h


# ---------------------------------------------------------------- images

def _hash(data):
    return hashlib.md5(data).hexdigest()[:16]


def load_png(name, png_bytes, overwrite=False, pack=True):
    """载入贴图。按内容哈希判断：同名图片内容没变就复用；游戏里换了资源包（内容变了）就替换图片数据，
    使用它的材质会自动用上新贴图。"""
    iname = _short(name)
    h = _hash(png_bytes)
    img = bpy.data.images.get(iname)
    if img is not None and not overwrite and img.get("mc_hash") == h:
        return img
    fd, path = tempfile.mkstemp(suffix=".png", prefix="mineblend_")
    try:
        with os.fdopen(fd, "wb") as f:
            f.write(png_bytes)
        new = bpy.data.images.load(path, check_existing=False)
        if pack:
            new.pack()
        if img is not None:
            img.user_remap(new)
            bpy.data.images.remove(img)
        new.name = iname
        new.filepath = ""
        new["mc_hash"] = h
        return new
    finally:
        try:
            os.remove(path)
        except OSError:
            pass


def _avg_color(img):
    try:
        px = np.empty(len(img.pixels), np.float32)
        img.pixels.foreach_get(px)
        px = px.reshape(-1, 4)
        m = px[:, 3] > 0.5
        if not m.any():
            return (0.8, 0.8, 0.8)
        return tuple(float(v) for v in px[m, :3].mean(axis=0))
    except Exception:
        return (0.8, 0.8, 0.8)


# ---------------------------------------------------------------- materials

def _principled(mat):
    try:
        mat.use_nodes = True  # 5.x 中材质总是使用节点，此属性可能已弃用
    except Exception:
        pass
    nt = mat.node_tree
    bsdf = next((n for n in nt.nodes if n.type == "BSDF_PRINCIPLED"), None)
    out = next((n for n in nt.nodes if n.type == "OUTPUT_MATERIAL"), None)
    if out is None:
        out = nt.nodes.new("ShaderNodeOutputMaterial")
    out.location = (400, 0)
    if bsdf is None:
        bsdf = nt.nodes.new("ShaderNodeBsdfPrincipled")
        nt.links.new(bsdf.outputs[0], out.inputs["Surface"])
    bsdf.location = (100, 0)
    return nt, bsdf


def _set_input(node, names, value):
    for n in names:
        sock = node.inputs.get(n)
        if sock is not None:
            sock.default_value = value
            return sock
    return None


def _inp(node, names):
    for n in names:
        s = node.inputs.get(n)
        if s is not None:
            return s
    return None


def _set_transparent(mat, layer):
    """solid：不透明；cutout：镂空（Alpha 已二值化，抖动即精确裁切）；translucent：混合半透明"""
    if hasattr(mat, "surface_render_method"):
        mat.surface_render_method = "BLENDED" if layer == "translucent" else "DITHERED"
    if hasattr(mat, "blend_method"):  # 4.0/4.1 的 EEVEE（4.2+ 已弃用）
        try:
            mat.blend_method = {"translucent": "BLEND", "cutout": "CLIP"}.get(layer, "OPAQUE")
        except Exception:
            pass
    if hasattr(mat, "shadow_method"):
        try:
            mat.shadow_method = {"translucent": "HASHED", "cutout": "CLIP"}.get(layer, "OPAQUE")
        except Exception:
            pass
    if hasattr(mat, "use_backface_culling"):
        mat.use_backface_culling = False


def material_name(key):
    if key.startswith("untextured:"):
        key = key[len("untextured:"):]
    if key.startswith("minecraft:"):
        key = key[len("minecraft:"):]
    return _short("MC_" + key)


def tinted_image(base, rgb, overwrite=False):
    """把染色（0xRRGGBB，sRGB 空间相乘，与游戏一致）烘焙进贴图副本，名称如 minecraft:block/grass_block_top#79c05a"""
    name = _short(f"{base.name}#{rgb:06x}")
    src = f"{base.get('mc_hash', base.name)}|{rgb:06x}"
    img = bpy.data.images.get(name)
    if img is not None and not overwrite and img.get("mc_src") == src:
        return img
    w, h = base.size
    px = np.empty(w * h * 4, np.float32)
    base.pixels.foreach_get(px)
    px = px.reshape(-1, 4)
    px[:, 0] *= ((rgb >> 16) & 255) / 255.0
    px[:, 1] *= ((rgb >> 8) & 255) / 255.0
    px[:, 2] *= (rgb & 255) / 255.0
    if img is None or tuple(img.size) != (w, h):
        if img is not None:
            bpy.data.images.remove(img)
        img = bpy.data.images.new(name, w, h, alpha=True)
    img.pixels.foreach_set(px.ravel())
    img.file_format = "PNG"
    img.pack()
    img["mc_src"] = src
    img["mc_hash"] = _hash(src.encode())
    return img


def overlay_image(base, overlay, name, overwrite=False):
    """把叠加层贴图（已染色）按 Alpha 合成到底层贴图上，尺寸不同时返回 None"""
    name = _short(name)
    src = f"{base.get('mc_hash', base.name)}|{overlay.get('mc_hash', overlay.name)}"
    img = bpy.data.images.get(name)
    if img is not None and not overwrite and img.get("mc_src") == src:
        return img
    if tuple(base.size) != tuple(overlay.size):
        return None
    w, h = base.size
    a = np.empty(w * h * 4, np.float32)
    b = np.empty(w * h * 4, np.float32)
    base.pixels.foreach_get(a)
    overlay.pixels.foreach_get(b)
    a = a.reshape(-1, 4)
    b = b.reshape(-1, 4)
    t = b[:, 3:4]
    a[:, :3] = a[:, :3] * (1 - t) + b[:, :3] * t
    if img is None or tuple(img.size) != (w, h):
        if img is not None:
            bpy.data.images.remove(img)
        img = bpy.data.images.new(name, w, h, alpha=True)
    img.pixels.foreach_set(a.ravel())
    img.file_format = "PNG"
    img.pack()
    img["mc_src"] = src
    img["mc_hash"] = _hash(src.encode())
    return img


def _has_partial_alpha(px):
    a = px[:, 3]
    return bool(((a > 0.004) & (a < 0.996)).any())


def cutout_image(base, overwrite=False):
    """镂空层：游戏里 Alpha < 0.1 的像素被丢弃、其余完全不透明。把 Alpha 二值化成副本，避免在 Blender 里变成半透明"""
    w, h = base.size
    px = np.empty(w * h * 4, np.float32)
    base.pixels.foreach_get(px)
    px = px.reshape(-1, 4)
    if not _has_partial_alpha(px):
        return base
    name = _short(base.name + "~cut")
    src = f"{base.get('mc_hash', base.name)}|cut"
    img = bpy.data.images.get(name)
    if img is not None and not overwrite and img.get("mc_src") == src:
        return img
    px[:, 3] = (px[:, 3] >= 0.1).astype(np.float32)
    if img is None or tuple(img.size) != (w, h):
        if img is not None:
            bpy.data.images.remove(img)
        img = bpy.data.images.new(name, w, h, alpha=True)
    img.pixels.foreach_set(px.ravel())
    img.file_format = "PNG"
    img.pack()
    img["mc_src"] = src
    img["mc_hash"] = _hash(src.encode())
    return img


# ---------------------------------------------------------------- animated textures

def _anim_root():
    if bpy.data.filepath:
        return bpy.path.abspath("//mc_anim"), True
    try:  # Blender 4.2+（扩展）
        return bpy.utils.extension_path_user(__package__, path="anim", create=True), False
    except Exception:
        pass
    try:  # Blender 4.0 / 4.1（传统插件）
        return bpy.utils.user_resource("DATAFILES", path="mineblend/anim", create=True), False
    except Exception:
        return os.path.join(tempfile.gettempdir(), "mineblend_anim"), False


def anim_sequence(name, strip_png, anim, rgb=0xFFFFFF, cutout=False, overwrite=False):
    """把游戏的动画贴图（完整帧图 + .mcmeta 帧顺序/时长/插值）按当前场景帧率展开成图像序列。
    返回 (image, 帧数)；image.source = 'SEQUENCE'，在材质里仍然只是一个图像纹理节点。"""
    import re
    scene = bpy.context.scene
    fps = scene.render.fps / (scene.render.fps_base or 1.0)
    frames = [(int(i), max(1, int(t))) for i, t in anim["frames"]]
    total = sum(t for _, t in frames)
    n_out = max(1, int(round(total / 20.0 * fps)))
    fw, fh, cols = int(anim["frameWidth"]), int(anim["frameHeight"]), max(1, int(anim["columns"]))
    tag = re.sub(r"[^A-Za-z0-9_.-]", "_", name)
    if rgb != 0xFFFFFF:
        tag += f"_{rgb:06x}"
    if cutout:
        tag += "_cut"
    tag += f"_{n_out}f_{_hash(strip_png)[:8]}"  # 资源包变了 → 新目录、重新展开
    root, relative = _anim_root()
    folder = os.path.join(root, tag)
    first = os.path.join(folder, "0001.png")
    iname = _short(f"{name}#seq" + (f"{rgb:06x}" if rgb != 0xFFFFFF else "") + ("~cut" if cutout else ""))

    if overwrite or not os.path.isfile(os.path.join(folder, f"{n_out:04d}.png")):
        os.makedirs(folder, exist_ok=True)
        strip = load_png(f"_mineblend_strip_{tag}", strip_png, overwrite=True, pack=True)
        W, H = strip.size
        px = np.empty(W * H * 4, np.float32)
        strip.pixels.foreach_get(px)
        bpy.data.images.remove(strip)
        top = px.reshape(H, W, 4)[::-1]  # 改为自上而下，与游戏帧排列一致
        tint = np.array([((rgb >> 16) & 255) / 255.0, ((rgb >> 8) & 255) / 255.0, (rgb & 255) / 255.0], np.float32)

        def frame(idx):
            r, c = divmod(idx, cols)
            return top[r * fh:(r + 1) * fh, c * fw:(c + 1) * fw]

        starts = np.cumsum([0] + [t for _, t in frames[:-1]])
        out = bpy.data.images.new("_mineblend_frame", fw, fh, alpha=True)
        try:
            for k in range(n_out):
                tick = (k / fps * 20.0) % total
                j = int(np.searchsorted(starts, tick, side="right") - 1)
                f = frame(frames[j][0]).copy()
                if anim.get("interpolate"):
                    frac = (tick - starts[j]) / frames[j][1]
                    f = (f * (1 - frac) + frame(frames[(j + 1) % len(frames)][0]) * frac).astype(np.float32)
                f[..., :3] *= tint
                if cutout:
                    f[..., 3] = (f[..., 3] >= 0.1).astype(np.float32)
                out.pixels.foreach_set(np.ascontiguousarray(f[::-1], dtype=np.float32).ravel())
                out.filepath_raw = os.path.join(folder, f"{k + 1:04d}.png")
                out.file_format = "PNG"
                out.save()
        finally:
            bpy.data.images.remove(out)

    img = bpy.data.images.get(iname)
    if img is None or overwrite:
        if img is not None:
            bpy.data.images.remove(img)
        img = bpy.data.images.load(first, check_existing=False)
        img.name = iname
    elif bpy.path.abspath(img.filepath) != first:
        img.filepath = first
        img.reload()
    img.source = "SEQUENCE"
    if relative:
        try:
            img.filepath = bpy.path.relpath(first)
        except Exception:
            pass
    return img, n_out


# ---------------------------------------------------------------- PBR（LabPBR：_n 法线/AO/高度，_s 光滑度/F0/自发光）

def _png_pixels(png):
    tmp = load_png(f"_mineblend_tmp_{_hash(png)}", png, overwrite=True, pack=True)
    try:
        w, h = tmp.size
        px = np.empty(w * h * 4, np.float32)
        tmp.pixels.foreach_get(px)
    finally:
        bpy.data.images.remove(tmp)
    return px.reshape(h, w, 4), w, h


def _first_frame(px, aspect):
    """动画贴图的配套图也是多帧竖排，只取第一帧（图片顶部）"""
    h, w = px.shape[:2]
    fh = int(round(w * aspect))
    if 0 < fh < h:
        px = px[h - fh:]  # Blender 像素自下而上，顶部 = 末尾几行
    return px


def _data_image(name, px, src, overwrite):
    """非颜色数据图（法线/粗糙度等），通道独立、不做色彩转换"""
    h, w = px.shape[:2]
    img = bpy.data.images.get(name)
    if img is not None and not overwrite and img.get("mc_src") == src:
        return img
    if img is None or tuple(img.size) != (w, h):
        if img is not None:
            bpy.data.images.remove(img)
        img = bpy.data.images.new(name, w, h, alpha=True)
    img.colorspace_settings.name = "Non-Color"
    try:
        img.alpha_mode = "CHANNEL_PACKED"
    except Exception:
        pass
    img.pixels.foreach_set(np.ascontiguousarray(px, np.float32).ravel())
    img.file_format = "PNG"
    img.pack()
    img["mc_src"] = src
    return img


def pbr_normal(tname, png, aspect=1.0, overwrite=False):
    """LabPBR _n（DirectX 法线 RG、B=AO、A=高度）→ Blender 法线贴图（OpenGL：RGB=法线，A=高度）"""
    name = _short(tname + "_n")
    src = _hash(png)
    img = bpy.data.images.get(name)
    if img is not None and not overwrite and img.get("mc_src") == src:
        return img
    px, _, _ = _png_pixels(png)
    px = _first_frame(px, aspect).copy()
    x = px[..., 0] * 2 - 1
    y = -(px[..., 1] * 2 - 1)  # DirectX(Y-) → OpenGL(Y+)
    z = np.sqrt(np.clip(1 - x * x - y * y, 0, 1))
    px[..., 0] = x * 0.5 + 0.5
    px[..., 1] = y * 0.5 + 0.5
    px[..., 2] = z * 0.5 + 0.5
    return _data_image(name, px, src, True)


def pbr_specular(tname, png, aspect=1.0, overwrite=False):
    """LabPBR _s → R=粗糙度 (1-光滑度)²、G=金属度（G≥230 为金属）、B=自发光强度（A<255）"""
    name = _short(tname + "_s")
    src = _hash(png)
    img = bpy.data.images.get(name)
    if img is not None and not overwrite and img.get("mc_src") == src:
        return img
    px, _, _ = _png_pixels(png)
    px = _first_frame(px, aspect).copy()
    r, g, a = px[..., 0], px[..., 1] * 255, px[..., 3] * 255
    out = np.empty_like(px)
    out[..., 0] = (1 - r) ** 2
    out[..., 1] = (g >= 229.5).astype(np.float32)
    out[..., 2] = np.where(a < 254.5, a / 254.0, 0.0)
    out[..., 3] = 1.0
    return _data_image(name, out, src, True)


MAT_VERSION = 4


def block_material(minfo, image, rgb=0xFFFFFF, overwrite=False, suffix="", seq_frames=0, pbr=(None, None)):
    """图像纹理 → 原理化 BSDF（染色已烘焙进贴图）。已存在且不覆盖时直接复用（保留手动调整）。
    同一贴图在半透明层或发光时是单独的材质（名称带 [半透明] / [发光N]）。"""
    layer = minfo.get("layer") or ("translucent" if minfo.get("translucent") else "cutout" if minfo.get("alpha") else "solid")
    name = material_name(minfo["name"])
    if rgb != 0xFFFFFF:
        name = f"{name}#{rgb:06x}"
    name += suffix
    if layer == "translucent":
        name += " [半透明]"
    if int(minfo.get("light", 0)) > 0:
        name += f" [发光{int(minfo['light'])}]"
    name = _short(name)
    nimg, simg = pbr
    sig = "|".join(i.name if i is not None else "" for i in (image, nimg, simg)) + f"|{seq_frames}"
    mat = bpy.data.materials.get(name)
    if mat is not None and not overwrite and mat.get("mc_version") == MAT_VERSION and mat.get("mc_sig") == sig:
        return mat
    if mat is None:
        mat = bpy.data.materials.new(name)
    mat["mc_texture"] = minfo.get("texture", "")
    mat["mc_version"] = MAT_VERSION
    mat["mc_sig"] = sig
    nt, bsdf = _principled(mat)
    for n in list(nt.nodes):
        if n.name != bsdf.name and n.type != "OUTPUT_MATERIAL":
            nt.nodes.remove(n)

    alpha = layer != "solid" and image is not None
    light = int(minfo.get("light", 0))
    is_water = "water" in minfo.get("name", "")
    if image is not None:
        tex = nt.nodes.new("ShaderNodeTexImage")
        tex.image = image
        tex.interpolation = "Closest"
        tex.extension = "EXTEND"  # 模组模型的 UV 可能略超出贴图范围，延伸边缘而不是平铺
        tex.location = (-350, 0)
        if seq_frames > 1:
            iu = tex.image_user
            iu.frame_duration = seq_frames
            iu.frame_start = 1
            iu.frame_offset = 0
            iu.use_cyclic = True
            iu.use_auto_refresh = True
        nt.links.new(tex.outputs["Color"], _inp(bsdf, ["Base Color"]))
        if alpha:
            nt.links.new(tex.outputs["Alpha"], _inp(bsdf, ["Alpha"]))
        if light > 0:
            em = _inp(bsdf, ["Emission Color", "Emission"])
            if em is not None:
                nt.links.new(tex.outputs["Color"], em)
        avg = _avg_color(image) if seq_frames <= 1 else (0.5, 0.5, 0.5)
    else:
        c = (((rgb >> 16) & 255) / 255.0, ((rgb >> 8) & 255) / 255.0, (rgb & 255) / 255.0)
        avg = tuple(srgb_to_linear(v) for v in c)
        _set_input(bsdf, ["Base Color"], (*avg, 1.0))
        if light > 0:
            _set_input(bsdf, ["Emission Color", "Emission"], (*avg, 1.0))
    if light > 0:
        _set_input(bsdf, ["Emission Strength"], light / 15.0 * 2.0)
    _set_input(bsdf, ["Roughness"], 0.1 if is_water else 0.9)

    # PBR：资源包提供 _s / _n 时连接粗糙度、金属度、自发光与法线/凹凸
    if image is not None and simg is not None:
        st = nt.nodes.new("ShaderNodeTexImage")
        st.image, st.interpolation, st.extension = simg, "Closest", "EXTEND"
        st.location = (-650, -250)
        sep = nt.nodes.new("ShaderNodeSeparateColor")
        sep.location = (-350, -250)
        nt.links.new(st.outputs["Color"], sep.inputs[0])
        nt.links.new(sep.outputs[0], _inp(bsdf, ["Roughness"]))
        nt.links.new(sep.outputs[1], _inp(bsdf, ["Metallic"]))
        em = _inp(bsdf, ["Emission Color", "Emission"])
        if em is not None and not em.is_linked:
            nt.links.new(tex.outputs["Color"], em)
        nt.links.new(sep.outputs[2], _inp(bsdf, ["Emission Strength"]))
    if image is not None and nimg is not None:
        nt_ = nt.nodes.new("ShaderNodeTexImage")
        nt_.image, nt_.interpolation, nt_.extension = nimg, "Closest", "EXTEND"
        nt_.location = (-650, -550)
        nmap = nt.nodes.new("ShaderNodeNormalMap")
        nmap.location = (-350, -550)
        nt.links.new(nt_.outputs["Color"], nmap.inputs["Color"])
        bump = nt.nodes.new("ShaderNodeBump")
        bump.location = (-150, -550)
        bump.inputs["Strength"].default_value = 0.15
        bump.inputs["Distance"].default_value = 0.02
        nt.links.new(nt_.outputs["Alpha"], bump.inputs["Height"])
        nt.links.new(nmap.outputs["Normal"], bump.inputs["Normal"])
        nt.links.new(bump.outputs["Normal"], _inp(bsdf, ["Normal"]))
    if minfo.get("translucent") and image is not None:
        _set_input(bsdf, ["IOR"], 1.33 if is_water else 1.5)
    _set_transparent(mat, layer if image is not None else "solid")
    mat.diffuse_color = (avg[0], avg[1], avg[2], 1.0)
    return mat


def srgb_to_linear(c):
    c = max(0.0, min(1.0, float(c)))
    return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4


# ---------------------------------------------------------------- mesh object

def make_mesh_object(name, md, materials=None, collection=None):
    """md: mesher.MeshData（或具备相同字段的对象）"""
    me = bpy.data.meshes.new(name)
    nv, nf, nl = len(md.verts), len(md.face_size), len(md.loop_vert)
    me.vertices.add(nv)
    me.vertices.foreach_set("co", np.ascontiguousarray(md.verts, np.float32).ravel())
    me.loops.add(nl)
    me.loops.foreach_set("vertex_index", np.ascontiguousarray(md.loop_vert, np.int32))
    me.polygons.add(nf)
    starts = np.zeros(nf, np.int32)
    if nf:
        np.cumsum(md.face_size[:-1], out=starts[1:])
    me.polygons.foreach_set("loop_start", starts)
    try:
        me.polygons.foreach_set("loop_total", np.ascontiguousarray(md.face_size, np.int32))
    except Exception:
        pass  # 新版本中由 loop_start 推导，只读
    if materials:
        for m in materials:
            me.materials.append(m)
        if nf:
            me.polygons.foreach_set("material_index", np.ascontiguousarray(md.face_mat, np.int32))
    if md.loop_uv is not None and nl:
        uvl = me.uv_layers.new(name="UVMap")
        uvl.data.foreach_set("uv", np.ascontiguousarray(md.loop_uv, np.float32).ravel())
    me.update(calc_edges=True)
    shade_flat(me)
    obj = bpy.data.objects.new(name, me)
    (collection or get_collection()).objects.link(obj)
    return obj


def shade_flat(me):
    if hasattr(me, "shade_flat"):
        me.shade_flat()
    else:
        me.polygons.foreach_set("use_smooth", np.zeros(len(me.polygons), bool))
