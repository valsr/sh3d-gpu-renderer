package sh3d.gpurenderer;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.InputStream;

import javax.imageio.ImageIO;
import javax.xml.parsers.SAXParserFactory;

import com.eteks.sweethome3d.io.DefaultUserPreferences;
import com.eteks.sweethome3d.io.HomeFileRecorder;
import com.eteks.sweethome3d.io.HomeXMLHandler;
import com.eteks.sweethome3d.j3d.AbstractPhotoRenderer;
import com.eteks.sweethome3d.j3d.Object3DBranchFactory;
import com.eteks.sweethome3d.model.Camera;
import com.eteks.sweethome3d.model.Home;

/**
 * Renders a home from the command line to compare renderers and tune their look:
 * RenderHomeTool home output.png [width height LOW|HIGH rendererClass storedCameraIndex]
 * where home is a .sh3d file or the name of an example of Sweet Home 3D like Studio.
 */
public class RenderHomeTool {
  public static void main(String [] args) throws Exception {
    String homeName = args [0];
    File output = new File(args [1]);
    int width = args.length > 2 ? Integer.parseInt(args [2]) : 1280;
    int height = args.length > 3 ? Integer.parseInt(args [3]) : 720;
    AbstractPhotoRenderer.Quality quality = AbstractPhotoRenderer.Quality.valueOf(args.length > 4 ? args [4] : "HIGH");
    String rendererClass = args.length > 5 ? args [5] : BlenderRenderer.class.getName();

    Home home;
    if (new File(homeName).exists()) {
      home = new HomeFileRecorder().readHome(homeName);
    } else {
      HomeXMLHandler handler = new HomeXMLHandler(new DefaultUserPreferences());
      InputStream in = HomeXMLHandler.class.getResourceAsStream("resources/examples/" + homeName + ".xml");
      SAXParserFactory.newInstance().newSAXParser().parse(in, handler);
      home = handler.getHome();
    }
    Camera camera = args.length > 6
        ? home.getStoredCameras().get(Integer.parseInt(args [6]))
        : home.getCamera();
    System.out.println("Camera " + camera.getClass().getSimpleName() + " at " + camera.getX() + ", " + camera.getY() + ", " + camera.getZ()
        + " yaw " + camera.getYaw() + " pitch " + camera.getPitch() + " lens " + camera.getLens()
        + ", " + home.getStoredCameras().size() + " stored cameras");

    AbstractPhotoRenderer renderer = AbstractPhotoRenderer.createInstance(rendererClass, home, new Object3DBranchFactory(), quality);
    BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    for (int i = 0; i < 2; i++) {
      long start = System.currentTimeMillis();
      renderer.render(image, camera, null);
      System.out.println(renderer.getName() + " " + quality + " " + width + "x" + height
          + (i == 0 ? " first image: " : " next image: ") + (System.currentTimeMillis() - start) + " ms");
      if (!(renderer instanceof BlenderRenderer)) {
        break;
      }
    }
    if (renderer instanceof BlenderRenderer && Boolean.getBoolean("keepSession")) {
      // Keep exported files to run worker.py on them by hand
      System.out.println("Session folder " + ((BlenderRenderer)renderer).getSessionFolder());
      renderer.stop();
    } else {
      renderer.dispose();
    }
    ImageIO.write(image, "png", output);
    System.exit(0);
  }
}
