package classhierarchyanalysis;

import java.util.*;

import classcollections.JavaClassCollection;
import parsedmethod.ParsedMethodMap;
import parsedmethod.ShimpleMethod;
import soot.SootClass;
import soot.SootMethod;
import soot.shimple.Shimple;
import utils.Utils;

public class ClassHierarchyGraph extends HashMap<SootClass, ArrayList<SootClass>> {
  private static ClassHierarchyGraph instance = null;

  private ClassHierarchyGraph() {}

  public ArrayList<SootClass> getSubClasses(SootClass superclass) {
    if (!containsKey(superclass)) {
      put(superclass, new ArrayList<SootClass>());
    }
    return get(superclass);
  }

  public ArrayList<ShimpleMethod> getAllOverridenMethods(ShimpleMethod baseMethod) {
    ArrayList<ShimpleMethod> overridenMethods = new ArrayList<ShimpleMethod>();
    // Utils.debugPrintln("searching for " + baseMethod.fullname());
    for (SootClass subclass : getSubClasses(baseMethod.sootMethod.getDeclaringClass())) {
      SootMethod m = subclass.getMethodUnsafe(baseMethod.sootMethod.getName(), baseMethod.sootMethod.getParameterTypes(), baseMethod.sootMethod.getReturnType());
      if (m != null) {
        ShimpleMethod sm = ParsedMethodMap.v().getOrParseToShimple(m);
        Utils.debugPrintln(sm.fullname());
        overridenMethods.add(sm);
      }
    }
    return overridenMethods;
  }

  public void build(JavaClassCollection classCollection) {
    for (SootClass klass : classCollection.values()) {
      if (klass.hasSuperclass()) {
        getSubClasses(klass.getSuperclass()).add(klass);
      }
      for (SootClass interfaceImpl : klass.getInterfaces()) {
        getSubClasses(interfaceImpl).add(klass);
      }
    }
  }

  public static ClassHierarchyGraph v() {
    if (instance == null) {
      instance = new ClassHierarchyGraph();
    }

    return instance;
  }
}
