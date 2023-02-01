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

  public void mayCallTest(ClassHierarchyGraph chaGraph) {
    if (true) {
      String calleeStr = "org.apache.lucene.queryParser.FastCharStream.BeginToken()C"; //"org.apache.lucene.store.FSDirectory.openInput"
      ShimpleMethod caller = ParsedMethodMap.v().getOrParseToShimple("org.apache.lucene.queryParser.QueryParserTokenManager.getNextToken()Lorg/apache/lucene/queryParser/Token;");// "org.apache.lucene.queryParser.QueryParser.jj_scan_token(I)Z");
      ShimpleMethod callee = ParsedMethodMap.v().getOrParseToShimple(calleeStr);
      if (true) {
        Utils.infoPrintln("Does run reaches?");
        for (Value expr : caller.getCallExprs()) {
          Utils.infoPrintln(expr);
        }
        for (HashSet<ShimpleMethod> c : getCallees(chaGraph, caller).getAllCallees()) {
          Utils.infoPrintln(c);
        }
        boolean f = mayCall(chaGraph, caller, calleeStr);
        Utils.infoPrintln(f);
        System.exit(0);
      }
    }
    
  }

  public boolean mayCall(ClassHierarchyGraph chaGraph, ShimpleMethod caller, ShimpleMethod callee) {
    // if (caller.fullname().contains("org.apache.lucene.index.SegmentInfos$FindSegmentsFile.run()") && !callee.fullname().contains("org.apache.lucene.index.IndexFileNameFilter.<clinit>()V")) { //!callee.fullname().contains("org.apache.lucene.index.IndexFileNameFilter.getFilter()Lorg/apache/lucene/index/IndexFileNameFilter;")
    Stack<ShimpleMethod> stack = new Stack<ShimpleMethod>();
    stack.push(caller);
    Set<ShimpleMethod> visited = new HashSet<>();

    while (!stack.isEmpty()) {
      ShimpleMethod m = stack.pop();
      if (m == null) continue;
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
    if (expr instanceof InvokeExpr) {
      SootMethod exprCallee = ((InvokeExpr)expr).getMethod();
      if (!Utils.methodToCare(exprCallee)) {
        return false;
      }
    } 
    Utils.debugPrintln(expr);
    HashSet<ShimpleMethod> callees = getCallees(chaGraph, caller).getCalleesAtExpr(expr);
    if (callees == null)
      return false;
      
    if (callees.contains(callee))
      return true;
    
    for (ShimpleMethod directCallee : callees) {
      Utils.debugPrintln(directCallee.fullname());
      if (mayCall(chaGraph, directCallee, callee)) {
        return true;
      }
    }

    return false;
  }
}