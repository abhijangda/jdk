package classhierarchyanalysis;

import utils.Pair;
import parsedmethod.*;

public class CHAEdge extends Pair<ShimpleMethod, ShimpleMethod> {
  CHAEdge(ShimpleMethod caller, ShimpleMethod callee) {
    super(caller, callee);
  }
}
