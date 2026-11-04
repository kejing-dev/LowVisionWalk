"""Scene props from Tripo (v0.1.5, QA #3 q3-2, profile B12 / D25 / D24).

The user's own Tripo account (studio.tripo3d.ai, text to 3D, HD model H3.1, then Retopology to ~9k triangles,
exported GLB with 1k textures) made the table, the mug, the salt shaker, the glasses case, the sign plaque, the sign
stand and the shoe. Raw files live outside the repository in ../low-vision-assets/raw/lv_<name>.glb.

For each prop this script
1. bakes ONE calibration root node into the file (D25): the prop's origin as the scene expects it (see ORIGIN), its
   face on +Z (YAW turns the file about +Y first), its true size in metres (per axis where the design gives all three);
2. forces every material OPAQUE, metallic 0 (D24: the Stage has no reflections), images cut to 1024 px;
3. writes app/src/main/assets/props/<name>.glb, app/src/main/java/io/embodit/lowvision/domain/PropModels.kt (the one
   place for the calibrated boxes the app and the unit test use) and tools/props_metrics.json;
4. renders front / side / top views into ../spatial-app-factory/apps/low-vision/build/props/<name>_views.png and a
   line-up of the table with the three items.

    python -I tools/make_props.py inspect          bounding boxes of the raw files (as exported by Tripo)
    python -I tools/make_props.py [RAW_DIR]        process all
"""
import io
import json
import math
import os
import struct
import sys

import numpy as np
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, ".."))
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets", "props")
RAW = os.path.abspath(os.path.join(ROOT, "..", "low-vision-assets", "raw"))
RENDERS = os.path.abspath(os.path.join(ROOT, "..", "spatial-app-factory", "apps", "low-vision", "build", "props"))
KOTLIN = os.path.join(ROOT, "app", "src", "main", "java", "io", "embodit", "lowvision", "domain", "PropModels.kt")
METRICS = os.path.join(HERE, "props_metrics.json")

# name: target size in metres (x, y, z; None = follow the uniform scale of the axes given), yaw (degrees about +Y that
# turns the file's face onto +Z), origin, Tripo task id. Sizes from the design package (E2, E3) and WalkRules.
#   origin "bottom": centre of the bottom face; "top": centre of the top face (table: items stand on y = 0);
#   "back": centre of the board, the front face at z = -0.001 (the word quad lies at z = +0.0005 in front of it)
# Yaw (raw renders in build/props/raw_*.png): table and glasses case long along Z -> 90; shoe toe on +X -> -90 (toe on
# +Z); sign stand's mounting plate on -Z -> 180 (the plate on the board's side, hidden behind the plaque).
MODELS = {
    "table": ((0.80, 0.75, 0.50), 90, "top", "c28bc564-8498-47f1-b72f-9da0431ad0af"),
    "mug": ((None, 0.095, None), 0, "bottom", "b1c73f74-7384-4f80-ac94-d38517c15d8f"),
    "salt": ((0.04, 0.09, 0.04), 0, "bottom", "b1de190c-fa33-4560-94f1-ef90df9e3240"),
    "glasses_case": ((0.16, 0.045, 0.07), 90, "bottom", "a235f900-2be0-48a6-a34a-bcffdebb2d92"),
    "sign_plaque": ((0.32, 0.22, 0.012), 0, "back", "6409d45d-6c3f-4156-b596-a8c966536552"),
    "sign_stand": ((None, 1.0, None), 180, "bottom", "6de2cfbf-1934-4d21-9291-51b14d2c4c8a"),
    "shoe": ((None, None, 0.28), -90, "bottom", "68790c9e-1944-44c6-82d9-81ebd0fcc939"),
}
# props whose origin is the centre of a round body with a handle sticking out along +X
BODY_X = {"mug"}
IMAGE_MAX = 1024
CT = {5126: (np.float32, 4), 5123: (np.uint16, 2), 5121: (np.uint8, 1), 5125: (np.uint32, 4)}
NC = {"SCALAR": 1, "VEC2": 2, "VEC3": 3, "VEC4": 4, "MAT4": 16}


# ------------------------------------------------------------------------------------------------- GLB io

def read_glb(path):
    data = open(path, "rb").read()
    magic, _version, length = struct.unpack_from("<III", data, 0)
    assert magic == 0x46546C67, "not a GLB"
    off, js, binc = 12, None, b""
    while off < length:
        clen, ctype = struct.unpack_from("<II", data, off)
        chunk = data[off + 8: off + 8 + clen]
        if ctype == 0x4E4F534A:
            js = json.loads(chunk.decode("utf-8"))
        elif ctype == 0x004E4942:
            binc = chunk
        off += 8 + clen
    return js, binc


def write_glb(path, js, binc):
    jb = json.dumps(js, separators=(",", ":")).encode("utf-8")
    jb += b" " * ((4 - len(jb) % 4) % 4)
    bb = bytes(binc) + b"\x00" * ((4 - len(binc) % 4) % 4)
    with open(path, "wb") as f:
        f.write(struct.pack("<III", 0x46546C67, 2, 12 + 8 + len(jb) + 8 + len(bb)))
        f.write(struct.pack("<II", len(jb), 0x4E4F534A))
        f.write(jb)
        f.write(struct.pack("<II", len(bb), 0x004E4942))
        f.write(bb)


def accessor(js, binc, i):
    a = js["accessors"][i]
    bv = js["bufferViews"][a["bufferView"]]
    dt, size = CT[a["componentType"]]
    n = NC[a["type"]]
    off = bv.get("byteOffset", 0) + a.get("byteOffset", 0)
    stride = bv.get("byteStride", 0) or size * n
    raw = np.frombuffer(binc, dtype=np.uint8, count=stride * (a["count"] - 1) + size * n, offset=off)
    if stride == size * n:
        return raw.view(dt).reshape(a["count"], n).astype(np.float64 if dt == np.float32 else np.int64)
    rows = np.lib.stride_tricks.as_strided(raw, shape=(a["count"], size * n), strides=(stride, 1))
    return rows.copy().view(dt).reshape(a["count"], n).astype(np.float64 if dt == np.float32 else np.int64)


def node_matrix(n):
    if "matrix" in n:
        return np.array(n["matrix"], dtype=float).reshape(4, 4).T
    t = np.array(n.get("translation", [0, 0, 0]), dtype=float)
    x, y, z, w = n.get("rotation", [0, 0, 0, 1])
    s = np.array(n.get("scale", [1, 1, 1]), dtype=float)
    r = np.array([
        [1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)],
        [2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)],
        [2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)],
    ])
    m = np.eye(4)
    m[:3, :3] = r * s[None, :]
    m[:3, 3] = t
    return m


def triangles(js, binc):
    """World-space triangles of the default scene with a base colour per triangle (texture at the centroid UV)."""
    out, cols = [], []
    scene = js["scenes"][js.get("scene", 0)]

    def walk(i, parent):
        n = js["nodes"][i]
        m = parent @ node_matrix(n)
        if "mesh" in n:
            for prim in js["meshes"][n["mesh"]]["primitives"]:
                pos = accessor(js, binc, prim["attributes"]["POSITION"])[:, :3]
                pw = (m @ np.c_[pos, np.ones(len(pos))].T).T[:, :3]
                idx = accessor(js, binc, prim["indices"]).reshape(-1, 3) if "indices" in prim else np.arange(len(pos)).reshape(-1, 3)
                col = np.full((len(idx), 3), 0.7)
                mat = js["materials"][prim["material"]] if "material" in prim else {}
                pbr = mat.get("pbrMetallicRoughness", {})
                factor = np.array(pbr.get("baseColorFactor", [1, 1, 1, 1])[:3])
                if "baseColorTexture" in pbr and "TEXCOORD_0" in prim["attributes"]:
                    uv = accessor(js, binc, prim["attributes"]["TEXCOORD_0"])[:, :2]
                    tex = js["textures"][pbr["baseColorTexture"]["index"]]
                    img = js["images"][tex["source"]]
                    bv = js["bufferViews"][img["bufferView"]]
                    o = bv.get("byteOffset", 0)
                    im = np.asarray(Image.open(io.BytesIO(bytes(binc[o: o + bv["byteLength"]]))).convert("RGB"), dtype=float) / 255
                    h, w = im.shape[:2]
                    c = uv[idx].mean(axis=1)
                    px = np.clip((c[:, 0] % 1) * (w - 1), 0, w - 1).astype(int)
                    py = np.clip((c[:, 1] % 1) * (h - 1), 0, h - 1).astype(int)
                    col = im[py, px] * factor
                else:
                    col = col * factor
                out.append(pw[idx])
                cols.append(col)
        for c in n.get("children", []):
            walk(c, m)

    for r in scene["nodes"]:
        walk(r, np.eye(4))
    return np.concatenate(out), np.concatenate(cols)


def repack_images(js, binc):
    """Rebuild the binary chunk with images cut to IMAGE_MAX px (JPEG unless the image has alpha), views aligned."""
    views = []
    for bv in js["bufferViews"]:
        off = bv.get("byteOffset", 0)
        views.append(bytes(binc[off: off + bv["byteLength"]]))
    for img in js.get("images", []):
        if "bufferView" not in img:
            continue
        im = Image.open(io.BytesIO(views[img["bufferView"]]))
        alpha = im.mode in ("RGBA", "LA") and np.asarray(im.convert("RGBA"))[..., 3].min() < 250
        im = im.convert("RGBA" if alpha else "RGB")
        if max(im.size) > IMAGE_MAX:
            k = IMAGE_MAX / max(im.size)
            im = im.resize((max(1, int(im.size[0] * k)), max(1, int(im.size[1] * k))), Image.LANCZOS)
        b = io.BytesIO()
        if alpha:
            im.save(b, "PNG", optimize=True)
            img["mimeType"] = "image/png"
        else:
            im.save(b, "JPEG", quality=88)
            img["mimeType"] = "image/jpeg"
        views[img["bufferView"]] = b.getvalue()
    out = bytearray()
    for i, data in enumerate(views):
        while len(out) % 4:
            out.append(0)
        js["bufferViews"][i]["byteOffset"] = len(out)
        js["bufferViews"][i]["byteLength"] = len(data)
        out += data
    js["buffers"] = [{"byteLength": len(out)}]
    return out


# ------------------------------------------------------------------------------------------------- renders

def render(tris, cols, view, size=420, title=""):
    """Flat-shaded painter's render in metres. front: +Z towards the camera; side: from +X; top: from above, +Z down."""
    if view == "front":
        P = tris[..., [0, 1, 2]]
    elif view == "side":
        P = np.stack([-tris[..., 2], tris[..., 1], tris[..., 0]], -1)
    else:
        P = np.stack([tris[..., 0], -tris[..., 2], tris[..., 1]], -1)
    n = np.cross(tris[:, 1] - tris[:, 0], tris[:, 2] - tris[:, 0])
    n /= np.linalg.norm(n, axis=1, keepdims=True) + 1e-12
    light = np.array([0.35, 0.8, 0.5])
    light /= np.linalg.norm(light)
    shade = 0.5 + 0.5 * np.abs(n @ light)
    lo = P[..., :2].reshape(-1, 2).min(0)
    hi = P[..., :2].reshape(-1, 2).max(0)
    span = max(hi - lo) * 1.15 + 1e-9
    c = (lo + hi) / 2
    img = Image.new("RGB", (size, size), (236, 236, 232))
    d = ImageDraw.Draw(img)
    for i in np.argsort(P[..., 2].mean(1)):
        q = (P[i, :, :2] - c) / span * size + size / 2
        d.polygon([(float(x), float(size - y)) for x, y in q], fill=tuple(int(v) for v in np.clip(cols[i] * shade[i] * 255, 0, 255)))
    # the origin: a red cross (the calibration point)
    o = (-c) / span * size + size / 2
    ox, oy = float(o[0]), float(size - o[1])
    d.line([(ox - 8, oy), (ox + 8, oy)], fill=(220, 30, 30), width=2)
    d.line([(ox, oy - 8), (ox, oy + 8)], fill=(220, 30, 30), width=2)
    d.text((8, 8), title, fill=(30, 30, 30))
    return img


# ------------------------------------------------------------------------------------------------- one prop

def quat_yaw(deg):
    h = math.radians(deg) / 2
    return [0.0, math.sin(h), 0.0, math.cos(h)]


def process(name, spec, metrics):
    size_m, yaw, origin, task = spec
    src = os.path.join(RAW, f"lv_{name}.glb")
    js, binc = read_glb(src)
    tris, cols = triangles(js, binc)
    a = math.radians(yaw)
    rot = np.array([[math.cos(a), 0, math.sin(a)], [0, 1, 0], [-math.sin(a), 0, math.cos(a)]])
    tr = tris @ rot.T
    lo, hi = tr.reshape(-1, 3).min(0), tr.reshape(-1, 3).max(0)
    size = hi - lo
    given = [i for i in range(3) if size_m[i] is not None]
    uniform = float(np.mean([size_m[i] / size[i] for i in given]))
    sv = np.array([size_m[i] / size[i] if size_m[i] is not None else uniform for i in range(3)])
    # the point of the rotated file that goes on the origin
    cx, cz = (lo[0] + hi[0]) / 2, (lo[2] + hi[2]) / 2
    if name in BODY_X:
        # a handle on +X: the round body (as wide in x as it is deep in z) is centred, not the box with the handle
        cx = lo[0] + size[2] / 2
    if origin == "bottom":
        anchor, shift = np.array([cx, lo[1], cz]), np.zeros(3)
    elif origin == "top":
        anchor, shift = np.array([cx, hi[1], cz]), np.zeros(3)
    else:  # back: board centre, front face at z = -0.001
        anchor, shift = np.array([cx, (lo[1] + hi[1]) / 2, hi[2]]), np.array([0.0, 0.0, -0.001])
    # node: v' = S * (R v) + t   (glTF TRS applies scale first, so the turn sits on a child node)
    t = (-anchor * sv + shift).tolist()
    roots = list(js["scenes"][js.get("scene", 0)]["nodes"])
    js["nodes"].append({"name": f"turn:{name}", "children": roots, "rotation": quat_yaw(yaw)})
    js["nodes"].append({"name": f"calib:{name}", "children": [len(js["nodes"]) - 1], "translation": t, "scale": sv.tolist()})
    js["scenes"][js.get("scene", 0)]["nodes"] = [len(js["nodes"]) - 1]
    for mat in js.get("materials", []):
        mat["alphaMode"] = "OPAQUE"
        mat.pop("alphaCutoff", None)
        pbr = mat.setdefault("pbrMetallicRoughness", {})
        pbr["metallicFactor"] = 0.0
        pbr.setdefault("roughnessFactor", 0.8)
        f = pbr.get("baseColorFactor")
        if f is not None:
            pbr["baseColorFactor"] = [f[0], f[1], f[2], 1.0]
        mat.pop("extensions", None)
    js.pop("extensionsUsed", None)
    js.pop("extensionsRequired", None)
    binc2 = repack_images(js, binc)
    os.makedirs(ASSETS, exist_ok=True)
    dst = os.path.join(ASSETS, f"{name}.glb")
    write_glb(dst, js, binc2)
    # check what the app will get: read the written file back and measure it
    js3, binc3 = read_glb(dst)
    cal, cols3 = triangles(js3, binc3)
    lo2, hi2 = cal.reshape(-1, 3).min(0), cal.reshape(-1, 3).max(0)
    m = {
        "asset": f"props/{name}.glb", "tripoTask": task, "origin": origin, "yawDeg": yaw,
        "rawMin": [float(v) for v in tris.reshape(-1, 3).min(0)], "rawMax": [float(v) for v in tris.reshape(-1, 3).max(0)],
        "min": [float(v) for v in lo2], "max": [float(v) for v in hi2], "scale": [float(v) for v in sv],
        "triangles": int(len(cal)), "fileKB": os.path.getsize(dst) // 1024,
        # the front (+Z) of the top 15 % (the stand's mounting plate sits behind the plaque by this much)
        "topFrontZ": float(cal.reshape(-1, 3)[cal.reshape(-1, 3)[:, 1] > lo2[1] + 0.85 * (hi2[1] - lo2[1])][:, 2].max()),
        "opaque": all(mm.get("alphaMode") == "OPAQUE" for mm in js3.get("materials", [])),
    }
    metrics[name] = m
    os.makedirs(RENDERS, exist_ok=True)
    sz = hi2 - lo2
    row = [render(cal, cols3, v, 420, f"{name} {v}  {sz[0]:.3f} x {sz[1]:.3f} x {sz[2]:.3f} m") for v in ("front", "side", "top")]
    sheet = Image.new("RGB", (1260, 420))
    for i, im in enumerate(row):
        sheet.paste(im, (i * 420, 0))
    sheet.save(os.path.join(RENDERS, f"{name}_views.png"))
    print(f"  {name}: {sz[0]:.3f} x {sz[1]:.3f} x {sz[2]:.3f} m, {m['triangles']} tris, {m['fileKB']} KB, opaque={m['opaque']}")
    return cal, cols3


def kotlin(metrics):
    lines = [
        "package io.embodit.lowvision.domain",
        "",
        "// Generated by tools/make_props.py from the Tripo exports (v0.1.5, QA #3 q3-2). Do not edit by hand.",
        "",
        "/**",
        " * Scene props made with Tripo in the user's own account (profile B12): each GLB carries ONE calibration root node",
        " * (profile D25: origin where the scene expects it, face on +Z, true size in metres) and opaque materials (D24).",
        " * [box] is the calibrated box measured from the written file; [rawBox] the file as Tripo exported it (the fallback",
        " * test for a loader that would drop the root node). All instances of a prop share the one calibration.",
        " */",
        "enum class PropModel(val asset: String, val origin: String, val box: Box3, val rawBox: Box3, val yawDeg: Float, val topFrontZ: Float, val triangles: Int, val tripoTask: String) {",
    ]
    def v3(a):
        return f"Vec3({a[0]:.5f}f, {a[1]:.5f}f, {a[2]:.5f}f)"
    entries = []
    for name, m in metrics.items():
        entries.append(
            f"    {name.upper()}(\"{m['asset']}\", \"{m['origin']}\", Box3({v3(m['min'])}, {v3(m['max'])}), "
            f"Box3({v3(m['rawMin'])}, {v3(m['rawMax'])}), {m['yawDeg']:.1f}f, {m['topFrontZ']:.5f}f, {m['triangles']}, \"{m['tripoTask']}\")"
        )
    lines.append(",\n".join(entries) + ";")
    lines.append("}")
    lines.append("")
    with open(KOTLIN, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(lines))


def lineup(parts):
    """The table with the three items on it (front view), as the scene places them at the default slots."""
    tris, cols = [], []
    for name, offset in (("table", (0, 0, 0)), ("mug", (-0.22, 0, -0.05)), ("salt", (0.0, 0, -0.05)), ("glasses_case", (0.22, 0, -0.05))):
        t, c = parts[name]
        tris.append(t + np.array(offset))
        cols.append(c)
    t, c = np.concatenate(tris), np.concatenate(cols)
    row = [render(t, c, v, 600, f"table + items {v}") for v in ("front", "top")]
    sheet = Image.new("RGB", (1200, 600))
    for i, im in enumerate(row):
        sheet.paste(im, (i * 600, 0))
    sheet.save(os.path.join(RENDERS, "table_items_lineup.png"))


def inspect():
    for name in MODELS:
        js, binc = read_glb(os.path.join(RAW, f"lv_{name}.glb"))
        t, c = triangles(js, binc)
        lo, hi = t.reshape(-1, 3).min(0), t.reshape(-1, 3).max(0)
        mats = [(m.get("alphaMode", "OPAQUE"), m.get("pbrMetallicRoughness", {}).get("metallicFactor")) for m in js.get("materials", [])]
        imgs = [js["images"][i].get("mimeType") for i in range(len(js.get("images", [])))]
        print(f"{name}: tris={len(t)} min={np.round(lo, 3).tolist()} max={np.round(hi, 3).tolist()} size={np.round(hi - lo, 3).tolist()} mats={mats} images={imgs}")
        os.makedirs(RENDERS, exist_ok=True)
        row = [render(t, c, v, 360, f"raw {name} {v}") for v in ("front", "side", "top")]
        sheet = Image.new("RGB", (1080, 360))
        for i, im in enumerate(row):
            sheet.paste(im, (i * 360, 0))
        sheet.save(os.path.join(RENDERS, f"raw_{name}.png"))


def main():
    global RAW
    if len(sys.argv) > 1 and sys.argv[1] == "inspect":
        inspect()
        return
    if len(sys.argv) > 1:
        RAW = os.path.abspath(sys.argv[1])
    metrics, parts = {}, {}
    for name, spec in MODELS.items():
        parts[name] = process(name, spec, metrics)
    lineup(parts)
    with open(METRICS, "w", encoding="utf-8") as f:
        json.dump(metrics, f, indent=1)
    kotlin(metrics)
    print("wrote", KOTLIN)


if __name__ == "__main__":
    main()
