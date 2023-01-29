package parsedmethod;

import java.util.*;

import callstack.StaticInitializers;
import classcollections.JavaClassCollection;
import soot.SootMethod;
import utils.Utils;

public class ShimpleMethodList extends ArrayList<ShimpleMethod> {
  public ShimpleMethodList() {
    super();
  }
  
  public ShimpleMethodList(List<ShimpleMethod> list) {
    super(list);
  }

  public ShimpleMethodList(ShimpleMethod e1) {
    this(List.of(e1));
  }

  public ShimpleMethodList(SootMethod e1) {
    this(List.of(ParsedMethodMap.v().getOrParseToShimple(e1)));
  }

  public boolean contains(String methodStr) {
    ShimpleMethod method = ParsedMethodMap.v().getOrParseToShimple(methodStr);
    return super.contains(method);
  }

  public ShimpleMethod nextUnexecutedStaticInit(StaticInitializers staticInits) {
    Iterator<ShimpleMethod> iter = iterator();
    while (iter.hasNext()) {
      ShimpleMethod clinit = iter.next();
      if (clinit.isStaticInitializer() &&
          Utils.methodToCare(clinit) &&
          !staticInits.wasExecuted(clinit)) {
            return clinit;
      }
    }

    return null;
  }
}
