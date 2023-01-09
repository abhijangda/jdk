package classhierarchyanalysis;

import java.util.*;
import parsedmethod.*;

public class ClassHierarchyAnalysis extends HashMap<ShimpleMethod, CHACaller> {
  private static ClassHierarchyAnalysis instance = null;

  private ClassHierarchyAnalysis() {}

  public static ClassHierarchyAnalysis v() {
    if (instance == null) {
      instance = new ClassHierarchyAnalysis();
    }

    return instance;
  }

  
}
