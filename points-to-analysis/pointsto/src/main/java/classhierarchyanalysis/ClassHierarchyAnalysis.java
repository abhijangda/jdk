package classhierarchyanalysis;

import java.util.*;

import classcollections.JavaClassCollection;
import parsedmethod.*;
import soot.SootMethod;
import soot.Value;
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
      ShimpleMethod sm = ParsedMethodMap.v().getOrParseToShimple("org.apache.lucene.index.IndexFileNameFilter.getFilter()Lorg/apache/lucene/index/IndexFileNameFilter;");
      for (Value expr : sm.getCallExprs()) {
        Utils.debugPrintln(expr);
      }
      for (HashSet<ShimpleMethod> c : getCallees(chaGraph, sm).getAllCallees()) {
        Utils.debugPrintln(c);
      }
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

  public boolean mayCallInExpr(ClassHierarchyGraph chaGraph, ShimpleMethod caller, Value expr, ShimpleMethod callee) {
    SootMethod exprCallee = null;
    if (expr instanceof InvokeExpr) {
      exprCallee = ((InvokeExpr)expr).getMethod();
    } else {
      Utils.debugAssert(false, "");
    }

    if (!Utils.methodToCare(exprCallee)) {
      return false;
    }

    HashSet<ShimpleMethod> callees = getCallees(chaGraph, caller).getCalleesAtExpr(expr);

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