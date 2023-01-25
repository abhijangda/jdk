package classhierarchyanalysis;

import java.util.*;

import classcollections.JavaClassCollection;
import soot.SootClass;

public class ClassHierarchyGraph extends HashMap<SootClass, ArrayList<SootClass>> {
  private static ClassHierarchyGraph instance = null;

  private ClassHierarchyGraph() {}

  public ArrayList<SootClass> getSubClasses(SootClass superclass) {
    if (!containsKey(superclass)) {
      put(superclass, new ArrayList<SootClass>());
    }
    return get(superclass);
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
