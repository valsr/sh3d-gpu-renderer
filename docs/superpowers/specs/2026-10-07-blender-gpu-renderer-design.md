# Blender GPU renderer for Sweet Home 3D — design

Date: 2026-10-07

## Goal

Sweet Home 3D 7.5 on Linux renders photos and videos with two CPU renderers
(SunFlow and YafaRay) that are slow and give mediocre results. Add a third
renderer that hands the scene to Blender Cycles running on the GPU, so that a
photo taking minutes comes back in seconds and looks at least as good as the
YafaRay output, and so that video frames do not pay a Blender start-up each.

Target machine: Manjaro, AMD Radeon RX 7800 XT, Blender 5.2.1 with Cycles HIP,
OpenJDK 26, Sweet Home 3D 7.5 from the distro package. It only has to work
there; packaging for other machines is out of scope.

## Integration

The renderer appears as a third entry in the stock *Create photo* and *Create
video* dialogs. Sweet Home 3D builds that list from the system property
`com.eteks.sweethome3d.j3d.rendererClassNames` and instantiates entries with
`Class.forName` through a `(Home, Object3DFactory, Quality)` constructor, so:

- no Sweet Home 3D source is modified;
- the class must be on the application classpath, which a `.sh3p` plugin cannot
  provide. The deliverable is therefore `gpu-renderer.jar` plus a launcher that
  adds it to the classpath and sets the property.

## Components

| Unit | Language | Responsibility |
|---|---|---|
| `BlenderRenderer` | Java | `AbstractPhotoRenderer` subclass. Session lifecycle: `isAvailable`, `render`, `stop`, `dispose`. |
| `SceneExporter` | Java | Writes the home to a folder: `scene.obj`, `.mtl` and textures through Sweet Home 3D's `OBJWriter`, plus `scene.json`. |
| `BlenderWorker` | Java | Starts one headless Blender process and exchanges line-based messages with it. |
| `Json` | Java | Minimal JSON writer (no third-party dependency). |
| `worker.py` | Python (bpy) | Runs inside Blender. Imports the scene, builds materials, lights and world, answers render commands. Bundled in the jar, extracted to the session folder. |

The Java package is `sh3d.gpurenderer`, outside Sweet Home 3D's own packages,
so nothing can depend on its package-private members.

The Java side knows nothing about Cycles. `worker.py` knows nothing about Sweet
Home 3D beyond the files it is given, so it can be run from a shell against a
saved export folder.

## Scene files

`scene.obj` uses Sweet Home 3D's conventions: centimetres, Y up. Items are
written with stable object names so lamps can be matched to their materials.

`scene.json`:

```json
{
  "obj": "scene.obj",
  "lightColor": [r, g, b],
  "skyColor": [r, g, b],
  "skyTexture": "sky.png" | null,
  "groundColor": [r, g, b],
  "northDirection": radians,
  "lights": [
    {"position": [x, y, z], "color": [r, g, b], "radius": cm, "power": 0..1}
  ],
  "emissiveMaterials": [{"name": "...", "power": 0..1}]
}
```

Positions are in Sweet Home 3D's Java3D frame (cm, Y up); the worker converts
to Blender's (m, Z up) in one place.

## Protocol

Java to Blender, one JSON object per line on stdin:

- `{"cmd":"load","scene":"/path/scene.json"}`
- `{"cmd":"render","output":"/path/frame.png","width":W,"height":H,"samples":N,
   "camera":{"position":[x,y,z],"yaw":rad,"pitch":rad,"fov":rad,"lens":"PINHOLE|NORMAL|FISHEYE|SPHERICAL"},
   "sunDirection":[x,y,z]}`
- `{"cmd":"quit"}`

Blender to Java, on stdout, only lines starting with `@@SH3D ` are protocol;
everything else is Blender's own output and is kept as a rolling log tail:

- `@@SH3D READY device=HIP|CPU` once after start-up
- `@@SH3D OK`
- `@@SH3D ERR <message>`

## Data flow

1. First `render()`: export the scene, start Blender, wait for `READY`, send `load`.
2. Every `render()`: send `render`, wait for `OK`, read the PNG into the
   caller's `BufferedImage`, notify the `ImageObserver`.
3. Video reuses the renderer instance, so each frame is step 2 only.
4. `updatedItems` non-empty: re-export and `load` again before rendering.
5. `stop()`: destroys the Blender process (Cycles cannot be interrupted from
   the script). `render()` returns without throwing, as the stock renderers do.
   The next `render()` starts a new process.
6. `dispose()`: sends `quit`, destroys the process if it lingers, deletes the
   session folder.

## Look mapping

- Materials: OBJ import gives Principled BSDF with colour, texture, roughness
  from shininess, and alpha. Untextured materials with alpha below 0.5 become
  glass-like (transmission) so windows pass light.
- Lamps: each light source becomes a point light with the source colour, the
  source radius and a wattage proportional to lamp power squared. Materials
  named as light-source materials get emission.
- Sun and sky: Nishita-type sky texture with the sun direction computed from
  the home's compass and the camera's time. Below the horizon the world goes
  near-black. A sky texture in the home replaces the procedural sky.
- Lenses: `PINHOLE` and `NORMAL` are perspective; `FISHEYE` and `SPHERICAL`
  are Cycles panoramic (fisheye equidistant 180°, equirectangular).
- Quality: `LOW` 64 samples, `HIGH` 512, both with OpenImageDenoise. Values
  live in `BlenderRenderer.properties`, overridable by system property through
  the inherited `getRenderingParameterValue` mechanism.

Light intensities are expected to need tuning by eye against a real home; the
scale factors are named constants at the top of `worker.py`.

## Failure handling

- Blender missing or older than 4.0: `isAvailable()` is false and the entry
  does not appear. Path defaults to `blender`, overridable with
  `-Dsh3d.gpurenderer.blender=/path`.
- No GPU device: Cycles runs on CPU; `READY device=CPU` is logged to stderr.
- Blender exits or replies `ERR`: `render()` throws `IOException` carrying the
  message and the last lines of Blender output.

## Build and installation

- `make` compiles with `javac` against `/usr/share/java/sweethome3d/SweetHome3D.jar`
  and the Java3D jars under `/usr/lib/sweethome3d`, and produces
  `build/gpu-renderer.jar`.
- `bin/sweethome3d-gpu` is a launcher equivalent to the distro one with the jar
  and the property added. Replacing `/usr/local/bin/sweethome3d` with it is a
  manual step for the user.

## Testing

No JUnit on the machine, so Java tests are plain classes with a `main`, run by
`make test`.

- `worker.py`: a script drives a real Blender against a small fixture scene and
  checks image size, selected device, and that a lit pixel is brighter than the
  same pixel with the lamp removed.
- Java: `Json` output, `BlenderWorker` protocol against a fake process (a shell
  script), `SceneExporter` output for a small home built in code.
- End to end: render a home built in code through `BlenderRenderer` and check
  the image is not uniform.
- By eye: same camera in YafaRay and in this renderer, with timings.

## Out of scope

Progressive preview during a render, incremental updates for animated
furniture, extra controls in the dialogs, EEVEE as an alternative engine,
packaging for other machines or operating systems.
