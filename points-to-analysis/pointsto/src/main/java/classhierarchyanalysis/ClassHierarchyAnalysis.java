package classhierarchyanalysis;

import java.util.*;

import classcollections.JavaClassCollection;
import parsedmethod.*;
import soot.jimple.InvokeExpr;
import soot.shimple.Shimple;
import utils.Utils;

public class ClassHierarchyAnalysis extends HashMap<ShimpleMethod, CHACaller> {
  private static ClassHierarchyAnalysis instance = null;

  private ClassHierarchyAnalysis() {}

  public static ClassHierarchyAnalysis v() {
    if (instance == null) {
      instance = new ClassHierarchyAnalysis();
    }

    return instance;
  }
  
  public CHACaller getCallees(ClassHierarchyGraph chaGraph, ShimpleMethod caller) {
    if (!containsKey(caller)) {
      put(caller, new CHACaller(caller, chaGraph));
    }

    return get(caller);
  }

  public boolean mayCall(ClassHierarchyGraph chaGraph, ShimpleMethod caller, String callee) {
    return mayCall(chaGraph, caller, 
                   ParsedMethodMap.v().getOrParseToShimple(JavaClassCollection.v().getMethod(callee)));
  }

  public boolean mayCall(ClassHierarchyGraph chaGraph, String caller, String callee) {
    return mayCall(chaGraph, ParsedMethodMap.v().getOrParseToShimple(JavaClassCollection.v().getMethod(caller)), 
                   ParsedMethodMap.v().getOrParseToShimple(JavaClassCollection.v().getMethod(callee)));
  }

  public boolean mayCall(ClassHierarchyGraph chaGraph, ShimpleMethod caller, ShimpleMethod callee) {
    if (caller.fullname().contains("org.apache.lucene.index.SegmentInfos$FindSegmentsFile.run()") && !callee.fullname().contains("org.apache.lucene.index.IndexFileNameFilter.<clinit>()V")) { //!callee.fullname().contains("org.apache.lucene.index.IndexFileNameFilter.getFilter()Lorg/apache/lucene/index/IndexFileNameFilter;")
      Utils.debugPrintln("Does run reaches?");
      boolean f = mayCall(chaGraph, caller, "org.apache.lucene.index.IndexFileNameFilter.<clinit>()V");
      Utils.debugPrintln(f);
    }
    Stack<ShimpleMethod> stack = new Stack<ShimpleMethod>();
    stack.push(caller);
    Set<ShimpleMethod> visited = new HashSet<>();

    while (!stack.isEmpty()) {
      ShimpleMethod m = stack.pop();
      if (m == callee) return true;

      if (visited.contains(m)) continue;
      visited.add(m);
      CHACaller chaCaller = getCallees(chaGraph, m);
      for (HashSet<ShimpleMethod> callees : chaCaller.getAllCallees()) {
        stack.addAll(callees);
      }
    }

    return false;
  } 

  public boolean mayCallAtInvoke(ClassHierarchyGraph chaGraph, ShimpleMethod caller, InvokeExpr invokeExpr, ShimpleMethod callee) {
    if (!Utils.methodToCare(invokeExpr.getMethod())) {
      return false;
    }

    HashSet<ShimpleMethod> callees = getCallees(chaGraph, caller).getCalleesAtExpr(invokeExpr);

    if (callees.contains(callee))
      return true;

    for (ShimpleMethod directCallee : callees) {
      if (mayCall(chaGraph, directCallee, callee)) {
        return true;
      }
    }

    return false;
  }
}