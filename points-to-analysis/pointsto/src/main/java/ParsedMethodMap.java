import java.util.HashMap;

import soot.SootMethod;

public class ParsedMethodMap extends HashMap<SootMethod, ShimpleMethod> {
  private ParsedMethodMap() {
    super();
  }

  private static ParsedMethodMap map = null;
  public static ParsedMethodMap v() {
    if (map == null) {
      map = new ParsedMethodMap();
    }

    return map;
  }

  public ShimpleMethod getOrParseToShimple(SootMethod method) {
    if (!containsKey(method)) {
      System.out.println(Main.methodFullName(method));
      ShimpleMethod sm = ShimpleMethod.v(method);     

      // System.exit(0);
      put(method, sm);
    }
    return get(method);
  }
}
