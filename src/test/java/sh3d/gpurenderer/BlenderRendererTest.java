package sh3d.gpurenderer;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.concurrent.atomic.AtomicInteger;

import com.eteks.sweethome3d.j3d.AbstractPhotoRenderer;
import com.eteks.sweethome3d.j3d.Object3DBranchFactory;
import com.eteks.sweethome3d.model.Camera;
import com.eteks.sweethome3d.model.Home;

/**
 * Renders a home with a real Blender.
 */
public class BlenderRendererTest {
  /**
   * Returns the count of pixels which differ notably between two images of the same size.
   */
  private static int countDifferentPixels(BufferedImage image1, BufferedImage image2) {
    int count = 0;
    for (int y = 0; y < image1.getHeight(); y++) {
      for (int x = 0; x < image1.getWidth(); x++) {
        int rgb1 = image1.getRGB(x, y);
        int rgb2 = image2.getRGB(x, y);
        int difference = Math.abs((rgb1 >> 16 & 0xFF) - (rgb2 >> 16 & 0xFF))
            + Math.abs((rgb1 >> 8 & 0xFF) - (rgb2 >> 8 & 0xFF))
            + Math.abs((rgb1 & 0xFF) - (rgb2 & 0xFF));
        if (difference > 48) {
          count++;
        }
      }
    }
    return count;
  }

  /**
   * Saves <code>image</code> in build/test-output folder to check it by eye.
   */
  private static void save(BufferedImage image, String name) throws java.io.IOException {
    File folder = new File("build/test-output");
    folder.mkdirs();
    javax.imageio.ImageIO.write(image, "png", new File(folder, name + ".png"));
  }

  /**
   * Returns the mean of the red, green and blue components of the pixels of <code>image</code>.
   */
  private static double getBrightness(BufferedImage image) {
    long total = 0;
    for (int y = 0; y < image.getHeight(); y++) {
      for (int x = 0; x < image.getWidth(); x++) {
        int rgb = image.getRGB(x, y);
        total += ((rgb >> 16) & 0xFF) + ((rgb >> 8) & 0xFF) + (rgb & 0xFF);
      }
    }
    return total / (3. * image.getWidth() * image.getHeight());
  }

  public static void main(String [] args) throws Exception {
    System.setProperty("sh3d.gpurenderer.BlenderRenderer.lowQuality.samples", "16");
    Check.isTrue(!BlenderRenderer.isBlenderAvailable("/nonexistent/blender"), "missing Blender detected");
    Check.isTrue(!BlenderRenderer.isBlenderAvailable("true"), "program which isn't Blender detected");

    // Sweet Home 3D lists the renderer in its dialogs when its class is declared
    System.setProperty("com.eteks.sweethome3d.j3d.rendererClassNames",
        "com.eteks.sweethome3d.j3d.PhotoRenderer," + BlenderRenderer.class.getName());
    Check.isTrue(AbstractPhotoRenderer.getAvailableRenderers().contains(BlenderRenderer.class.getName()),
        "renderer listed by Sweet Home 3D");

    Home home = TestHomes.createRoomHome(TestHomes.createLamp(false));
    BlenderRenderer renderer = new BlenderRenderer(home, new Object3DBranchFactory(), AbstractPhotoRenderer.Quality.LOW);
    Check.isTrue(renderer.getName().length() > 0, "renderer has a name");

    // Camera in a corner of the room looking at its center, then at the corner
    Camera camera = new Camera(60, 60, 150, -0.9f, 0.2f, 1.1f);
    BufferedImage black = new BufferedImage(160, 120, BufferedImage.TYPE_INT_RGB);
    BufferedImage image = new BufferedImage(160, 120, BufferedImage.TYPE_INT_RGB);
    AtomicInteger updates = new AtomicInteger();
    long start = System.currentTimeMillis();
    renderer.render(image, camera, (img, flags, x, y, width, height) -> {
        updates.incrementAndGet();
        return true;
      });
    long firstRenderTime = System.currentTimeMillis() - start;
    save(image, "room-center");
    Check.isTrue(countDifferentPixels(image, black) > 160 * 120 / 2, "most of the image of a lit room isn't black");
    java.awt.EventQueue.invokeAndWait(() -> { });
    Check.isTrue(updates.get() > 0, "observer notified");
    File sessionFolder = renderer.getSessionFolder();
    Check.isTrue(new File(sessionFolder, "scene.obj").exists(), "scene exported in session folder");

    BufferedImage otherImage = new BufferedImage(160, 120, BufferedImage.TYPE_INT_RGB);
    start = System.currentTimeMillis();
    renderer.render(otherImage, new Camera(60, 60, 150, 2.3f, 0.2f, 1.1f), null);
    long secondRenderTime = System.currentTimeMillis() - start;
    save(otherImage, "room-corner");
    Check.isTrue(countDifferentPixels(image, otherImage) > 160 * 120 / 20, "image changes with the camera");
    System.out.println("first render " + firstRenderTime + " ms, second render " + secondRenderTime + " ms");
    Check.isTrue(secondRenderTime < firstRenderTime, "second render reuses the running Blender");

    // Stopping from an other thread ends the current rendering without error
    System.setProperty("sh3d.gpurenderer.BlenderRenderer.lowQuality.samples", "100000");
    Thread stopper = new Thread(() -> {
        try {
          Thread.sleep(1500);
        } catch (InterruptedException ex) {
        }
        renderer.stop();
      });
    stopper.start();
    start = System.currentTimeMillis();
    renderer.render(new BufferedImage(1920, 1080, BufferedImage.TYPE_INT_RGB), camera, null);
    Check.isTrue(System.currentTimeMillis() - start < 15000, "stop ends rendering");
    stopper.join();

    // The renderer still works after a stop
    System.setProperty("sh3d.gpurenderer.BlenderRenderer.lowQuality.samples", "16");
    BufferedImage imageAfterStop = new BufferedImage(160, 120, BufferedImage.TYPE_INT_RGB);
    renderer.render(imageAfterStop, camera, null);
    Check.isTrue(countDifferentPixels(image, imageAfterStop) < 160 * 120 / 20, "same image rendered after a stop");

    // Exposure is read at each rendering
    System.setProperty("sh3d.gpurenderer.BlenderRenderer.lowQuality.exposure", "2");
    BufferedImage brighterImage = new BufferedImage(160, 120, BufferedImage.TYPE_INT_RGB);
    renderer.render(brighterImage, camera, null);
    save(brighterImage, "room-center-brighter");
    Check.isTrue(getBrightness(brighterImage) > getBrightness(imageAfterStop) * 1.2, "exposure brightens the image");
    System.clearProperty("sh3d.gpurenderer.BlenderRenderer.lowQuality.exposure");

    renderer.dispose();
    Check.isTrue(!sessionFolder.exists(), "session folder deleted by dispose");
    System.out.println("BlenderRendererTest OK");
    System.exit(0);
  }
}
