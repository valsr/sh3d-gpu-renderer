"""Renders scenes exported by Sweet Home 3D with Cycles. Run with:
blender -b --factory-startup --python worker.py

Reads one JSON command per line on stdin (load, render, quit) and answers each one
with a line starting with @@SH3D on stdout. See the design spec for the protocol.

Scene files use Sweet Home 3D's frame: centimeters, Y up, right handed.
"""
import json
import math
import os
import sys
import traceback

import bpy
from mathutils import Matrix, Vector

PROTOCOL_PREFIX = "@@SH3D "

# Look tuning
SKY_STRENGTH = 0.06              # Background strength of the physical sky
NIGHT_SKY_COLOR = (0.002, 0.003, 0.006)
SUN_MIN_HEIGHT = -0.075          # Sun direction height under which it's night, as in Sweet Home 3D
LAMP_WATTS = 600                 # Point light energy for a light source at full power
EMISSION_STRENGTH = 20           # Emission of a light source material at full power
TRANSPARENT_ROUGHNESS = 0.05     # Glossiness given to see through materials
GPU_DEVICE_TYPES = ("OPTIX", "HIP", "ONEAPI", "CUDA", "METAL")


def to_blender(v):
    """Converts a vector from Sweet Home 3D's frame (Y up) to Blender's (Z up), without scaling."""
    return Vector((v[0], -v[2], v[1]))


def to_blender_location(p):
    return to_blender(p) * 0.01


def configure_device():
    """Enables the first available kind of GPU in Cycles preferences and returns its type, or CPU."""
    preferences = bpy.context.preferences.addons["cycles"].preferences
    for device_type in GPU_DEVICE_TYPES:
        try:
            preferences.compute_device_type = device_type
        except TypeError:
            continue
        preferences.get_devices()
        if any(device.type == device_type for device in preferences.devices):
            for device in preferences.devices:
                device.use = device.type == device_type
            for hardware_ray_tracing in ("use_hiprt", "use_oneapirt"):
                if hasattr(preferences, hardware_ray_tracing):
                    setattr(preferences, hardware_ray_tracing, True)
            return device_type
    preferences.compute_device_type = "NONE"
    return "CPU"


def clear_data():
    for collection in (bpy.data.objects, bpy.data.meshes, bpy.data.lights, bpy.data.cameras,
                       bpy.data.materials, bpy.data.images, bpy.data.worlds):
        for block in list(collection):
            collection.remove(block)


def configure_scene(scene):
    scene.render.engine = "CYCLES"
    scene.cycles.device = "CPU" if bpy.context.preferences.addons["cycles"].preferences.compute_device_type == "NONE" else "GPU"
    scene.cycles.use_adaptive_sampling = True
    scene.cycles.use_denoising = True
    scene.cycles.max_bounces = 8
    # Keep the scene in GPU memory between the frames of a video
    scene.render.use_persistent_data = True
    scene.render.resolution_percentage = 100
    scene.render.image_settings.file_format = "PNG"
    scene.render.image_settings.color_mode = "RGB"
    scene.render.image_settings.compression = 0
    scene.render.film_transparent = False


def find_principled(material):
    if material.node_tree is not None:
        for node in material.node_tree.nodes:
            if node.type == "BSDF_PRINCIPLED":
                return node
    return None


def set_emission(principled, color, strength):
    color_input = principled.inputs.get("Emission Color") or principled.inputs.get("Emission")
    if not color_input.is_linked:
        color_input.default_value = (color[0], color[1], color[2], 1)
    principled.inputs["Emission Strength"].default_value = strength


def adapt_materials(scene_description):
    emissive = {m["name"]: m["power"] for m in scene_description["emissiveMaterials"]}
    for material in bpy.data.materials:
        principled = find_principled(material)
        if principled is None:
            continue
        tree = material.node_tree
        base_color = principled.inputs["Base Color"]
        alpha = principled.inputs["Alpha"]
        # Use the transparency of texture images
        if base_color.is_linked and not alpha.is_linked:
            texture = base_color.links[0].from_node
            if texture.type == "TEX_IMAGE" and texture.image is not None and texture.image.channels == 4:
                tree.links.new(texture.outputs["Alpha"], alpha)
        if not alpha.is_linked and alpha.default_value < 0.5:
            principled.inputs["Roughness"].default_value = TRANSPARENT_ROUGHNESS
        if material.name in emissive:
            set_emission(principled, base_color.default_value, EMISSION_STRENGTH * emissive[material.name])
        else:
            principled.inputs["Emission Strength"].default_value = 0


def create_lights(scene, scene_description):
    light_color = scene_description["lightColor"]
    for i, source in enumerate(scene_description["lights"]):
        light = bpy.data.lights.new("light%d" % i, "POINT")
        light.color = [source["color"][c] * light_color[c] for c in range(3)]
        light.energy = LAMP_WATTS * source["power"] ** 2
        light.shadow_soft_size = source["radius"] * 0.01
        light_object = bpy.data.objects.new(light.name, light)
        light_object.location = to_blender_location(source["position"])
        scene.collection.objects.link(light_object)


def create_world(scene, scene_description, folder):
    """Creates a world lit by a physical sky, showing the sky texture of the home to the camera if it has one."""
    world = bpy.data.worlds.new("world")
    world.use_nodes = True
    nodes = world.node_tree.nodes
    links = world.node_tree.links
    nodes.clear()
    output = nodes.new("ShaderNodeOutputWorld")
    background = nodes.new("ShaderNodeBackground")
    background.name = "sky_background"
    sky = nodes.new("ShaderNodeTexSky")
    sky.name = "sky"
    sky_types = [item.identifier for item in sky.bl_rna.properties["sky_type"].enum_items]
    sky.sky_type = next(t for t in ("MULTIPLE_SCATTERING", "NISHITA", "HOSEK_WILKIE") if t in sky_types)
    light_color = scene_description["lightColor"]
    tint = nodes.new("ShaderNodeMixRGB")
    tint.name = "sky_tint"
    tint.blend_type = "MULTIPLY"
    tint.inputs["Fac"].default_value = 1
    tint.inputs["Color2"].default_value = (light_color[0], light_color[1], light_color[2], 1)
    links.new(sky.outputs["Color"], tint.inputs["Color1"])
    links.new(tint.outputs["Color"], background.inputs["Color"])
    shader = background.outputs["Background"]

    if scene_description.get("skyTexture"):
        image = bpy.data.images.load(os.path.join(folder, scene_description["skyTexture"]))
        environment = nodes.new("ShaderNodeTexEnvironment")
        environment.image = image
        visible = nodes.new("ShaderNodeBackground")
        visible.name = "sky_texture_background"
        links.new(environment.outputs["Color"], visible.inputs["Color"])
        light_path = nodes.new("ShaderNodeLightPath")
        mix = nodes.new("ShaderNodeMixShader")
        links.new(light_path.outputs["Is Camera Ray"], mix.inputs["Fac"])
        links.new(shader, mix.inputs[1])
        links.new(visible.outputs["Background"], mix.inputs[2])
        shader = mix.outputs["Shader"]

    links.new(shader, output.inputs["Surface"])
    scene.world = world


def update_sun(scene, sun_direction):
    nodes = scene.world.node_tree.nodes
    sky = nodes["sky"]
    background = nodes["sky_background"]
    direction = to_blender(sun_direction).normalized()
    day = direction.z > SUN_MIN_HEIGHT
    if day:
        if not background.inputs["Color"].is_linked:
            scene.world.node_tree.links.new(nodes["sky_tint"].outputs["Color"], background.inputs["Color"])
        sky.sun_elevation = math.asin(max(-1, min(1, direction.z)))
        sky.sun_rotation = math.atan2(direction.x, direction.y)
        background.inputs["Strength"].default_value = SKY_STRENGTH
    else:
        for link in list(background.inputs["Color"].links):
            scene.world.node_tree.links.remove(link)
        background.inputs["Color"].default_value = NIGHT_SKY_COLOR + (1,)
        background.inputs["Strength"].default_value = 1
    if "sky_texture_background" in nodes:
        nodes["sky_texture_background"].inputs["Strength"].default_value = 1 if day else 0.02


def load(command):
    """Replaces the current scene by the one described in the JSON file command["scene"]."""
    with open(command["scene"]) as f:
        scene_description = json.load(f)
    folder = os.path.dirname(command["scene"])

    clear_data()
    scene = bpy.context.scene
    configure_scene(scene)
    bpy.ops.wm.obj_import(filepath=os.path.join(folder, scene_description["obj"]),
                          forward_axis="NEGATIVE_Z", up_axis="Y", global_scale=0.01)
    adapt_materials(scene_description)
    create_lights(scene, scene_description)
    create_world(scene, scene_description, folder)

    camera = bpy.data.objects.new("camera", bpy.data.cameras.new("camera"))
    scene.collection.objects.link(camera)
    scene.camera = camera


def update_camera(scene, camera_description, width, height):
    camera_object = scene.camera
    camera = camera_object.data
    lens = camera_description["lens"]
    if lens in ("SPHERICAL", "FISHEYE"):
        camera.type = "PANO"
        settings = camera if hasattr(camera, "panorama_type") else camera.cycles
        if lens == "SPHERICAL":
            settings.panorama_type = "EQUIRECTANGULAR"
        else:
            settings.panorama_type = "FISHEYE_EQUIDISTANT"
            settings.fisheye_fov = math.pi
    else:
        camera.type = "PERSP"
        camera.sensor_fit = "HORIZONTAL"
        camera.angle = camera_description["fov"]
    camera.clip_start = 0.01
    camera.clip_end = 100000

    # Blender cameras look along their -Z axis with +Y up
    forward = to_blender(camera_description["direction"]).normalized()
    up = to_blender(camera_description["up"]).normalized()
    right = forward.cross(up).normalized()
    up = right.cross(forward)
    rotation = Matrix((right, up, -forward)).transposed()
    camera_object.matrix_world = Matrix.Translation(to_blender_location(camera_description["position"])) @ rotation.to_4x4()


def render(command):
    """Renders the loaded scene in the PNG file command["output"]."""
    scene = bpy.context.scene
    scene.render.resolution_x = command["width"]
    scene.render.resolution_y = command["height"]
    scene.cycles.samples = command["samples"]
    update_camera(scene, command["camera"], command["width"], command["height"])
    update_sun(scene, command["sunDirection"])
    scene.render.filepath = command["output"]
    bpy.ops.render.render(write_still=True)


def reply(message):
    # Start with a new line in case Blender left an unfinished line in its own output
    sys.stdout.write("\n" + PROTOCOL_PREFIX + message + "\n")
    sys.stdout.flush()


def main():
    reply("READY device=" + configure_device())
    commands = {"load": load, "render": render}
    while True:
        line = sys.stdin.readline()
        if not line:
            break
        try:
            command = json.loads(line)
            if command["cmd"] == "quit":
                break
            commands[command["cmd"]](command)
            reply("OK")
        except Exception as ex:
            traceback.print_exc(file=sys.stdout)
            reply("ERR " + " ".join(str(ex).split()))


if __name__ == "__main__":
    main()
