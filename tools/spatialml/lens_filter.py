"""Host prototype of the lens window filter (design spatialMl.filterModel, operator-only variant).

The same graph runs on the device in Kotlin (platform/lens/LensPipeline.kt); here it is traced with py2smr so that
pySpatialML can validate the operator graph and run it on the host executor against the sample photos.

    tools/spatialml/.venv/Scripts/python.exe tools/spatialml/lens_filter.py <samples_dir> <out_dir>

Masks (per pixel, replicated per channel in a flat [H, W*3] float layout):
    base = src + BM * (blur - src)
    out  = W1 * base + W2 * grey(blur) + C
with BM = blur mix, W1 = (1-dark)(1-veil)(1-desat), W2 = (1-dark)(1-veil)desat, C = (1-dark)*veil*128 + dark*tint.
The derived per-disease formulas are the design's (spatialMl.filterModel.derived); LensMasks.kt computes the same.
"""
import math
import os
import sys

import numpy as np
from securemr.py2smr import convert, ops, trace

H, W = 480, 640
HFOV_DEG = 90.0
TINT = {1: (0x12, 0x13, 0x14), 2: (0x3A, 0x35, 0x30), 3: (0x0E, 0x0F, 0x10)}
# design spatialMl.filterModel.severityTable: mode, r1, r2, edgeSoft, hemiSide, veil, desat, blurStrength
TABLE = {
    "glaucoma": [[1, 40, 0, 12, 0, 0, 0.6, 1], [1, 30, 0, 10, 0, 0.05, 0.6, 1], [1, 20, 0, 8, 0, 0.1, 0.6, 1], [1, 12, 0, 6, 0, 0.15, 0.6, 1], [1, 6, 0, 4, 0, 0.2, 0.6, 1]],
    "amd": [[2, 2.5, 0, 3, 0, 0.1, 0.9, 1], [2, 5, 0, 3, 0, 0.15, 0.9, 1], [2, 8, 0, 4, 0, 0.2, 0.9, 1], [2, 12, 0, 4, 0, 0.25, 0.9, 1], [2, 16, 0, 5, 0, 0.3, 0.9, 1]],
    "hemi-left": [[3, 0, 0, 2, -1, 0.05, 0, 1]],
    "hemi-right": [[3, 0, 0, 2, 1, 0.05, 0, 1]],
}


def smoothstep(e0, e1, x):
    t = np.clip((x - e0) / max(e1 - e0, 1e-6), 0, 1)
    return t * t * (3 - 2 * t)


def angle_grid():
    f = (W / 2) / math.tan(math.radians(HFOV_DEG / 2))
    xs = (np.arange(W) + 0.5 - W / 2) / f
    ys = (H / 2 - np.arange(H) - 0.5) / f
    x, y = np.meshgrid(xs, ys)
    theta = np.degrees(np.arctan(np.hypot(x, y)))
    theta_x = np.degrees(np.arctan(x))
    return theta, theta_x


def masks(params):
    mode, r1, _r2, edge, side, veil, _desat, _bs = params
    theta, theta_x = angle_grid()
    if mode == 1:
        t = smoothstep(r1, r1 + 1.6 * edge, theta)
        blur, dark, desat = t, 0.75 * t, 0.6 * t
    elif mode == 2:
        t = 1 - smoothstep(r1, r1 + 1.4 * edge, theta)
        blur, dark, desat = np.minimum(1, 1.2 * t), 0.55 * t, 0.9 * t
    elif mode == 3:
        t = smoothstep(-1, 1, side * theta_x - 2)
        blur, dark, desat = t, 0.95 * t, 0 * t
    else:
        z = np.zeros((H, W))
        blur = dark = desat = z
        veil = 0.0
    w1 = (1 - dark) * (1 - veil) * (1 - desat)
    w2 = (1 - dark) * (1 - veil) * desat
    tint = TINT.get(int(mode), (0, 0, 0))
    c = np.stack([(1 - dark) * veil * 128 + dark * tint[k] for k in range(3)], axis=-1)

    def rep(m):
        return np.repeat(m[..., None], 3, axis=-1).reshape(H, W * 3).astype(np.float32)

    return rep(blur), rep(w1), rep(w2), c.reshape(H, W * 3).astype(np.float32)


def affine(scale):
    # dst = scale * src, pixel centres aligned (OpenCV warpAffine maps src -> dst)
    off = 0.5 * scale - 0.5
    return np.array([[scale, 0, off], [0, scale, off]], dtype=np.float32)


DOWN, UP = affine(0.5), affine(2.0)


@trace(inputs=["src", "bm", "w1", "w2", "c"], outputs=["out"])
def lens(src, bm, w1, w2, c):
    s1 = ops.apply_affine(DOWN, src, output_shape=(240, 320, 3))
    s2 = ops.apply_affine(DOWN, s1, output_shape=(120, 160, 3))
    s3 = ops.apply_affine(DOWN, s2, output_shape=(60, 80, 3))
    u2 = ops.apply_affine(UP, s3, output_shape=(120, 160, 3))
    u1 = ops.apply_affine(UP, u2, output_shape=(240, 320, 3))
    blur = ops.apply_affine(UP, u1, output_shape=(480, 640, 3))
    grey1 = ops.cvt_color(blur, 7)  # COLOR_RGB2GRAY
    grey3 = ops.cvt_color(grey1, 8)  # COLOR_GRAY2RGB
    f = src.reshape(H, W * 3).astype(np.float32)
    b = blur.reshape(H, W * 3).astype(np.float32)
    g = grey3.reshape(H, W * 3).astype(np.float32)
    d = ops.arithmetic([b, f], "{0} - {1}")
    t = ops.elementwise_multiply(d, bm)
    base = ops.arithmetic([f, t], "{0} + {1}")
    p1 = ops.elementwise_multiply(base, w1)
    p2 = ops.elementwise_multiply(g, w2)
    out = ops.arithmetic([p1, p2, c], "{0} + {1} + {2}")
    return out


def main():
    import cv2

    src_dir, out_dir = sys.argv[1], sys.argv[2]
    os.makedirs(out_dir, exist_ok=True)
    for photo in ("kitchen", "pills", "stairs"):
        img = cv2.cvtColor(cv2.imread(os.path.join(src_dir, photo + ".png")), cv2.COLOR_BGR2RGB)
        img = cv2.resize(img, (W, H))
        for name, rows in TABLE.items():
            for level, params in enumerate(rows, start=1):
                if name.startswith("hemi") or level in (1, 3, 5):
                    bm, w1, w2, c = masks(params)
                    out = lens(img, bm, w1, w2, c)
                    rgb = np.clip(out, 0, 255).astype(np.uint8).reshape(H, W, 3)
                    cv2.imwrite(os.path.join(out_dir, f"{photo}-{name}-{level}.png"), cv2.cvtColor(rgb, cv2.COLOR_RGB2BGR))
    bm, w1, w2, c = masks([0, 0, 0, 0, 0, 0, 0, 0])
    ident = lens(img, bm, w1, w2, c)
    print("identity max abs diff:", float(np.abs(ident - img.reshape(H, W * 3)).max()))
    _res, ctx = lens.trace(src=img, bm=bm, w1=w1, w2=w2, c=c)
    convert(ctx, output=os.path.join(out_dir, "lens_pipeline.json"))
    print("wrote", os.path.join(out_dir, "lens_pipeline.json"))


if __name__ == "__main__":
    main()
