package sh3d.gpurenderer;

import java.awt.EventQueue;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.ImageObserver;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;

import com.eteks.sweethome3d.j3d.AbstractPhotoRenderer;
import com.eteks.sweethome3d.j3d.Object3DBranchFactory;
import com.eteks.sweethome3d.model.Camera;
import com.eteks.sweethome3d.model.Compass;
import com.eteks.sweethome3d.model.Home;
import com.eteks.sweethome3d.model.Selectable;
import com.eteks.sweethome3d.viewcontroller.Object3DFactory;

/**
 * A renderer computing images on the GPU with Cycles, the path tracer of Blender.
 * The home is exported once and kept loaded in a Blender process until this renderer is disposed.
 */
public class BlenderRenderer extends AbstractPhotoRenderer {
  private static final String BLENDER_PROPERTY = "sh3d.gpurenderer.blender";
  private static final int    BLENDER_MIN_MAJOR_VERSION = 4;
  private static final String WORKER_SCRIPT = "worker.py";
  private static final String FRAME_FILE = "frame.png";

  private static final Map<String, Boolean> availableBlenders = new ConcurrentHashMap<String, Boolean>();

  private final Object3DFactory object3dFactory;
  private File                  sessionFolder;
  private boolean               sceneExported;
  private volatile BlenderWorker worker;
  private volatile boolean      stopped;

  public BlenderRenderer(Home home, Object3DFactory object3dFactory, Quality quality) {
    super(home, quality);
    this.object3dFactory = object3dFactory != null
        ? object3dFactory
        : new Object3DBranchFactory();
  }

  @Override
  public String getName() {
    return "Blender Cycles (GPU)";
  }

  /**
   * Returns <code>true</code> if a recent enough Blender can be run.
   */
  @Override
  public boolean isAvailable() {
    return isBlenderAvailable(getBlender());
  }

  private static String getBlender() {
    return System.getProperty(BLENDER_PROPERTY, "blender");
  }

  static boolean isBlenderAvailable(String blender) {
    Boolean available = availableBlenders.get(blender);
    if (available == null) {
      available = false;
      try {
        Process process = new ProcessBuilder(blender, "--version").redirectErrorStream(true).start();
        process.getOutputStream().close();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor(30, TimeUnit.SECONDS)) {
          Matcher version = Pattern.compile("^Blender (\\d+)\\.", Pattern.MULTILINE).matcher(output);
          available = version.find() && Integer.parseInt(version.group(1)) >= BLENDER_MIN_MAJOR_VERSION;
        } else {
          process.destroyForcibly();
        }
      } catch (IOException ex) {
        // Blender can't be run
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
      }
      availableBlenders.put(blender, available);
    }
    return available;
  }

  /**
   * Returns the folder where the home is exported, or <code>null</code> if nothing was rendered yet.
   */
  File getSessionFolder() {
    return this.sessionFolder;
  }

  @Override
  public void render(BufferedImage image, Camera camera,
                     List<? extends Selectable> updatedItems,
                     final ImageObserver observer) throws IOException {
    this.stopped = false;
    try {
      if (this.sessionFolder == null) {
        this.sessionFolder = Files.createTempDirectory("sh3d-gpu-renderer").toFile();
        InputStream script = BlenderRenderer.class.getResourceAsStream(WORKER_SCRIPT);
        try {
          Files.copy(script, new File(this.sessionFolder, WORKER_SCRIPT).toPath(), StandardCopyOption.REPLACE_EXISTING);
        } finally {
          script.close();
        }
      }
      boolean sceneUpdated = !this.sceneExported
          || updatedItems != null && !updatedItems.isEmpty();
      if (sceneUpdated) {
        SceneExporter.export(getHome(), this.object3dFactory, this.sessionFolder);
        this.sceneExported = true;
      }

      BlenderWorker worker = this.worker;
      if (worker == null || !worker.isAlive()) {
        worker = new BlenderWorker(Arrays.asList(getBlender(), "-b", "--factory-startup",
            "--python", new File(this.sessionFolder, WORKER_SCRIPT).getPath()));
        this.worker = worker;
        if ("CPU".equals(worker.getDevice())) {
          System.err.println("Blender renderer: no GPU usable by Cycles, rendering with CPU");
        }
        if (this.stopped) {
          // Stop was requested while Blender was starting
          worker.kill();
        }
        sceneUpdated = true;
      }
      if (sceneUpdated) {
        Map<String, Object> load = new LinkedHashMap<String, Object>();
        load.put("cmd", "load");
        load.put("scene", new File(this.sessionFolder, SceneExporter.SCENE_FILE).getPath());
        worker.send(load);
      }

      File frameFile = new File(this.sessionFolder, FRAME_FILE);
      worker.send(createRenderCommand(camera, image.getWidth(), image.getHeight(), frameFile));
      BufferedImage frame = ImageIO.read(frameFile);
      if (frame == null) {
        throw new IOException("Blender didn't write a readable image in " + frameFile);
      }
      Graphics2D g2D = image.createGraphics();
      g2D.drawImage(frame, 0, 0, null);
      g2D.dispose();
      notifyObserver(image, observer);
    } catch (IOException ex) {
      BlenderWorker worker = this.worker;
      if (worker != null) {
        // Start again from a new process at next rendering
        worker.kill();
      }
      if (!this.stopped) {
        throw ex;
      }
    }
  }

  private Map<String, Object> createRenderCommand(Camera camera, int width, int height, File output) {
    // Pitch isn't used with a lens showing all directions
    float pitch = camera.getLens() == Camera.Lens.SPHERICAL ? 0 : camera.getPitch();
    float yaw = camera.getYaw();
    float yawSin = (float)Math.sin(yaw);
    float yawCos = (float)Math.cos(yaw);
    float pitchSin = (float)Math.sin(pitch);
    float pitchCos = (float)Math.cos(pitch);
    Map<String, Object> cameraDescription = new LinkedHashMap<String, Object>();
    cameraDescription.put("position", new float [] {camera.getX(), camera.getZ(), camera.getY()});
    cameraDescription.put("direction", new float [] {-yawSin * pitchCos, -pitchSin, yawCos * pitchCos});
    // Perpendicular to direction and still defined when the camera looks at the top or the bottom
    cameraDescription.put("up", new float [] {-yawSin * pitchSin, pitchCos, yawCos * pitchSin});
    cameraDescription.put("fov", camera.getFieldOfView());
    cameraDescription.put("lens", camera.getLens().name());

    Map<String, Object> render = new LinkedHashMap<String, Object>();
    render.put("cmd", "render");
    render.put("output", output.getPath());
    render.put("width", width);
    render.put("height", height);
    render.put("samples", Integer.parseInt(getRenderingParameterValue("samples")));
    render.put("camera", cameraDescription);
    render.put("sunDirection", getSunDirection(camera));
    return render;
  }

  /**
   * Returns the direction of the Sun at the time of <code>camera</code>, as Sweet Home 3D computes it.
   */
  private float [] getSunDirection(Camera camera) {
    Compass compass = getHome().getCompass();
    long time = Camera.convertTimeToTimeZone(camera.getTime(), compass.getTimeZone());
    float elevation = compass.getSunElevation(time);
    float azimuth = compass.getSunAzimuth(time);
    azimuth += compass.getNorthDirection() - Math.PI / 2f;
    return new float [] {(float)(Math.cos(azimuth) * Math.cos(elevation)),
                         (float)Math.sin(elevation),
                         (float)(Math.sin(azimuth) * Math.cos(elevation))};
  }

  private void notifyObserver(final BufferedImage image, final ImageObserver observer) {
    if (observer != null) {
      EventQueue.invokeLater(new Runnable() {
          public void run() {
            observer.imageUpdate(image, ImageObserver.FRAMEBITS | ImageObserver.WIDTH | ImageObserver.HEIGHT
                | ImageObserver.PROPERTIES, 0, 0, image.getWidth(), image.getHeight());
          }
        });
    }
  }

  /**
   * Stops the current rendering by ending Blender, which will be restarted at next rendering.
   */
  @Override
  public void stop() {
    this.stopped = true;
    BlenderWorker worker = this.worker;
    if (worker != null) {
      worker.kill();
    }
  }

  @Override
  public void dispose() {
    BlenderWorker worker = this.worker;
    this.worker = null;
    if (worker != null) {
      worker.quit();
    }
    if (this.sessionFolder != null) {
      File [] files = this.sessionFolder.listFiles();
      if (files != null) {
        for (File file : files) {
          file.delete();
        }
      }
      this.sessionFolder.delete();
      this.sessionFolder = null;
      this.sceneExported = false;
    }
  }
}
