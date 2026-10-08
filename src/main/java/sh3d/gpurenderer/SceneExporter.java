package sh3d.gpurenderer;

import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.imageio.ImageIO;
import javax.media.j3d.Appearance;
import javax.media.j3d.Group;
import javax.media.j3d.Link;
import javax.media.j3d.Node;
import javax.media.j3d.RenderingAttributes;
import javax.media.j3d.Shape3D;
import javax.media.j3d.Transform3D;
import javax.media.j3d.TransformGroup;
import javax.vecmath.Point3f;
import javax.vecmath.Vector3d;
import javax.vecmath.Vector3f;

import com.eteks.sweethome3d.j3d.Ground3D;
import com.eteks.sweethome3d.j3d.OBJWriter;
import com.eteks.sweethome3d.model.DimensionLine;
import com.eteks.sweethome3d.model.Elevatable;
import com.eteks.sweethome3d.model.Home;
import com.eteks.sweethome3d.model.HomeEnvironment;
import com.eteks.sweethome3d.model.HomeFurnitureGroup;
import com.eteks.sweethome3d.model.HomeLight;
import com.eteks.sweethome3d.model.HomePieceOfFurniture;
import com.eteks.sweethome3d.model.HomeTexture;
import com.eteks.sweethome3d.model.Level;
import com.eteks.sweethome3d.model.LightSource;
import com.eteks.sweethome3d.model.Room;
import com.eteks.sweethome3d.model.Selectable;
import com.eteks.sweethome3d.model.Wall;
import com.eteks.sweethome3d.viewcontroller.Object3DFactory;

/**
 * Writes a home as an OBJ file with its materials and textures, and a JSON file
 * describing its lights and environment, in Sweet Home 3D's frame (centimeters, Y up).
 */
final class SceneExporter {
  static final String SCENE_FILE = "scene.json";

  private static final String OBJ_FILE = "scene.obj";
  private static final String OCCLUDERS_OBJ_FILE = "occluders.obj";
  private static final String OCCLUDER_NAME_PREFIX = "occluder";
  private static final String SKY_TEXTURE_FILE = "sky.png";
  private static final String ITEM_NAME_PREFIX = "item";
  private static final float  GROUND_SIZE = 1E7f;
  // Default radius used by Sweet Home 3D for light sources without diameter
  private static final float  DEFAULT_LIGHT_SOURCE_RADIUS = 3.25f;
  private static final float  CEILING_LIGHT_RADIUS = 20;
  private static final float  CEILING_LIGHT_DISTANCE = 25;

  private SceneExporter() {
  }

  /**
   * Exports <code>home</code> in <code>folder</code> and returns the content
   * of the file {@link #SCENE_FILE} written in this folder.
   */
  static Map<String, Object> export(Home home, Object3DFactory object3dFactory, File folder) throws IOException {
    return export(home, object3dFactory, folder, null);
  }

  /**
   * Returns what occluders block for a value of the rendering parameter <code>hiddenItemsBlockLight</code>:
   * <code>"sun"</code>, <code>"all"</code> (also written <code>true</code>) or <code>null</code>
   * for <code>false</code>.
   * @throws IllegalArgumentException if <code>parameterValue</code> is none of them
   */
  static String getOccludersBlock(String parameterValue) {
    String value = parameterValue.trim().toLowerCase(Locale.ENGLISH);
    if (value.equals("false")) {
      return null;
    } else if (value.equals("all") || value.equals("true")) {
      return "all";
    } else if (value.equals("sun")) {
      return "sun";
    } else {
      throw new IllegalArgumentException("hiddenItemsBlockLight should be false, sun or all, not " + parameterValue);
    }
  }

  /**
   * Exports <code>home</code> as described above. If <code>occludersBlock</code> isn't <code>null</code>,
   * the ceilings and the levels hidden in <code>home</code> are written in a second OBJ file,
   * named by the <code>occluders</code> entry of the scene, for objects which aren't seen but block
   * the light of the sun (<code>"sun"</code>) or all light (<code>"all"</code>), as the
   * <code>occludersBlock</code> entry tells.
   */
  static Map<String, Object> export(Home home, Object3DFactory object3dFactory, File folder,
                                    String occludersBlock) throws IOException {
    HomeEnvironment environment = home.getEnvironment();
    List<Map<String, Object>> lights = new ArrayList<Map<String, Object>>();
    // Lamps with light source materials, with a flag for each of their exported shapes telling if it emits light
    Map<String, HomeLight> materialLamps = new LinkedHashMap<String, HomeLight>();
    Map<String, List<Boolean>> lightSourceShapes = new LinkedHashMap<String, List<Boolean>>();
    Set<String> wallAndRoomNames = new HashSet<String>();

    float subpartSize = environment.getSubpartSizeUnderLight();
    // Dividing walls and rooms surface in subparts is useless
    environment.setSubpartSizeUnderLight(0);
    File objFile = new File(folder, OBJ_FILE);
    OBJWriter writer = new OBJWriter(objFile, null, -1);
    try {
      int i = 0;
      for (Selectable item : getExportedItems(home)) {
        Node node = (Node)object3dFactory.createObject3D(home, item, true);
        if (node != null) {
          String itemName = ITEM_NAME_PREFIX + i++;
          writer.writeNode(node, itemName);
          if (item instanceof Wall || item instanceof Room) {
            wallAndRoomNames.add(itemName);
          }
          if (item instanceof HomeLight) {
            HomeLight lamp = (HomeLight)item;
            Level level = lamp.getLevel();
            if (lamp.getPower() > 0
                && (level == null || level.isViewableAndVisible())) {
              if (lamp.getLightSourceMaterialNames().length > 0) {
                List<Boolean> shapes = new ArrayList<Boolean>();
                listLightSourceShapes(node, lamp.getLightSourceMaterialNames(), shapes);
                materialLamps.put(itemName, lamp);
                lightSourceShapes.put(itemName, shapes);
              } else {
                addLightSources(lamp, lights);
              }
            }
          }
        }
      }
      // Create a 3D ground large enough to join the sky at the horizon, placed under floors
      Transform3D translation = new Transform3D();
      translation.setTranslation(new Vector3f(0, -0.1f, 0));
      TransformGroup ground = new TransformGroup(translation);
      ground.addChild(new Ground3D(home, -GROUND_SIZE / 2, -GROUND_SIZE / 2, GROUND_SIZE, GROUND_SIZE, true));
      writer.writeNode(ground, "ground");
    } finally {
      environment.setSubpartSizeUnderLight(subpartSize);
      writer.close();
    }
    addCeilingLights(home, lights);

    File occludersFile = new File(folder, OCCLUDERS_OBJ_FILE);
    Set<String> occluderWallAndRoomNames = new HashSet<String>();
    boolean occludersExported = occludersBlock != null
        && exportOccluders(home, object3dFactory, occludersFile, occluderWallAndRoomNames);
    if (!occludersExported) {
      // Don't leave the occluders of a previous export in the folder
      occludersFile.delete();
    }

    Map<String, Object> scene = new LinkedHashMap<String, Object>();
    scene.put("obj", OBJ_FILE);
    scene.put("lightColor", getColor(environment.getLightColor()));
    scene.put("skyColor", getColor(environment.getSkyColor()));
    scene.put("skyTexture", exportSkyTexture(environment.getSkyTexture(), folder));
    scene.put("groundColor", getColor(environment.getGroundColor()));
    scene.put("northDirection", home.getCompass().getNorthDirection());
    scene.put("lights", lights);
    Map<String, List<String>> itemsMaterials = readItemsMaterials(objFile);
    scene.put("emissiveMaterials", getEmissiveMaterials(itemsMaterials, materialLamps, lightSourceShapes));
    if (occludersExported) {
      scene.put("occluders", OCCLUDERS_OBJ_FILE);
      scene.put("occludersBlock", occludersBlock);
    }
    List<String> opaqueMaterials = new ArrayList<String>();
    if (environment.getWallsAlpha() > 0) {
      opaqueMaterials.addAll(getMaterials(itemsMaterials, wallAndRoomNames));
      if (occludersExported) {
        // Walls and rooms which block light are opaque too
        for (String material : getMaterials(readItemsMaterials(occludersFile), occluderWallAndRoomNames)) {
          if (!opaqueMaterials.contains(material)) {
            opaqueMaterials.add(material);
          }
        }
      }
    }
    scene.put("opaqueMaterials", opaqueMaterials);
    Files.write(new File(folder, SCENE_FILE).toPath(), (Json.write(scene) + "\n").getBytes(StandardCharsets.UTF_8));
    return scene;
  }

  /**
   * Writes in <code>occludersFile</code> what <code>home</code> hides to let a camera see a floor from above
   * but should still stop light: the hidden ceilings of rooms at visible levels, and the items of the levels
   * which aren't visible. Returns <code>false</code> if the home hides nothing able to stop light.
   */
  private static boolean exportOccluders(Home home, Object3DFactory object3dFactory, File occludersFile,
                                         Set<String> wallAndRoomNames) throws IOException {
    Set<String> hiddenCeilingRoomIds = new HashSet<String>();
    for (Room room : home.getRooms()) {
      Level level = room.getLevel();
      if (!room.isCeilingVisible()
          && (level == null || level.isViewableAndVisible())) {
        hiddenCeilingRoomIds.add(room.getId());
      }
    }
    Set<String> hiddenLevelIds = new HashSet<String>();
    for (Level level : home.getLevels()) {
      if (level.isViewable() && !level.isVisible()) {
        hiddenLevelIds.add(level.getId());
      }
    }
    if (hiddenCeilingRoomIds.isEmpty() && hiddenLevelIds.isEmpty()) {
      return false;
    }

    // Build occluders from a copy of the home where they're visible, to leave the home unchanged and
    // because the shape of a wall or a room depends on the other items shown in its home
    Home shownHome = home.clone();
    shownHome.getEnvironment().setSubpartSizeUnderLight(0);
    for (Level level : shownHome.getLevels()) {
      if (level.isViewable()) {
        level.setVisible(true);
      }
    }
    List<Selectable> occluders = new ArrayList<Selectable>();
    for (Room room : shownHome.getRooms()) {
      if (hiddenCeilingRoomIds.contains(room.getId())) {
        // Keep the ceiling only, the floor being written with visible items
        room.setCeilingVisible(true);
        room.setFloorVisible(false);
        occluders.add(room);
      }
    }
    for (Selectable item : getExportedItems(shownHome)) {
      if (item instanceof Elevatable
          && ((Elevatable)item).getLevel() != null
          && hiddenLevelIds.contains(((Elevatable)item).getLevel().getId())) {
        occluders.add(item);
      }
    }

    OBJWriter writer = new OBJWriter(occludersFile, null, -1);
    try {
      int i = 0;
      for (Selectable item : occluders) {
        Node node = (Node)object3dFactory.createObject3D(shownHome, item, true);
        if (node != null) {
          String itemName = OCCLUDER_NAME_PREFIX + i++;
          writer.writeNode(node, itemName);
          if (item instanceof Wall || item instanceof Room) {
            wallAndRoomNames.add(itemName);
          }
        }
      }
    } finally {
      writer.close();
    }
    // A hidden level may be empty or show nothing in 3D, and Blender can't read a file without object
    return hasFaces(occludersFile);
  }

  /**
   * Returns <code>true</code> if <code>objFile</code> has at least a face.
   */
  private static boolean hasFaces(File objFile) throws IOException {
    BufferedReader reader = Files.newBufferedReader(objFile.toPath(), StandardCharsets.ISO_8859_1);
    try {
      for (String line; (line = reader.readLine()) != null; ) {
        if (line.startsWith("f ")) {
          return true;
        }
      }
      return false;
    } finally {
      reader.close();
    }
  }

  /**
   * Returns the viewable items of <code>home</code> with furniture groups replaced by their furniture.
   */
  private static List<Selectable> getExportedItems(Home home) {
    List<Selectable> items = new ArrayList<Selectable>();
    for (Selectable item : home.getSelectableViewableItems()) {
      if (item instanceof HomeFurnitureGroup) {
        for (HomePieceOfFurniture piece : ((HomeFurnitureGroup)item).getAllFurniture()) {
          if (!(piece instanceof HomeFurnitureGroup)) {
            items.add(piece);
          }
        }
      } else if (!(item instanceof DimensionLine)) {
        items.add(item);
      }
    }
    return items;
  }

  static float [] getColor(int rgb) {
    return new float [] {((rgb >> 16) & 0xFF) / 255f, ((rgb >> 8) & 0xFF) / 255f, (rgb & 0xFF) / 255f};
  }

  /**
   * Adds to <code>lights</code> the light sources of <code>lamp</code> at their location in the home.
   */
  private static void addLightSources(HomeLight lamp, List<Map<String, Object>> lights) {
    Transform3D lampTransform = getNormalizedModelTransformation(lamp);
    for (LightSource lightSource : lamp.getLightSources()) {
      Point3f location = new Point3f(lightSource.getX() - 0.5f, lightSource.getZ() - 0.5f, 0.5f - lightSource.getY());
      lampTransform.transform(location);
      float radius = lightSource.getDiameter() != null
          ? lightSource.getDiameter() * lamp.getWidth() / 2
          : DEFAULT_LIGHT_SOURCE_RADIUS;
      lights.add(createLight(location.x, location.y, location.z, lightSource.getColor(), radius, lamp.getPower()));
    }
  }

  private static Map<String, Object> createLight(float x, float y, float z, int color, float radius, float power) {
    Map<String, Object> light = new LinkedHashMap<String, Object>();
    light.put("position", new float [] {x, y, z});
    light.put("color", getColor(color));
    light.put("radius", radius);
    light.put("power", power);
    return light;
  }

  /**
   * Returns the transformation placing in the home the model of <code>piece</code> normalized in a unit cube.
   * Contrary to Sweet Home 3D, the center of a piece rotated around an horizontal axis
   * isn't recomputed from its rotated bounds.
   */
  private static Transform3D getNormalizedModelTransformation(HomePieceOfFurniture piece) {
    Transform3D modelTransform = new Transform3D();
    if (piece.isHorizontallyRotated()) {
      if (piece.getPitch() != 0) {
        modelTransform.rotX(-piece.getPitch());
      }
      if (piece.getRoll() != 0) {
        Transform3D rollRotation = new Transform3D();
        rollRotation.rotZ(-piece.getRoll());
        modelTransform.mul(rollRotation, modelTransform);
      }
    }
    Transform3D scale = new Transform3D();
    scale.setScale(new Vector3d(piece.isModelMirrored() ? -piece.getWidth() : piece.getWidth(),
        piece.getHeight(), piece.getDepth()));
    modelTransform.mul(scale);

    Transform3D verticalRotation = new Transform3D();
    verticalRotation.rotY(-piece.getAngle());
    verticalRotation.mul(modelTransform);

    Transform3D pieceTransform = new Transform3D();
    float levelElevation = piece.getLevel() != null ? piece.getLevel().getElevation() : 0;
    pieceTransform.setTranslation(new Vector3f(piece.getX(),
        piece.getElevation() + piece.getHeight() / 2 + levelElevation, piece.getY()));
    pieceTransform.mul(verticalRotation);
    return pieceTransform;
  }

  /**
   * Adds a light under the ceiling of each room which has one, as Sweet Home 3D renderers do.
   */
  private static void addCeilingLights(Home home, List<Map<String, Object>> lights) {
    int ceilingLightColor = home.getEnvironment().getCeillingLightColor();
    if (ceilingLightColor > 0) {
      for (Room room : home.getRooms()) {
        Level level = room.getLevel();
        if (room.isCeilingVisible()
            && (level == null || level.isViewableAndVisible())) {
          float ceilingElevation = level != null
              ? level.getElevation() + level.getHeight()
              : home.getWallHeight();
          // Power of a lamp emitting as much light as the ceiling light of Sweet Home 3D for the room area
          float power = (float)Math.sqrt(Math.sqrt(room.getArea()) / 2000);
          lights.add(createLight(room.getXCenter(), ceilingElevation - CEILING_LIGHT_DISTANCE, room.getYCenter(),
              ceilingLightColor, CEILING_LIGHT_RADIUS, power));
        }
      }
    }
  }

  /**
   * Adds to <code>shapes</code> a flag for each shape of <code>node</code> written by <code>OBJWriter</code>,
   * in the same order, equal to <code>true</code> for shapes using a light source material.
   */
  private static void listLightSourceShapes(Node node, String [] lightSourceMaterialNames, List<Boolean> shapes) {
    if (node instanceof Group) {
      Enumeration<?> enumeration = ((Group)node).getAllChildren();
      while (enumeration.hasMoreElements()) {
        listLightSourceShapes((Node)enumeration.nextElement(), lightSourceMaterialNames, shapes);
      }
    } else if (node instanceof Link) {
      listLightSourceShapes(((Link)node).getSharedGroup(), lightSourceMaterialNames, shapes);
    } else if (node instanceof Shape3D) {
      Shape3D shape = (Shape3D)node;
      Appearance appearance = shape.getAppearance();
      RenderingAttributes renderingAttributes = appearance != null ? appearance.getRenderingAttributes() : null;
      if (shape.numGeometries() >= 1
          && (renderingAttributes == null || renderingAttributes.getVisible())) {
        boolean lightSource = false;
        if (appearance != null) {
          for (String name : lightSourceMaterialNames) {
            if (name.equals(appearance.getName())) {
              lightSource = true;
            }
          }
        }
        shapes.add(lightSource);
      }
    }
  }

  /**
   * Returns the material names used by the shapes of each item written in <code>objFile</code>,
   * in the order of their shapes. The name is <code>null</code> for a shape without material.
   */
  private static Map<String, List<String>> readItemsMaterials(File objFile) throws IOException {
    Map<String, List<String>> itemsMaterials = new LinkedHashMap<String, List<String>>();
    List<String> itemMaterials = null;
    BufferedReader reader = Files.newBufferedReader(objFile.toPath(), StandardCharsets.ISO_8859_1);
    try {
      for (String line; (line = reader.readLine()) != null; ) {
        if (line.startsWith("g ")) {
          // Groups are named with their item name followed by an underscore
          String groupName = line.substring(2);
          int separator = groupName.indexOf('_');
          String itemName = separator > 0 ? groupName.substring(0, separator) : groupName;
          itemMaterials = itemsMaterials.get(itemName);
          if (itemMaterials == null) {
            itemMaterials = new ArrayList<String>();
            itemsMaterials.put(itemName, itemMaterials);
          }
          itemMaterials.add(null);
        } else if (line.startsWith("usemtl ") && itemMaterials != null) {
          itemMaterials.set(itemMaterials.size() - 1, line.substring("usemtl ".length()).trim());
        }
      }
    } finally {
      reader.close();
    }
    return itemsMaterials;
  }

  /**
   * Returns the names of the materials of the light source shapes of the lamps
   * in <code>materialLamps</code>, with the power of their lamp.
   */
  private static List<Map<String, Object>> getEmissiveMaterials(Map<String, List<String>> itemsMaterials,
                                                                Map<String, HomeLight> materialLamps,
                                                                Map<String, List<Boolean>> lightSourceShapes) {
    Map<String, Float> materialsPower = new LinkedHashMap<String, Float>();
    for (Map.Entry<String, HomeLight> lamp : materialLamps.entrySet()) {
      List<String> materials = itemsMaterials.get(lamp.getKey());
      List<Boolean> shapes = lightSourceShapes.get(lamp.getKey());
      // Ignore lamps which shapes weren't written as expected
      if (materials != null && materials.size() == shapes.size()) {
        for (int i = 0; i < shapes.size(); i++) {
          if (shapes.get(i) && materials.get(i) != null) {
            materialsPower.merge(materials.get(i), lamp.getValue().getPower(), Math::max);
          }
        }
      }
    }
    List<Map<String, Object>> emissiveMaterials = new ArrayList<Map<String, Object>>();
    for (Map.Entry<String, Float> material : materialsPower.entrySet()) {
      Map<String, Object> emissiveMaterial = new LinkedHashMap<String, Object>();
      emissiveMaterial.put("name", material.getKey());
      emissiveMaterial.put("power", material.getValue());
      emissiveMaterials.add(emissiveMaterial);
    }
    return emissiveMaterials;
  }

  /**
   * Returns the names of the materials used by the items named <code>itemNames</code>.
   */
  private static List<String> getMaterials(Map<String, List<String>> itemsMaterials, Set<String> itemNames) {
    Set<String> materials = new LinkedHashSet<String>();
    for (Map.Entry<String, List<String>> itemMaterials : itemsMaterials.entrySet()) {
      if (itemNames.contains(itemMaterials.getKey())) {
        for (String material : itemMaterials.getValue()) {
          if (material != null) {
            materials.add(material);
          }
        }
      }
    }
    return new ArrayList<String>(materials);
  }

  /**
   * Writes the sky texture as an image covering the whole sphere around the home
   * and returns its file name, or <code>null</code> if there's no sky texture.
   */
  private static String exportSkyTexture(HomeTexture skyTexture, File folder) throws IOException {
    if (skyTexture == null) {
      return null;
    }
    InputStream in = skyTexture.getImage().openStream();
    BufferedImage skyImage;
    try {
      skyImage = ImageIO.read(in);
    } finally {
      in.close();
    }
    if (skyImage == null) {
      return null;
    }
    // The sky image covers the top half of the sphere, its mirror the bottom half to avoid a line at the horizon
    BufferedImage sphereImage = new BufferedImage(skyImage.getWidth(), skyImage.getHeight() * 2, BufferedImage.TYPE_INT_RGB);
    Graphics2D g2D = (Graphics2D)sphereImage.getGraphics();
    float xOffset = skyImage.getWidth() * skyTexture.getXOffset();
    AffineTransform mirrorTransform = AffineTransform.getScaleInstance(1, -1);
    mirrorTransform.translate(xOffset, -2 * skyImage.getHeight());
    g2D.drawRenderedImage(skyImage, mirrorTransform);
    mirrorTransform.translate(-skyImage.getWidth(), 0);
    g2D.drawRenderedImage(skyImage, mirrorTransform);
    g2D.drawRenderedImage(skyImage, AffineTransform.getTranslateInstance(xOffset, 0));
    g2D.drawRenderedImage(skyImage, AffineTransform.getTranslateInstance(xOffset - skyImage.getWidth(), 0));
    g2D.dispose();
    ImageIO.write(sphereImage, "png", new File(folder, SKY_TEXTURE_FILE));
    return SKY_TEXTURE_FILE;
  }
}
