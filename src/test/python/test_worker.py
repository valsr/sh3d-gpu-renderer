"""Tests worker.py inside Blender:
blender -b --factory-startup --python-exit-code 1 --python src/test/python/test_worker.py
"""
import importlib.util
import json
import os
import sys
import tempfile

import bpy

HERE = os.path.dirname(os.path.abspath(__file__))
WORKER = os.path.join(HERE, "..", "..", "main", "resources", "sh3d", "gpurenderer", "worker.py")
spec = importlib.util.spec_from_file_location("worker", WORKER)
worker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(worker)

TMP = tempfile.mkdtemp(prefix="sh3d-worker-test")
print("images in", TMP)

# Sweet Home 3D frame: centimeters, Y up. A grey 8 m floor and a red 1 m cube centered at x = +150 cm
OBJ = """mtllib scene.mtl
o floor
v -400 0 -400
v -400 0 400
v 400 0 400
v 400 0 -400
vn 0 1 0
usemtl grey
f 1//1 2//1 3//1 4//1
o cube
v 100 0 -50
v 200 0 -50
v 200 0 50
v 100 0 50
v 100 100 -50
v 200 100 -50
v 200 100 50
v 100 100 50
usemtl red
f 9 12 11 10
f 9 10 6 5
f 10 11 7 6
f 11 12 8 7
f 12 9 5 8
usemtl bulb
f 5 6 7 8
"""
MTL = """newmtl grey
Kd 0.6 0.6 0.6
newmtl red
Kd 0.9 0.02 0.02
newmtl bulb
Kd 1 1 1
d 0.4
"""
NIGHT = [0.3, -0.8, 0.5]
NOON = [0.3, 0.8, 0.5]


def write_scene(name, lights, emissive=(), opaque=()):
    folder = os.path.join(TMP, name)
    os.makedirs(folder)
    with open(os.path.join(folder, "scene.obj"), "w") as f:
        f.write(OBJ)
    with open(os.path.join(folder, "scene.mtl"), "w") as f:
        f.write(MTL)
    scene = {"obj": "scene.obj", "lightColor": [1, 1, 1], "skyColor": [0.8, 0.9, 1], "skyTexture": None,
             "groundColor": [0.5, 0.5, 0.5], "northDirection": 0,
             "lights": lights, "emissiveMaterials": list(emissive), "opaqueMaterials": list(opaque)}
    path = os.path.join(folder, "scene.json")
    with open(path, "w") as f:
        json.dump(scene, f)
    return path


def render(name, sun, position=(0, 600, 0), direction=(0, -1, 0), up=(0, 0, 1), lens="PINHOLE",
           width=64, height=48, exposure=None):
    """Renders from above by default and returns (width, height, RGBA float pixels from bottom left)."""
    output = os.path.join(TMP, name + ".png")
    command = {"output": output, "width": width, "height": height, "samples": 16,
               "camera": {"position": list(position), "direction": list(direction), "up": list(up),
                          "fov": 1.1, "lens": lens},
               "sunDirection": sun}
    if exposure is not None:
        command["exposure"] = exposure
    worker.render(command)
    image = bpy.data.images.load(output)
    result = (image.size[0], image.size[1], list(image.pixels))
    bpy.data.images.remove(image)
    return result


def brightness(pixels, width, x0, x1, y0, y1):
    """Mean of R+G+B over pixel columns [x0, x1) and rows [y0, y1)."""
    total = 0
    for y in range(y0, y1):
        for x in range(x0, x1):
            i = (y * width + x) * 4
            total += pixels[i] + pixels[i + 1] + pixels[i + 2]
    return total / ((x1 - x0) * (y1 - y0))


def redness(pixels, width, x0, x1, y0, y1):
    total = 0
    for y in range(y0, y1):
        for x in range(x0, x1):
            i = (y * width + x) * 4
            total += pixels[i] - (pixels[i + 1] + pixels[i + 2]) / 2
    return total / ((x1 - x0) * (y1 - y0))


def check(condition, message):
    if not condition:
        raise AssertionError(message)
    print("ok:", message)


device = worker.configure_device()
expected_device = os.environ.get("SH3D_EXPECT_DEVICE", "HIP")
check(device == expected_device, "Cycles device is %s (got %s)" % (expected_device, device))

# Night without any lamp
worker.load({"scene": write_scene("dark", [])})
check(bpy.context.scene.render.engine == "CYCLES", "scene renders with Cycles")
check(bpy.context.scene.cycles.device == ("CPU" if device == "CPU" else "GPU"), "scene renders on the configured device")
check(bpy.context.scene.cycles.use_denoising, "images are denoised")
check(bpy.context.scene.cycles.denoising_use_gpu == (device != "CPU"), "denoising runs on the GPU when there's one")
w, h, dark = render("dark", NIGHT)
check((w, h) == (64, 48), "image has the requested size (got %dx%d)" % (w, h))
dark_floor = brightness(dark, w, 24, 40, 16, 32)

# Seen from above with image top towards +Z, a right handed frame shows +X on the left
w, h, noon = render("noon", NOON)
check(redness(noon, w, 16, 22, 21, 27) > 0.2, "cube at +X is in the left half seen from above")
check(redness(noon, w, 42, 48, 21, 27) < 0.05, "no cube in the right half seen from above")
noon_floor = brightness(noon, w, 24, 40, 16, 32)
check(noon_floor > 10 * dark_floor + 0.3, "floor is lit by day (%.3f) and dark by night (%.3f)" % (noon_floor, dark_floor))
check(noon_floor < 2.9, "sunlit floor isn't burnt out (%.3f)" % noon_floor)

# Sun at 45 degrees towards +X: the 1 m high cube shades the floor at its -X side
w, h, morning = render("morning", [0.7, 0.7, 0])
shaded = brightness(morning, w, 25, 31, 22, 26)
sunny = brightness(morning, w, 8, 13, 22, 26)
check(shaded < 0.6 * sunny, "cube shadow is opposite to the sun (%.3f in shadow, %.3f in the sun)" % (shaded, sunny))

# Camera at eye height looking horizontally at the cube sees it in the image center
w, h, front = render("front", NOON, position=(-300, 50, 0), direction=(1, 0, 0), up=(0, 1, 0))
check(redness(front, w, 28, 36, 20, 28) > 0.2, "cube is in the center when the camera looks at it")
w, h, back = render("back", NOON, position=(-300, 50, 0), direction=(-1, 0, 0), up=(0, 1, 0))
check(redness(back, w, 0, 64, 0, 48) < 0.05, "cube isn't visible when the camera looks away")

# Panoramic lenses
w, h, spherical = render("spherical", NOON, position=(-300, 50, 0), direction=(-1, 0, 0), up=(0, 1, 0),
                         lens="SPHERICAL", width=96, height=48)
check(redness(spherical, w, 0, 2, 22, 26) + redness(spherical, w, 94, 96, 22, 26) > 0.4,
      "spherical lens sees the cube behind the camera at the image sides")
w, h, fisheye = render("fisheye", NOON, position=(-300, 50, 0), direction=(1, 0, 0), up=(0, 1, 0),
                       lens="FISHEYE", width=48, height=48)
check(brightness(fisheye, w, 0, 3, 0, 3) < 0.01, "fisheye lens leaves image corners black")
check(redness(fisheye, w, 22, 26, 22, 26) > 0.2, "fisheye lens sees the cube in the center")

# Night with a lamp above the floor center
lamp = {"position": [0, 200, 0], "color": [1, 1, 1], "radius": 5, "power": 0.5}
worker.load({"scene": write_scene("lamp", [lamp])})
w, h, lit = render("lamp", NIGHT)
lit_floor = brightness(lit, w, 24, 40, 16, 32)
check(lit_floor > 10 * dark_floor + 0.3, "floor is lit by a lamp at night (%.3f against %.3f)" % (lit_floor, dark_floor))
check(len([o for o in bpy.data.objects if o.type == "LIGHT"]) == 1, "loading a scene replaces the previous one")

# Exposure brightens or darkens the image, and an image rendered without it isn't changed
w, h, brighter = render("lamp_brighter", NIGHT, exposure=2)
brighter_floor = brightness(brighter, w, 24, 40, 16, 32)
check(brighter_floor > lit_floor * 1.2, "exposure brightens the image (%.3f against %.3f)" % (brighter_floor, lit_floor))
w, h, darker = render("lamp_darker", NIGHT, exposure=-2)
darker_floor = brightness(darker, w, 24, 40, 16, 32)
check(darker_floor < lit_floor * 0.8, "negative exposure darkens the image (%.3f against %.3f)" % (darker_floor, lit_floor))
w, h, unexposed = render("lamp_unexposed", NIGHT)
unexposed_floor = brightness(unexposed, w, 24, 40, 16, 32)
check(abs(unexposed_floor - lit_floor) < lit_floor * 0.05,
      "exposure is back to none without it (%.3f against %.3f)" % (unexposed_floor, lit_floor))

# Light source materials
worker.load({"scene": write_scene("emissive", [], [{"name": "bulb", "power": 0.5}])})
bulb = bpy.data.materials["bulb"].node_tree.nodes["Principled BSDF"]
check(bulb.inputs["Emission Strength"].default_value > 0, "light source material emits light")
red = bpy.data.materials["red"].node_tree.nodes["Principled BSDF"]
check(red.inputs["Emission Strength"].default_value == 0, "other materials don't emit light")

# Materials of walls made transparent in the 3D view
check(abs(bulb.inputs["Alpha"].default_value - 0.4) < 0.01, "transparent material imported with its transparency")
worker.load({"scene": write_scene("opaque", [], opaque=["bulb"])})
bulb = bpy.data.materials["bulb"].node_tree.nodes["Principled BSDF"]
check(bulb.inputs["Alpha"].default_value == 1, "listed material made opaque")

# Glass lets most of the light through, as with the glass shader of the default renderer
def glass_scene(name, glass):
    """Writes a scene with a floor under a wide pane 3 m above it, if glass is True."""
    path = write_scene(name, [])
    folder = os.path.dirname(path)
    with open(os.path.join(folder, "scene.obj"), "a") as f:
        if glass:
            f.write("o pane\nv -3000 300 -3000\nv -3000 300 3000\nv 3000 300 3000\nv 3000 300 -3000\n"
                    "usemtl glass\nf 13 14 15 16\n")
    with open(os.path.join(folder, "scene.mtl"), "a") as f:
        f.write("newmtl glass\nKd 0.8 0.8 0.8\nd 0.5\n")
    return path


def lit_floor_under(name, glass):
    """Returns the linear brightness of the floor seen from under the pane."""
    worker.load({"scene": glass_scene(name, glass)})
    view_settings = bpy.context.scene.view_settings
    view_settings.view_transform = "Standard"
    w, h, pixels = render(name, NOON, position=(-150, 250, 0), exposure=-3)
    value = brightness(pixels, w, 24, 40, 16, 32) / 3
    check(value < 0.9, "floor isn't overexposed (%.3f)" % value)
    return ((value + 0.055) / 1.055) ** 2.4


open_floor = lit_floor_under("no_glass", False)
glass_floor = lit_floor_under("glass", True)
check(glass_floor > 0.8 * open_floor,
      "glass lets most of the light through (%.3f under glass, %.3f without)" % (glass_floor, open_floor))
check(glass_floor < 0.97 * open_floor,
      "glass stops a part of the light (%.3f under glass, %.3f without)" % (glass_floor, open_floor))
bpy.context.scene.view_settings.view_transform = "AgX"

# Textured materials show their image and use its transparency
folder = os.path.dirname(write_scene("textured", []))
texture = bpy.data.images.new("texture", 4, 4, alpha=True)
texture.pixels = [0.0, 0.0, 1.0, 0.5] * 16
texture.filepath_raw = os.path.join(folder, "texture.png")
texture.file_format = "PNG"
texture.save()
with open(os.path.join(folder, "scene.obj"), "w") as f:
    f.write("mtllib scene.mtl\nv -400 0 -400\nv -400 0 400\nv 400 0 400\nv 400 0 -400\n"
            "vt 0 0\nvt 0 1\nvt 1 1\nvt 1 0\nvn 0 1 0\nusemtl textured\nf 1/1/1 2/2/1 3/3/1 4/4/1\n")
with open(os.path.join(folder, "scene.mtl"), "w") as f:
    f.write("newmtl textured\nKd 1 1 1\nmap_Kd texture.png\n")
worker.load({"scene": os.path.join(folder, "scene.json")})
textured = bpy.data.materials["textured"].node_tree.nodes["Principled BSDF"]
check(textured.inputs["Base Color"].is_linked, "texture image colors the material")
check(textured.inputs["Alpha"].is_linked, "texture image transparency is used")
w, h, blue = render("textured", NOON)
i = (24 * w + 32) * 4
check(blue[i + 2] > blue[i] + 0.1, "floor shows its blue texture (%.2f %.2f %.2f)" % tuple(blue[i:i + 3]))

# Occluders block light without being seen: a ceiling 2.5 m above the floor, written in its own file
# with a material named like one of the scene
path = write_scene("capped", [])
folder = os.path.dirname(path)
with open(os.path.join(folder, "occluders.obj"), "w") as f:
    f.write("mtllib occluders.mtl\no ceiling\nv -400 250 -400\nv -400 250 400\nv 400 250 400\nv 400 250 -400\n"
            "vn 0 -1 0\nusemtl grey\nf 1//1 2//1 3//1 4//1\n")
with open(os.path.join(folder, "occluders.mtl"), "w") as f:
    f.write("newmtl grey\nKd 0.6 0.6 0.6\n")
with open(path) as f:
    capped_scene = json.load(f)
capped_scene["occluders"] = "occluders.obj"
with open(path, "w") as f:
    json.dump(capped_scene, f)
worker.load({"scene": path})
meshes = [o for o in bpy.data.objects if o.type == "MESH"]
check(len(meshes) == 3, "scene and occluders are loaded")
check(len([o for o in meshes if not o.visible_camera]) == 1, "occluder is hidden from the camera only")
check(all(o.visible_shadow and o.visible_diffuse for o in meshes), "occluder still stops light")
w, h, capped = render("capped", NOON)
check(redness(capped, w, 16, 22, 21, 27) > 0.05, "cube is seen through the hidden ceiling")
capped_floor = brightness(capped, w, 24, 40, 16, 32)
check(capped_floor < noon_floor * 0.5,
      "hidden ceiling shades the floor (%.3f under it, %.3f without)" % (capped_floor, noon_floor))
check(capped_floor > dark_floor, "floor under the hidden ceiling still gets light from the sides")

# Occluders may stop the sun only, letting the light of the sky through
capped_scene["occludersBlock"] = "sun"
with open(path, "w") as f:
    json.dump(capped_scene, f)
worker.load({"scene": path})
w, h, sun_capped = render("sun_capped", NOON)
check(redness(sun_capped, w, 16, 22, 21, 27) > 0.05, "cube is seen through the ceiling hiding the sun")
sun_capped_floor = brightness(sun_capped, w, 24, 40, 16, 32)
check(capped_floor * 1.2 < sun_capped_floor < noon_floor * 0.8,
      "ceiling hiding the sun only leaves the light of the sky (%.3f, between %.3f and %.3f)"
      % (sun_capped_floor, capped_floor, noon_floor))
# The sun is hidden wherever it is, which the same directions without occluders are compared to below
SUN_DIRECTIONS = {"east": [0.4, 0.85, 0.1], "south_west": [-0.3, 0.85, -0.3]}
sun_capped_floors = {}
for name, direction in SUN_DIRECTIONS.items():
    w, h, pixels = render("sun_capped_" + name, direction)
    sun_capped_floors[name] = brightness(pixels, w, 24, 40, 16, 32)
check(bpy.context.scene.cycles.transparent_max_bounces == worker.SUN_BLOCKER_MAX_BOUNCES,
      "rays may cross many occluders stopping the sun only")

# A scene loaded afterwards has no occluder left
worker.load({"scene": write_scene("uncapped", [])})
check(all(o.visible_camera for o in bpy.data.objects if o.type == "MESH"), "no object hidden without occluders")
check(bpy.context.scene.cycles.transparent_max_bounces < worker.SUN_BLOCKER_MAX_BOUNCES,
      "usual count of transparent bounces without occluders stopping the sun")
for name, direction in SUN_DIRECTIONS.items():
    w, h, pixels = render("sun_" + name, direction)
    open_floor = brightness(pixels, w, 24, 40, 16, 32)
    check(sun_capped_floors[name] < open_floor * 0.75,
          "sun at %s doesn't reach the floor under occluders (%.3f, %.3f without)"
          % (name, sun_capped_floors[name], open_floor))

# An occluders file without any object is ignored
path = write_scene("empty_occluders", [])
with open(os.path.join(os.path.dirname(path), "occluders.obj"), "w") as f:
    f.write("mtllib occluders.mtl\n")
with open(path) as f:
    empty_scene = json.load(f)
empty_scene["occluders"] = "occluders.obj"
with open(path, "w") as f:
    json.dump(empty_scene, f)
worker.load({"scene": path})
check(len([o for o in bpy.data.objects if o.type == "MESH"]) == 2, "scene loaded with an empty occluders file")

print("test_worker OK")
