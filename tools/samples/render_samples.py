"""Renders the lens window's three sample photos (design P9: kitchen / pills / stairs), 640 x 480, original content.

    "C:/Program Files/Blender Foundation/Blender 5.2/blender.exe" -b -P tools/samples/render_samples.py -- <out_dir>

Simple modelled scenes (no downloaded assets, no licence questions). The camera has a 90 degree horizontal field of
view, the same as the angle grid the filter masks assume (LensMasks.HFOV_DEG).
"""
import math
import os
import sys

import bpy

OUT = sys.argv[sys.argv.index("--") + 1] if "--" in sys.argv else os.path.abspath("samples")
os.makedirs(OUT, exist_ok=True)


def reset():
    bpy.ops.wm.read_factory_settings(use_empty=True)
    scn = bpy.context.scene
    scn.render.engine = "BLENDER_EEVEE" if "BLENDER_EEVEE" in [e.identifier for e in bpy.types.RenderSettings.bl_rna.properties["engine"].enum_items] else "BLENDER_EEVEE_NEXT"
    scn.render.resolution_x = 640
    scn.render.resolution_y = 480
    scn.render.resolution_percentage = 100
    scn.render.image_settings.file_format = "PNG"
    scn.render.image_settings.color_mode = "RGB"
    world = bpy.data.worlds.new("w")
    world.use_nodes = True
    world.node_tree.nodes["Background"].inputs[0].default_value = (0.85, 0.87, 0.9, 1)
    world.node_tree.nodes["Background"].inputs[1].default_value = 0.25
    scn.world = world
    try:
        scn.view_settings.view_transform = "Standard"
    except Exception:
        pass
    return scn


def mat(name, rgb, rough=0.6, metal=0.0, emit=None):
    m = bpy.data.materials.new(name)
    m.use_nodes = True
    b = m.node_tree.nodes["Principled BSDF"]
    b.inputs["Base Color"].default_value = (*rgb, 1)
    b.inputs["Roughness"].default_value = rough
    b.inputs["Metallic"].default_value = metal
    if emit:
        b.inputs["Emission Color"].default_value = (*emit, 1)
        b.inputs["Emission Strength"].default_value = 3.0
    return m


def box(name, loc, size, m, rot=(0, 0, 0)):
    bpy.ops.mesh.primitive_cube_add(location=loc, rotation=rot)
    o = bpy.context.object
    o.name = name
    o.scale = (size[0] / 2, size[1] / 2, size[2] / 2)
    o.data.materials.append(m)
    return o


def cyl(name, loc, r, h, m, verts=40):
    bpy.ops.mesh.primitive_cylinder_add(location=loc, radius=r, depth=h, vertices=verts)
    o = bpy.context.object
    o.name = name
    o.data.materials.append(m)
    bpy.ops.object.shade_smooth()
    return o


def text(body, loc, size, m, rot=(math.radians(90), 0, 0), align="CENTER"):
    bpy.ops.object.text_add(location=loc, rotation=rot)
    o = bpy.context.object
    o.data.body = body
    o.data.size = size
    o.data.align_x = align
    o.data.align_y = "CENTER"
    o.data.extrude = 0.001
    o.data.materials.append(m)
    return o


def camera(loc, look_at, hfov_deg=90):
    bpy.ops.object.camera_add(location=loc)
    cam = bpy.context.object
    cam.data.sensor_fit = "HORIZONTAL"
    cam.data.angle = math.radians(hfov_deg)
    d = [look_at[i] - loc[i] for i in range(3)]
    yaw = math.atan2(d[1], d[0]) - math.pi / 2
    pitch = math.atan2(d[2], math.hypot(d[0], d[1]))
    cam.rotation_euler = (math.pi / 2 + pitch, 0, yaw)
    bpy.context.scene.camera = cam


def light(kind, loc, energy, size=1.0, rot=(0, 0, 0), color=(1, 0.97, 0.92)):
    bpy.ops.object.light_add(type=kind, location=loc, rotation=rot)
    l = bpy.context.object
    l.data.energy = energy
    l.data.color = color
    if kind == "AREA":
        l.data.size = size
    return l


def room(w=5, d=5, h=2.7, wall=(0.92, 0.9, 0.86), floor=(0.55, 0.42, 0.3)):
    box("floor", (0, 0, -0.01), (w, d, 0.02), mat("floor", floor, 0.5))
    box("ceiling", (0, 0, h), (w, d, 0.02), mat("ceil", (0.95, 0.95, 0.95)))
    wm = mat("wall", wall, 0.8)
    box("wallN", (0, d / 2, h / 2), (w, 0.05, h), wm)
    box("wallW", (-w / 2, 0, h / 2), (0.05, d, h), wm)
    box("wallE", (w / 2, 0, h / 2), (0.05, d, h), wm)


def kitchen():
    reset()
    room(wall=(0.88, 0.9, 0.86))
    wood = mat("wood", (0.62, 0.45, 0.28), 0.5)
    white = mat("white", (0.93, 0.93, 0.92), 0.3)
    stone = mat("stone", (0.3, 0.32, 0.34), 0.35)
    steel = mat("steel", (0.8, 0.8, 0.82), 0.25, 0.9)
    box("counter", (0, 2.2, 0.45), (3.6, 0.6, 0.9), white)
    box("top", (0, 2.2, 0.92), (3.7, 0.65, 0.04), stone)
    for i, x in enumerate([-1.35, -0.45, 0.45, 1.35]):
        box(f"door{i}", (x, 1.89, 0.45), (0.86, 0.02, 0.8), wood)
        box(f"hd{i}", (x, 1.87, 0.75), (0.2, 0.02, 0.02), steel)
    for i, x in enumerate([-1.2, 0, 1.2]):
        box(f"upper{i}", (x, 2.35, 1.9), (1.15, 0.35, 0.7), white)
    box("window", (0, 2.49, 1.45), (1.0, 0.02, 0.5), mat("glass", (0.8, 0.9, 1.0), 0.1, emit=(0.9, 0.95, 1.0)))
    cyl("mug", (-0.6, 2.1, 1.0), 0.045, 0.1, mat("mugm", (0.95, 0.95, 0.93), 0.2))
    cyl("kettle", (0.5, 2.2, 1.04), 0.09, 0.2, steel)
    box("board", (0.05, 2.12, 0.95), (0.4, 0.25, 0.02), wood)
    cyl("fruitbowl", (-1.2, 2.2, 0.98), 0.14, 0.08, mat("bowl", (0.2, 0.45, 0.6), 0.4))
    for i, (x, c) in enumerate([(-1.25, (0.9, 0.3, 0.1)), (-1.15, (0.95, 0.75, 0.1)), (-1.2, (0.4, 0.7, 0.2))]):
        bpy.ops.mesh.primitive_uv_sphere_add(location=(x, 2.2 + (i - 1) * 0.05, 1.05), radius=0.05)
        bpy.context.object.data.materials.append(mat(f"fruit{i}", c, 0.5))
    text("厨房 KITCHEN", (0, 2.52 - 0.05, 2.38), 0.12, mat("ink", (0.1, 0.1, 0.1)))
    box("table", (0.2, 0.6, 0.74), (1.2, 0.8, 0.04), wood)
    for sx in (-1, 1):
        for sy in (-1, 1):
            box("leg", (0.2 + sx * 0.55, 0.6 + sy * 0.35, 0.37), (0.04, 0.04, 0.74), wood)
    cyl("cup2", (0.4, 0.5, 0.81), 0.04, 0.1, mat("cup2", (0.95, 0.95, 0.95), 0.2))
    light("AREA", (0, 0.8, 2.6), 90, 2.0)
    light("SUN", (2, -2, 4), 0.8, rot=(math.radians(50), 0, math.radians(30)))
    camera((0, -1.4, 1.55), (0, 2.2, 1.05))
    render("kitchen")


def pills():
    reset()
    room(wall=(0.9, 0.88, 0.84), floor=(0.5, 0.4, 0.32))
    wood = mat("wood", (0.66, 0.5, 0.34), 0.5)
    box("table", (0, 0.5, 0.74), (1.6, 0.9, 0.04), wood)
    base = mat("org", (0.95, 0.95, 0.95), 0.4)
    lids = [(0.85, 0.25, 0.25), (0.95, 0.6, 0.15), (0.95, 0.85, 0.2), (0.35, 0.7, 0.35), (0.25, 0.55, 0.85), (0.45, 0.35, 0.8), (0.8, 0.4, 0.7)]
    days = ["一", "二", "三", "四", "五", "六", "日"]
    ink = mat("ink", (0.08, 0.08, 0.08))
    for i in range(7):
        x = -0.36 + i * 0.12
        box(f"cell{i}", (x, 0.45, 0.78), (0.11, 0.09, 0.04), base)
        box(f"lid{i}", (x, 0.45, 0.805), (0.11, 0.09, 0.008), mat(f"lid{i}", lids[i], 0.35))
        text(days[i], (x, 0.45, 0.811), 0.05, ink, rot=(0, 0, 0))
    bottle = mat("bottle", (0.75, 0.45, 0.15), 0.2)
    cyl("bottle", (0.5, 0.7, 0.82), 0.045, 0.14, bottle)
    cyl("cap", (0.5, 0.7, 0.905), 0.047, 0.03, mat("cap", (0.95, 0.95, 0.95)))
    label = mat("label", (0.97, 0.97, 0.95), 0.6)
    box("label", (0.5, 0.653, 0.82), (0.07, 0.004, 0.06), label)
    text("每日两次", (0.5, 0.649, 0.83), 0.014, ink)
    text("饭后", (0.5, 0.649, 0.808), 0.012, ink)
    box("glasses", (-0.55, 0.65, 0.78), (0.16, 0.06, 0.04), mat("case", (0.12, 0.22, 0.4), 0.4))
    cyl("water", (-0.6, 0.35, 0.82), 0.04, 0.12, mat("glass", (0.85, 0.92, 0.95), 0.05))
    box("paper", (0.15, 0.25, 0.762), (0.3, 0.2, 0.002), mat("paper", (0.98, 0.98, 0.96), 0.7))
    text("周一 早 8:00", (0.15, 0.27, 0.764), 0.025, ink, rot=(0, 0, 0))
    text("周一 晚 20:00", (0.15, 0.22, 0.764), 0.025, ink, rot=(0, 0, 0))
    light("AREA", (0, 0.4, 2.2), 40, 1.5)
    light("SUN", (1, -2, 4), 0.6, rot=(math.radians(40), 0, math.radians(20)))
    camera((0, -0.15, 1.2), (0, 0.55, 0.78))
    render("pills")


def stairs():
    reset()
    room(w=4, d=6, h=4.0, wall=(0.86, 0.88, 0.9), floor=(0.45, 0.36, 0.28))
    tread = mat("tread", (0.58, 0.44, 0.3), 0.5)
    riser = mat("riser", (0.92, 0.92, 0.9), 0.7)
    for i in range(12):
        y = 0.8 + i * 0.28
        z = (i + 1) * 0.17
        box(f"riser{i}", (0, y - 0.14, z - 0.085), (1.2, 0.02, 0.17), riser)
        box(f"tread{i}", (0, y, z), (1.2, 0.3, 0.03), tread)
    steel = mat("rail", (0.15, 0.15, 0.16), 0.4, 0.6)
    for i in range(0, 12, 2):
        y = 0.8 + i * 0.28
        z = (i + 1) * 0.17
        box(f"post{i}", (0.62, y, z + 0.45), (0.03, 0.03, 0.9), steel)
    ang = math.atan2(0.17, 0.28)
    box("rail", (0.62, 0.8 + 5.5 * 0.28, 6.5 * 0.17 + 0.9), (0.04, 12 * 0.28 / math.cos(ang), 0.04), steel, rot=(ang, 0, 0))
    shoe = mat("shoe", (0.22, 0.24, 0.27), 0.6)
    box("shoe", (-0.3, 0.8 + 2 * 0.28, 3 * 0.17 + 0.05), (0.1, 0.28, 0.08), shoe)
    box("mat", (0, 0.2, 0.005), (1.0, 0.6, 0.01), mat("mat", (0.5, 0.2, 0.2), 0.9))
    box("frame", (-1.95, 1.5, 1.6), (0.02, 0.5, 0.4), mat("pic", (0.3, 0.5, 0.6), 0.5))
    light("AREA", (0, 2.0, 3.8), 120, 2.5)
    light("SUN", (2, -3, 5), 0.7, rot=(math.radians(45), 0, math.radians(25)))
    camera((0.15, -1.0, 1.6), (0, 2.0, 1.0))
    render("stairs")


def render(name):
    path = os.path.join(OUT, f"{name}.png")
    bpy.context.scene.render.filepath = path
    bpy.ops.render.render(write_still=True)
    print("WROTE", path)


kitchen()
pills()
stairs()
