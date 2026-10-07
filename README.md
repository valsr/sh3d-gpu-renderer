# Blender GPU renderer for Sweet Home 3D

Adds a third renderer, "Blender Cycles (GPU)", to the *Create photo* and *Create video*
dialogs of Sweet Home 3D 7.5. The home is exported once and rendered by a headless
Blender process running Cycles on the GPU. Design: `docs/superpowers/specs/`.

## Requirements

Sweet Home 3D 7.5 (distro package paths, see the top of `Makefile`), a JDK 17 or later,
Blender 4.0 or later on `PATH` with a GPU that Cycles supports.

## Build and test

    make          # build/gpu-renderer.jar
    make test     # Java tests and worker tests (starts Blender, needs a display)

## Install

    make
    sudo make install     # copies the jar to /usr/lib/sweethome3d/gpu-renderer/

Then add the jar as a Java agent to the options Sweet Home 3D is started with, for example
in a launcher wrapper:

    export JAVA_TOOL_OPTIONS="-javaagent:/usr/lib/sweethome3d/gpu-renderer/gpu-renderer.jar"

`sudo make uninstall` removes it. `INSTALL_DIR` and `DESTDIR` change where the jar goes.

The renderer is used when quality is set to one of the two highest levels of the dialogs.

## Settings

System properties, all optional:

| Property | Default | Meaning |
|---|---|---|
| `sh3d.gpurenderer.blender` | `blender` | Blender executable |
| `sh3d.gpurenderer.BlenderRenderer.lowQuality.samples` | 64 | Samples per pixel at the third quality level |
| `sh3d.gpurenderer.BlenderRenderer.highQuality.samples` | 256 | Samples per pixel at the fourth quality level |

Light and sky intensities are constants at the top of
`src/main/resources/sh3d/gpurenderer/worker.py`.

## Render from the command line

    make render ARGS="Studio build/studio.png 1280 720 HIGH sh3d.gpurenderer.BlenderRenderer 0"

Arguments: a `.sh3d` file or the name of a bundled example, the output image, width, height,
`LOW` or `HIGH`, the renderer class, and a camera: the index of a stored camera or
`x,y,z,yaw,pitch` with angles in degrees (the current camera
of the home if omitted).

## Limits

- No preview while an image is computed; it appears when finished.
- Stopping a render ends Blender, so the next one reloads the scene.
- A video with animated furniture re-exports the whole home at each frame where something moved.
- Light sources of lamps rotated around a horizontal axis may be slightly misplaced.
