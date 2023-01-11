package parsedmethod;
import java.util.HashMap;

import classcollections.JavaClassCollection;
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

  public ShimpleMethod getOrParseToShimple(String method) {
    return getOrParseToShimple(JavaClassCollection.v().getMethod(method));
  }

  public ShimpleMethod getOrParseToShimple(SootMethod method) {
    if (!containsKey(method)) {
      ShimpleMethod sm = ShimpleMethod.v(method);
      super.put(method, sm);
    }
    return super.get(method);
  }
}
