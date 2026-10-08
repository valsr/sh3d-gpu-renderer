package sh3d.gpurenderer;

import java.util.Enumeration;

import javax.media.j3d.Appearance;
import javax.media.j3d.Group;
import javax.media.j3d.Link;
import javax.media.j3d.Node;
import javax.media.j3d.Shape3D;

import com.eteks.sweethome3d.io.DefaultFurnitureCatalog;
import com.eteks.sweethome3d.j3d.Object3DBranchFactory;
import com.eteks.sweethome3d.model.CatalogLight;
import com.eteks.sweethome3d.model.CatalogPieceOfFurniture;
import com.eteks.sweethome3d.model.FurnitureCategory;
import com.eteks.sweethome3d.model.Home;
import com.eteks.sweethome3d.model.HomeLight;
import com.eteks.sweethome3d.model.HomePieceOfFurniture;
import com.eteks.sweethome3d.model.Level;
import com.eteks.sweethome3d.model.Room;
import com.eteks.sweethome3d.model.Wall;

/**
 * Homes built in code for tests.
 */
final class TestHomes {
  private TestHomes() {
  }

  /**
   * Returns a lamp of the default catalog. If <code>withLightSourceMaterials</code> is <code>true</code>,
   * the material of the first visible shape of its model is declared as its light source material.
   */
  static HomeLight createLamp(boolean withLightSourceMaterials) {
    for (FurnitureCategory category : new DefaultFurnitureCatalog().getCategories()) {
      for (CatalogPieceOfFurniture piece : category.getFurniture()) {
        if (piece instanceof CatalogLight
            && ((CatalogLight)piece).getLightSources().length > 0
            && ((CatalogLight)piece).getLightSourceMaterialNames().length == 0) {
          HomeLight lamp = new HomeLight((CatalogLight)piece);
          if (withLightSourceMaterials) {
            String materialName = getFirstAppearanceName(
                (Node)new Object3DBranchFactory().createObject3D(new Home(), lamp, true));
            if (materialName == null) {
              continue;
            }
            lamp.setLightSourceMaterialNames(new String [] {materialName});
          }
          return lamp;
        }
      }
    }
    return null;
  }

  private static String getFirstAppearanceName(Node node) {
    if (node instanceof Group) {
      Enumeration<?> enumeration = ((Group)node).getAllChildren();
      while (enumeration.hasMoreElements()) {
        String name = getFirstAppearanceName((Node)enumeration.nextElement());
        if (name != null) {
          return name;
        }
      }
    } else if (node instanceof Link) {
      return getFirstAppearanceName(((Link)node).getSharedGroup());
    } else if (node instanceof Shape3D) {
      // Ignore the invisible shapes of the lamps which are only light sources
      Appearance appearance = ((Shape3D)node).getAppearance();
      if (appearance != null
          && (appearance.getRenderingAttributes() == null || appearance.getRenderingAttributes().getVisible())) {
        return appearance.getName();
      }
    }
    return null;
  }

  static HomePieceOfFurniture createPiece(String name) {
    for (FurnitureCategory category : new DefaultFurnitureCatalog().getCategories()) {
      for (CatalogPieceOfFurniture piece : category.getFurniture()) {
        if (!(piece instanceof CatalogLight) && !piece.isDoorOrWindow()
            && (name == null || name.equals(piece.getName()))) {
          return new HomePieceOfFurniture(piece);
        }
      }
    }
    return null;
  }

  /**
   * Returns a home with a 5 m x 4 m room closed by a ceiling, a piece of furniture
   * and <code>lamp</code> if it's not <code>null</code>.
   */
  static Home createRoomHome(HomeLight lamp) {
    Home home = new Home();
    float [][] corners = {{0, 0}, {500, 0}, {500, 400}, {0, 400}};
    for (int i = 0; i < corners.length; i++) {
      float [] start = corners [i];
      float [] end = corners [(i + 1) % corners.length];
      home.addWall(new Wall(start [0], start [1], end [0], end [1], 10, home.getWallHeight()));
    }
    Room room = new Room(corners);
    room.setFloorVisible(true);
    room.setCeilingVisible(true);
    home.addRoom(room);
    HomePieceOfFurniture piece = createPiece(null);
    piece.setX(350);
    piece.setY(250);
    home.addPieceOfFurniture(piece);
    if (lamp != null) {
      lamp.setX(200);
      lamp.setY(300);
      lamp.setPower(0.5f);
      home.addPieceOfFurniture(lamp);
    }
    return home;
  }

  /**
   * Returns a home with the levels "Ground" and "Upper", each with a 5 m x 4 m room closed by walls.
   * The ceiling of the ground room and the upper level are hidden, as when a floor is viewed from above.
   */
  static Home createTwoLevelHome() {
    Home home = new Home();
    Level ground = new Level("Ground", 0, 12, 250);
    Level upper = new Level("Upper", 262, 12, 250);
    home.addLevel(ground);
    home.addLevel(upper);
    for (Level level : new Level [] {ground, upper}) {
      home.setSelectedLevel(level);
      float [][] corners = {{0, 0}, {500, 0}, {500, 400}, {0, 400}};
      for (int i = 0; i < corners.length; i++) {
        float [] start = corners [i];
        float [] end = corners [(i + 1) % corners.length];
        home.addWall(new Wall(start [0], start [1], end [0], end [1], 10, 250));
      }
      Room room = new Room(corners);
      room.setFloorVisible(true);
      room.setCeilingVisible(level == upper);
      home.addRoom(room);
    }
    upper.setVisible(false);
    home.setSelectedLevel(ground);
    return home;
  }
}
