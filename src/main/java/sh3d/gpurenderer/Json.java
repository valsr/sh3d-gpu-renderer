package sh3d.gpurenderer;

import java.util.Map;

/**
 * Writes maps, iterables, float arrays, strings, numbers, booleans and <code>null</code>
 * as JSON on a single ASCII line.
 */
final class Json {
  private Json() {
  }

  static String write(Object value) {
    StringBuilder json = new StringBuilder();
    write(value, json);
    return json.toString();
  }

  private static void write(Object value, StringBuilder json) {
    if (value == null || value instanceof Boolean) {
      json.append(value);
    } else if (value instanceof Number) {
      double number = ((Number)value).doubleValue();
      if (Double.isNaN(number) || Double.isInfinite(number)) {
        throw new IllegalArgumentException("Not a JSON number: " + value);
      }
      json.append(value);
    } else if (value instanceof CharSequence) {
      writeString(value.toString(), json);
    } else if (value instanceof float []) {
      json.append('[');
      float [] numbers = (float [])value;
      for (int i = 0; i < numbers.length; i++) {
        if (i > 0) {
          json.append(',');
        }
        write(numbers [i], json);
      }
      json.append(']');
    } else if (value instanceof Map) {
      json.append('{');
      boolean first = true;
      for (Map.Entry<?, ?> entry : ((Map<?, ?>)value).entrySet()) {
        if (!first) {
          json.append(',');
        }
        first = false;
        writeString(String.valueOf(entry.getKey()), json);
        json.append(':');
        write(entry.getValue(), json);
      }
      json.append('}');
    } else if (value instanceof Iterable) {
      json.append('[');
      boolean first = true;
      for (Object item : (Iterable<?>)value) {
        if (!first) {
          json.append(',');
        }
        first = false;
        write(item, json);
      }
      json.append(']');
    } else {
      throw new IllegalArgumentException("Not a JSON value: " + value.getClass());
    }
  }

  private static void writeString(String string, StringBuilder json) {
    json.append('"');
    for (int i = 0; i < string.length(); i++) {
      char c = string.charAt(i);
      if (c == '"' || c == '\\') {
        json.append('\\').append(c);
      } else if (c == '\n') {
        json.append("\\n");
      } else if (c < 0x20 || c > 0x7E) {
        json.append(String.format("\\u%04x", (int)c));
      } else {
        json.append(c);
      }
    }
    json.append('"');
  }
}
