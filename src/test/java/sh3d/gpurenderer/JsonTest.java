package sh3d.gpurenderer;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

public class JsonTest {
  public static void main(String [] args) {
    Map<String, Object> object = new LinkedHashMap<String, Object>();
    object.put("cmd", "render");
    object.put("width", 640);
    object.put("fov", 1.5f);
    object.put("sky", null);
    object.put("on", true);
    object.put("position", new float [] {1, 2.5f, -3});
    object.put("items", Arrays.asList("a", Map.of("k", 1)));
    Check.equal("{\"cmd\":\"render\",\"width\":640,\"fov\":1.5,\"sky\":null,\"on\":true,"
        + "\"position\":[1.0,2.5,-3.0],\"items\":[\"a\",{\"k\":1}]}",
        Json.write(object), "nested object");

    Check.equal("\"a\\\"b\\\\c\\nd\\u0001\\u00e9\"", Json.write("a\"b\\c\nd\u0001é"),
        "string escaped to one ASCII line");

    Check.thrown(IllegalArgumentException.class, () -> Json.write(Float.NaN), "NaN refused");
    System.out.println("JsonTest OK");
  }
}
