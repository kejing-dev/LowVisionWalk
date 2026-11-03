"""Writes the lens window's assets (design E6): the 0.59 x 0.4425 m panel glb and the three sample photos as raw RGB.

    python -I tools/spatialml/make_lens_assets.py [<samples_dir>]      (no samples_dir: the panel only)

The panel must fit the SpatialML container (LensController: 1500 x 1125 units = 0.6 x 0.45 m, 0.4 mm per unit): up to
v0.1.2 it was 1.2 x 0.9 m in a 1200 x 900 container (0.48 x 0.36 m), which clipped it to its middle 40 % (QA #2: the
glaucoma edge never showed, the AMD patch looked 2.5 times too big).

app/src/main/assets/lens/
  lens_panel.glb      one quad, front +Z, PBR material 0 with a base colour texture (replaced at run time by the
                      pipeline's dynamic texture). glTF UV (0, 0) is the image's top-left corner.
  <photo>.rgb         640 x 480 x 3 bytes, row 0 = top (kitchen, pills, stairs; rendered by tools/samples)
"""
import io
import json
import os
import struct
import sys

from PIL import Image

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "lens")
W_M, H_M = 0.59, 0.4425


def png_bytes():
    im = Image.new("RGB", (4, 4), (40, 44, 50))
    b = io.BytesIO()
    im.save(b, "PNG")
    return b.getvalue()


def panel_glb(path):
    hw, hh = W_M / 2, H_M / 2
    pos = [(-hw, -hh, 0), (hw, -hh, 0), (hw, hh, 0), (-hw, hh, 0)]
    nrm = [(0, 0, 1)] * 4
    uv = [(0, 1), (1, 1), (1, 0), (0, 0)]  # glTF: v = 0 at the image top
    idx = [0, 1, 2, 0, 2, 3]
    buf = bytearray()

    def add(data):
        while len(buf) % 4:
            buf.append(0)
        off = len(buf)
        buf.extend(data)
        return off, len(data)

    p_off, p_len = add(b"".join(struct.pack("<3f", *v) for v in pos))
    n_off, n_len = add(b"".join(struct.pack("<3f", *v) for v in nrm))
    t_off, t_len = add(b"".join(struct.pack("<2f", *v) for v in uv))
    i_off, i_len = add(b"".join(struct.pack("<H", i) for i in idx))
    img = png_bytes()
    g_off, g_len = add(img)
    while len(buf) % 4:
        buf.append(0)
    gltf = {
        "asset": {"version": "2.0", "generator": "low-vision make_lens_assets.py"},
        "scene": 0,
        "scenes": [{"nodes": [0]}],
        "nodes": [{"name": "panel", "mesh": 0}],
        "meshes": [{"name": "panel", "primitives": [{"attributes": {"POSITION": 0, "NORMAL": 1, "TEXCOORD_0": 2}, "indices": 3, "material": 0}]}],
        "materials": [{"name": "lens", "doubleSided": True, "pbrMetallicRoughness": {"baseColorTexture": {"index": 0}, "metallicFactor": 0.0, "roughnessFactor": 1.0}}],
        "textures": [{"source": 0, "sampler": 0}],
        "samplers": [{"magFilter": 9729, "minFilter": 9729, "wrapS": 33071, "wrapT": 33071}],
        "images": [{"bufferView": 4, "mimeType": "image/png"}],
        "buffers": [{"byteLength": len(buf)}],
        "bufferViews": [
            {"buffer": 0, "byteOffset": p_off, "byteLength": p_len, "target": 34962},
            {"buffer": 0, "byteOffset": n_off, "byteLength": n_len, "target": 34962},
            {"buffer": 0, "byteOffset": t_off, "byteLength": t_len, "target": 34962},
            {"buffer": 0, "byteOffset": i_off, "byteLength": i_len, "target": 34963},
            {"buffer": 0, "byteOffset": g_off, "byteLength": g_len},
        ],
        "accessors": [
            {"bufferView": 0, "componentType": 5126, "count": 4, "type": "VEC3", "min": [-hw, -hh, 0], "max": [hw, hh, 0]},
            {"bufferView": 1, "componentType": 5126, "count": 4, "type": "VEC3"},
            {"bufferView": 2, "componentType": 5126, "count": 4, "type": "VEC2"},
            {"bufferView": 3, "componentType": 5123, "count": 6, "type": "SCALAR"},
        ],
    }
    js = json.dumps(gltf, separators=(",", ":")).encode()
    js += b" " * ((4 - len(js) % 4) % 4)
    total = 12 + 8 + len(js) + 8 + len(buf)
    with open(path, "wb") as f:
        f.write(struct.pack("<III", 0x46546C67, 2, total))
        f.write(struct.pack("<II", len(js), 0x4E4F534A) + js)
        f.write(struct.pack("<II", len(buf), 0x004E4942) + bytes(buf))


def main():
    os.makedirs(OUT, exist_ok=True)
    panel_glb(os.path.join(OUT, "lens_panel.glb"))
    if len(sys.argv) < 2:
        print("wrote", os.path.join(OUT, "lens_panel.glb"))
        return
    samples = sys.argv[1]
    for name in ("kitchen", "pills", "stairs"):
        im = Image.open(os.path.join(samples, name + ".png")).convert("RGB").resize((640, 480))
        with open(os.path.join(OUT, name + ".rgb"), "wb") as f:
            f.write(im.tobytes())
    print("wrote", OUT)


if __name__ == "__main__":
    main()
