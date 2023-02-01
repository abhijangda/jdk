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

  public ArrayList<SootClass> getImmediateSubClasses(SootClass superclass) {
    if (!containsKey(superclass)) {
      put(superclass, new ArrayList<SootClass>());
    }
    return get(superclass);
  }

  public HashSet<SootClass> getAllSubclasses(SootClass superClass) {
    HashSet<SootClass> allSubClasses = new HashSet<>();
    Stack<SootClass> stack = new Stack<>();

    stack.push(superClass);

    while (!stack.isEmpty()) {
      SootClass klass = stack.pop();
      
      ArrayList<SootClass> subclasses = getImmediateSubClasses(klass);
      stack.addAll(subclasses);
      allSubClasses.addAll(subclasses);
    }

    return allSubClasses;
  }

  public ArrayList<ShimpleMethod> getAllOverridenMethods(ShimpleMethod baseMethod) {
    ArrayList<ShimpleMethod> overridenMethods = new ArrayList<ShimpleMethod>();
    // Utils.debugPrintln("searching for " + baseMethod.fullname());
    for (SootClass subclass : getAllSubclasses(baseMethod.sootMethod.getDeclaringClass())) {
      SootMethod m = subclass.getMethodUnsafe(baseMethod.sootMethod.getName(), baseMethod.sootMethod.getParameterTypes(), baseMethod.sootMethod.getReturnType());
      if (m != null) {
        ShimpleMethod sm = ParsedMethodMap.v().getOrParseToShimple(m);
        Utils.infoPrintln(sm.fullname());
        overridenMethods.add(sm);
      }
    }
    return overridenMethods;
  }

  public void build(JavaClassCollection classCollection) {
    for (SootClass klass : classCollection.values()) {
      if (klass.hasSuperclass()) {
        getImmediateSubClasses(klass.getSuperclass()).add(klass);
      }
      for (SootClass interfaceImpl : klass.getInterfaces()) {
        getImmediateSubClasses(interfaceImpl).add(klass);
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
