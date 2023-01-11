package classhierarchyanalysis;

import utils.Pair;
import utils.Utils;

import java.io.InvalidObjectException;
import java.util.HashSet;
import java.util.Collection;
import java.util.HashMap;
import parsedmethod.*;
import soot.SootClass;
import soot.SootMethod;
import soot.Value;
import soot.jimple.*;
import soot.jimple.internal.JInterfaceInvokeExpr;
import soot.jimple.internal.JSpecialInvokeExpr;
import soot.jimple.internal.JStaticInvokeExpr;
import soot.jimple.internal.JVirtualInvokeExpr;
import soot.shimple.ShimpleExpr;
import soot.toolkits.astmetrics.StmtSumWeightedByDepth;

public class CHACaller {
  private final ShimpleMethod caller;
  private final HashMap<Value, HashSet<ShimpleMethod>> exprToCallees;

  CHACaller(ShimpleMethod caller, ClassHierarchyGraph chaGraph) {
    this.caller = caller;
    this.exprToCallees = new HashMap<>();
    for (Value expr : caller.getCallExprs()) {
      this.exprToCallees.put(expr, new HashSet<>()); 
    }
    build(chaGraph);
  }

  private void addCallee(HashSet<ShimpleMethod> callees, ShimpleMethod callee) {
    callees.add(callee);
  }

  private void addCallee(HashSet<ShimpleMethod> callees, SootMethod callee) {
    addCallee(callees, ParsedMethodMap.v().getOrParseToShimple(callee));
  }

  public HashSet<ShimpleMethod> getCalleesAtExpr(Value expr) {
    return exprToCallees.get(expr);
  }

  public void build(ClassHierarchyGraph chaGraph) {
    for (Value val : this.exprToCallees.keySet()) {
      if (val instanceof InvokeExpr) {
        InvokeExpr invokeExpr = (InvokeExpr)val;
        SootMethod sootCallee = invokeExpr.getMethod();
        if (!Utils.methodToCare(sootCallee))
          continue;
        
        HashSet<ShimpleMethod> callees = getCalleesAtExpr(val);
        if (invokeExpr instanceof JStaticInvokeExpr || invokeExpr instanceof JSpecialInvokeExpr) {
          ShimpleMethod callee = ParsedMethodMap.v().getOrParseToShimple(sootCallee);
          callees.add(callee);
        } else if (invokeExpr instanceof JVirtualInvokeExpr || invokeExpr instanceof JInterfaceInvokeExpr) {
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
      } else if (val instanceof StaticFieldRef) {
        //Add a potential clinit whenever a field of a class is accessed
        StaticFieldRef staticField = (StaticFieldRef)val;
        // Utils.debugPrintln(val);
        // Utils.debugPrintln(staticField.getFieldRef().declaringClass().getName());

        // for (SootMethod m : staticField.getFieldRef().declaringClass().getMethods()) {
        //   Utils.debugPrintln(m.getName());
        // }
        SootMethod clinit = staticField.getFieldRef().declaringClass().getMethodByName("<clinit>");
        addCallee(getCalleesAtExpr(val), clinit);
        Utils.debugAssert(clinit != null, "clinit cannot be null");
      }
    }
  }

  public Collection<HashSet<ShimpleMethod>> getAllCallees() {
    return exprToCallees.values();
  }
}
