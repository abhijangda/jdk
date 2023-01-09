package classhierarchyanalysis;

import utils.Pair;
import utils.Utils;

import java.io.InvalidObjectException;
import java.util.ArrayList;
import java.util.HashMap;
import parsedmethod.*;
import soot.SootClass;
import soot.SootMethod;
import soot.SootMethodInterface;
import soot.jimple.InvokeExpr;
import soot.jimple.Stmt;
import soot.jimple.internal.JInterfaceInvokeExpr;
import soot.jimple.internal.JSpecialInvokeExpr;
import soot.jimple.internal.JStaticInvokeExpr;
import soot.jimple.internal.JVirtualInvokeExpr;
import soot.shimple.ShimpleExpr;
import soot.toolkits.astmetrics.StmtSumWeightedByDepth;

public class CHACaller {
  private final ShimpleMethod caller;
  private final HashMap<InvokeExpr, ArrayList<ShimpleMethod>> invokesToCallees;

  CHACaller(ShimpleMethod caller, ClassHierarchyGraph chaGraph) {
    this.caller = caller;
    this.invokesToCallees = new HashMap<>();
    for (InvokeExpr invokeExpr : caller.getInvokeExprs()) {
      this.invokesToCallees.put(invokeExpr, new ArrayList<>()); 
    }
    build(chaGraph);
  }

  private void addCallee(ArrayList<ShimpleMethod> callees, ShimpleMethod callee) {
    callees.add(callee);
  }

  private void addCallee(ArrayList<ShimpleMethod> callees, SootMethod callee) {
    callees.add(ParsedMethodMap.v().getOrParseToShimple(callee));
  }

  public ArrayList<ShimpleMethod> getCalleesForInvoke(InvokeExpr invokeExpr) {
    return invokesToCallees.get(invokeExpr);
  }

  public void build(ClassHierarchyGraph chaGraph) {
    for (InvokeExpr invokeExpr : this.invokesToCallees.keySet()) {
      ArrayList<ShimpleMethod> callees = getCalleesForInvoke(invokeExpr);
      if (invokeExpr instanceof JStaticInvokeExpr || invokeExpr instanceof JSpecialInvokeExpr) {
        ShimpleMethod callee = ParsedMethodMap.v().getOrParseToShimple(invokeExpr.getMethod());
        callees.add(callee);
      } else if (invokeExpr instanceof JVirtualInvokeExpr || invokeExpr instanceof JInterfaceInvokeExpr) {
        SootMethod sootCallee = invokeExpr.getMethod();
        SootClass sootCalleeClass = sootCallee.getDeclaringClass();
        if (sootCallee.isConcrete()) {
          addCallee(callees, sootCallee);
        }
        if (!sootCallee.isNative() && !sootCallee.isFinal()) {
          for (SootClass subclass : chaGraph.getSubClasses(sootCalleeClass)) {
            if (subclass.declaresMethod(sootCallee.getSubSignature())) {
              addCallee(callees, subclass.getMethodUnsafe(sootCallee.getSubSignature()));
            }
          }
        }
      } else {
        Utils.debugAssert(false, "sanity %s", invokeExpr.toString());
      }
    }
  }
}
