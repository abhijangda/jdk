import java.util.HashMap;

import soot.SootMethod;
import soot.jimple.parser.Parse;
import soot.shimple.Shimple;
import soot.shimple.ShimpleBody;
import soot.shimple.ShimpleMethodSource;

public class ParsedMethodMap extends HashMap<SootMethod, ShimpleBody> {
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

  public ShimpleBody getOrParseToShimple(SootMethod method) {
    if (containsKey(method)) {
      ShimpleMethodSource sm = new ShimpleMethodSource(method.getSource());
      ShimpleBody sb = (ShimpleBody)sm.getBody(method, "");
      put(method, sb);
    }
    return get(method);
  }
}
