package callstack;

import java.util.HashSet;

import parsedmethod.ParsedMethodMap;
import parsedmethod.ShimpleMethod;
import soot.SootClass;
import soot.SootMethod;
import soot.jimple.StaticFieldRef;
import utils.Utils;

public class StaticInitializers {
  private HashSet<ShimpleMethod> executedClInit;
  private static StaticInitializers instance = null;

  private StaticInitializers() {
    executedClInit = new HashSet<>();
  }

  public static StaticInitializers v() {
    if (instance == null) {
      instance = new StaticInitializers();
    }

    return instance;
  }

  public void setExecuted(String method) {
    setExecuted(ParsedMethodMap.v().getOrParseToShimple(method));
  }

  public void setExecuted(ShimpleMethod method) {
    executedClInit.add(method);
  }

  public void setExecuted(SootMethod method) {
    setExecuted(ParsedMethodMap.v().getOrParseToShimple(method));
  }

  public void setClinitExecutedFor(SootClass klass) {
    SootMethod clinit = klass.getMethodByNameUnsafe("<clinit>");
    if (clinit != null) {
      setExecuted(clinit);
    }
  }

  public boolean wasExecuted(String method) {
    return wasExecuted(ParsedMethodMap.v().getOrParseToShimple(method));
  }

  public boolean wasExecuted(SootMethod method) {
    return wasExecuted(ParsedMethodMap.v().getOrParseToShimple(method));
  }

  public boolean wasExecuted(ShimpleMethod method) {
    return executedClInit.contains(method);
  }
}
