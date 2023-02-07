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

  public StaticInitializers() {
    executedClInit = new HashSet<>();
    setExecuted("org.apache.lucene.util.UnicodeUtil.<clinit>()V");
  }

  public StaticInitializers clone() {
    StaticInitializers copy = new StaticInitializers();
    for (ShimpleMethod m : this.executedClInit)
      copy.executedClInit.add(m);
    return copy;
  }

  public void setExecuted(String method) {
    setExecuted(ParsedMethodMap.v().getOrParseToShimple(method));
  }

  public void setExecuted(ShimpleMethod method) {
    Utils.infoPrintln("setexecuted " + method.fullname() + " in " + this.hashCode());
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
