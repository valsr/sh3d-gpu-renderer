package sh3d.gpurenderer;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import com.eteks.sweethome3d.io.DefaultTexturesCatalog;
import com.eteks.sweethome3d.j3d.Object3DBranchFactory;
import com.eteks.sweethome3d.model.CatalogTexture;
import com.eteks.sweethome3d.model.HomeTexture;
import com.eteks.sweethome3d.model.Home;
import com.eteks.sweethome3d.model.Room;
import com.eteks.sweethome3d.model.Wall;
import com.eteks.sweethome3d.model.Level;
import com.eteks.sweethome3d.model.HomeLight;

public class SceneExporterTest {
  @SuppressWarnings("unchecked")
  public static void main(String [] args) throws Exception {
    HomeLight lamp = TestHomes.createLamp(false);
    Check.isTrue(lamp != null, "default catalog has a lamp without light source materials");
    Home home = TestHomes.createRoomHome(lamp);
    home.getEnvironment().setLightColor(0xFF8040);
    home.getEnvironment().setCeillingLightColor(0);
    File folder = Files.createTempDirectory("sh3d-exporter-test").toFile();

    Map<String, Object> scene = SceneExporter.export(home, new Object3DBranchFactory(), folder);

    String obj = new String(Files.readAllBytes(new File(folder, "scene.obj").toPath()), StandardCharsets.ISO_8859_1);
    Check.isTrue(obj.contains("\nv "), "OBJ file has vertices");
    Check.isTrue(obj.contains("mtllib scene.mtl"), "OBJ file references its MTL file");
    Check.isTrue(new File(folder, "scene.mtl").length() > 0, "MTL file written");
    Check.equal(Json.write(scene),
        new String(Files.readAllBytes(new File(folder, "scene.json").toPath()), StandardCharsets.UTF_8).trim(),
        "scene.json content");
    Check.equal("scene.obj", scene.get("obj"), "OBJ file name");
    Check.equal("[1.0, 0.5019608, 0.2509804]", java.util.Arrays.toString((float [])scene.get("lightColor")), "light color");
    Check.equal(null, scene.get("skyTexture"), "no sky texture");

    List<Map<String, Object>> lights = (List<Map<String, Object>>)scene.get("lights");
    Check.equal(lamp.getLightSources().length, lights.size(), "one light per light source of the lamp");
    for (Map<String, Object> light : lights) {
      float [] position = (float [])light.get("position");
      Check.isTrue(Math.abs(position [0] - lamp.getX()) <= lamp.getWidth() / 2 + 1
          && Math.abs(position [2] - lamp.getY()) <= lamp.getDepth() / 2 + 1
          && position [1] >= lamp.getElevation() - 1
          && position [1] <= lamp.getElevation() + lamp.getHeight() + 1,
          "light source is in the box of its lamp: " + java.util.Arrays.toString(position));
      Check.equal(0.5f, light.get("power"), "lamp power");
      Check.isTrue((Float)light.get("radius") > 0, "light radius");
    }
    Check.equal(0, ((List<?>)scene.get("emissiveMaterials")).size(), "no light source material");

    // A lamp turned off doesn't light, a ceiling light is added in rooms with a ceiling
    lamp.setPower(0);
    home.getEnvironment().setCeillingLightColor(0xD0D0D0);
    scene = SceneExporter.export(home, new Object3DBranchFactory(), folder);
    lights = (List<Map<String, Object>>)scene.get("lights");
    Check.equal(1, lights.size(), "one ceiling light");
    float [] position = (float [])lights.get(0).get("position");
    Check.equal("[250.0, " + (home.getWallHeight() - 25) + ", 200.0]", java.util.Arrays.toString(position), "ceiling light location");

    // Walls and rooms made transparent in the 3D view are rendered opaque, as Sweet Home 3D renderers do
    Check.equal(0, ((List<?>)scene.get("opaqueMaterials")).size(), "no material to make opaque with opaque walls");
    home.getEnvironment().setWallsAlpha(0.5f);
    scene = SceneExporter.export(home, new Object3DBranchFactory(), folder);
    List<String> opaqueMaterials = (List<String>)scene.get("opaqueMaterials");
    Check.isTrue(opaqueMaterials.size() > 0, "materials of transparent walls listed");
    String wallsMtl = new String(Files.readAllBytes(new File(folder, "scene.mtl").toPath()), StandardCharsets.ISO_8859_1);
    int transparentMaterialCount = 0;
    for (String material : opaqueMaterials) {
      int definition = wallsMtl.indexOf("newmtl " + material + "\n");
      Check.isTrue(definition >= 0, "opaque material " + material + " is in MTL file");
      int end = wallsMtl.indexOf("newmtl ", definition + 1);
      if (wallsMtl.substring(definition, end < 0 ? wallsMtl.length() : end).contains("\nd 0.5")) {
        transparentMaterialCount++;
      }
    }
    Check.isTrue(transparentMaterialCount > 0, "transparent material of walls listed");
    Check.equal(transparentMaterialCount, wallsMtl.split("\nd 0.5", -1).length - 1, "all transparent materials listed");
    home.getEnvironment().setWallsAlpha(0);

    // Textures are written beside the MTL file which references them
    CatalogTexture texture = new DefaultTexturesCatalog().getCategories().get(0).getTextures().get(0);
    home.getRooms().get(0).setFloorTexture(new HomeTexture(texture));
    SceneExporter.export(home, new Object3DBranchFactory(), folder);
    String texturedMtl = new String(Files.readAllBytes(new File(folder, "scene.mtl").toPath()), StandardCharsets.ISO_8859_1);
    int textureLine = texturedMtl.indexOf("map_Kd ");
    Check.isTrue(textureLine >= 0, "MTL file references a texture image");
    String textureFile = texturedMtl.substring(textureLine + "map_Kd ".length(), texturedMtl.indexOf('\n', textureLine)).trim();
    Check.isTrue(new File(folder, textureFile).length() > 0, "texture image " + textureFile + " written in export folder");

    // Lamps with light source materials emit light from these materials instead of point lights
    HomeLight materialLamp = TestHomes.createLamp(true);
    Check.isTrue(materialLamp != null, "default catalog has a lamp with a named material");
    {
      Home materialHome = TestHomes.createRoomHome(materialLamp);
      materialHome.getEnvironment().setCeillingLightColor(0);
      scene = SceneExporter.export(materialHome, new Object3DBranchFactory(), folder);
      Check.equal(0, ((List<?>)scene.get("lights")).size(), "no point light for a lamp with light source materials");
      List<Map<String, Object>> emissive = (List<Map<String, Object>>)scene.get("emissiveMaterials");
      Check.isTrue(emissive.size() > 0, "light source materials listed");
      String mtl = new String(Files.readAllBytes(new File(folder, "scene.mtl").toPath()), StandardCharsets.ISO_8859_1);
      for (Map<String, Object> material : emissive) {
        Check.isTrue(mtl.contains("newmtl " + material.get("name") + "\n"), "emissive material " + material.get("name") + " is in MTL file");
        Check.equal(0.5f, material.get("power"), "emissive material power");
      }
    }
    // Hidden ceilings and levels are written apart to block light when asked, and only then
    {
      Object3DBranchFactory factory = new Object3DBranchFactory();
      Home twoLevelHome = TestHomes.createTwoLevelHome();
      Level upper = twoLevelHome.getLevels().get(1);
      Room groundRoom = twoLevelHome.getRooms().get(0);
      File occludersFile = new File(folder, "occluders.obj");

      scene = SceneExporter.export(twoLevelHome, factory, folder);
      Check.isTrue(!scene.containsKey("occluders") && !scene.containsKey("occludersBlock"), "no occluders unless asked");
      Check.isTrue(!occludersFile.exists(), "no occluders file unless asked");
      byte [] sceneObj = Files.readAllBytes(new File(folder, "scene.obj").toPath());
      String sceneLights = Json.write(scene.get("lights"));

      scene = SceneExporter.export(twoLevelHome, factory, folder, "all");
      Check.equal("occluders.obj", scene.get("occluders"), "occluders file name");
      Check.equal("all", scene.get("occludersBlock"), "occluders block all light");
      Check.equal(6, countOccluders(occludersFile), "ground ceiling, 4 upper walls and upper room block light");
      Check.equal("sun", SceneExporter.export(twoLevelHome, factory, folder, "sun").get("occludersBlock"),
          "occluders block the sun only");
      Check.equal(6, countOccluders(occludersFile), "same occluders whatever they block");

      // Values of the rendering parameter
      Check.equal(null, SceneExporter.getOccludersBlock("false"), "parameter false");
      Check.equal("all", SceneExporter.getOccludersBlock("all"), "parameter all");
      Check.equal("all", SceneExporter.getOccludersBlock("true"), "parameter true");
      Check.equal("sun", SceneExporter.getOccludersBlock(" Sun "), "parameter sun");
      Check.thrown(IllegalArgumentException.class, () -> SceneExporter.getOccludersBlock("moon"), "unknown parameter value");
      Check.isTrue(java.util.Arrays.equals(sceneObj, Files.readAllBytes(new File(folder, "scene.obj").toPath())),
          "visible items are written the same with occluders");
      Check.equal(sceneLights, Json.write(scene.get("lights")), "occluders add no light");
      Check.isTrue(!upper.isVisible() && !groundRoom.isCeilingVisible() && groundRoom.isFloorVisible(),
          "home unchanged by the export of occluders");

      SceneExporter.export(twoLevelHome, factory, folder, null);
      Check.isTrue(!occludersFile.exists(), "occluders file of a previous export removed");

      // A visible ceiling is written once, with the visible items
      groundRoom.setCeilingVisible(true);
      SceneExporter.export(twoLevelHome, factory, folder, "all");
      Check.equal(5, countOccluders(occludersFile), "visible ceiling isn't an occluder");
      groundRoom.setCeilingVisible(false);

      // A level which isn't viewable stays out of the scene
      upper.setViewable(false);
      SceneExporter.export(twoLevelHome, factory, folder, "all");
      Check.equal(1, countOccluders(occludersFile), "only the ground ceiling when upper level isn't viewable");

      // A hidden level without anything to stop light gives no occluders file, which Blender couldn't read
      Home emptyLevelHome = TestHomes.createTwoLevelHome();
      emptyLevelHome.getRooms().get(0).setCeilingVisible(true);
      for (Wall wall : new java.util.ArrayList<Wall>(emptyLevelHome.getWalls())) {
        if (wall.getLevel() == emptyLevelHome.getLevels().get(1)) {
          emptyLevelHome.deleteWall(wall);
        }
      }
      emptyLevelHome.deleteRoom(emptyLevelHome.getRooms().get(1));
      scene = SceneExporter.export(emptyLevelHome, factory, folder, "all");
      Check.isTrue(!scene.containsKey("occluders") && !occludersFile.exists(), "empty hidden level, no occluders");

      Home roomHome = TestHomes.createRoomHome(null);
      scene = SceneExporter.export(roomHome, factory, folder, "all");
      Check.isTrue(!scene.containsKey("occluders") && !occludersFile.exists(), "nothing hidden, no occluders");
      roomHome.getRooms().get(0).setCeilingVisible(false);
      SceneExporter.export(roomHome, factory, folder, "all");
      Check.equal(1, countOccluders(occludersFile), "hidden ceiling of a home without levels");
    }
    System.out.println("SceneExporterTest OK");
    System.exit(0);
  }

  /**
   * Returns the count of items written in the OBJ file of occluders.
   */
  private static int countOccluders(File occludersFile) throws Exception {
    java.util.Set<String> names = new java.util.HashSet<String>();
    for (String line : Files.readAllLines(occludersFile.toPath(), StandardCharsets.ISO_8859_1)) {
      if (line.startsWith("g occluder")) {
        int separator = line.indexOf('_');
        names.add(separator > 0 ? line.substring(2, separator) : line.substring(2));
      }
    }
    return names.size();
  }
}
