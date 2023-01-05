import soot.RefType;
import soot.SootClass;
import soot.SootMethod;
import soot.Type;
import soot.Unit;
import soot.Value;
import soot.ValueBox;
import soot.shimple.Shimple;
import soot.shimple.ShimpleBody;
import soot.toolkits.graph.Block;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;

import javaheap.HeapEvent;
import javaheap.JavaHeapElem;

import java.util.ArrayList;

import soot.jimple.InvokeExpr;
import soot.jimple.InvokeStmt;
import soot.jimple.ParameterRef;
import soot.jimple.VirtualInvokeExpr;
import soot.jimple.internal.AbstractInstanceInvokeExpr;
import soot.jimple.internal.JSpecialInvokeExpr;
import soot.jimple.internal.JStaticInvokeExpr;
import soot.jimple.internal.JVirtualInvokeExpr;
import soot.jimple.toolkits.annotation.callgraph.MethInfo;

import utils.Utils;

class ProgramCounter {
  private Block currBlock;
  private Iterator<Unit> counter;
  private Unit currStmt;

  public Iterator<Unit> getCounter() {
    return counter;
  }

  public void setCurrBlock(Block currBlock) {
    // this.currBlock = currBlock;
    this.counter = currBlock.iterator();
    this.currStmt = this.counter.next();
  }

  public Block getCurrBlock() {
    return currBlock;
  }

  public Unit getCurrStmt() {
    return currStmt;
  }

  public void setCurrStmt(Unit currStmt) {
    this.currStmt = currStmt;
  }

  public ProgramCounter(ShimpleBody b) {
    // this.currBlock = currBlock;
    if (b != null) {
      this.counter = b.getUnits().iterator();
      // this.currStmt = this.counter.next();
    } else {
      this.counter = null;
    }
  }

  public boolean hasNextStmt() {
    return this.counter != null && this.counter.hasNext();
  }

  public Unit nextStmt() {
    if (this.counter == null) return null;
    return this.counter.next();
  }
}

public class CallFrame {
  public final ShimpleMethod method;
  private HashMap<Value, VariableValues> allVariableValues;
  public final ArrayList<InvokeStmt> invokeStmts;
  public final CallFrame root;
  private Iterator<InvokeStmt> invokeStmtIterator;
  private final ProgramCounter pc;
  private final HashMap<ParameterRef, VariableValues> paramValues;

  CallFrame(ShimpleMethod m, InvokeExpr invokeExpr, Unit stmt, CallFrame root) {
    method = m;
    allVariableValues = method.initVarValues(invokeExpr, (root == null) ? null : root.allVariableValues);
    Utils.debugPrintln("CallFrame. 92:");
    invokeStmts = method.getInvokeStmts();
    this.root = root;
    invokeStmtIterator = invokeStmts.iterator();
    pc = new ProgramCounter(method.shimpleBody);
    this.paramValues = new HashMap<>();
    Utils.debugAssert(invokeExpr != null || (invokeExpr == null && root == null), "sanity");
  }

  CallFrame(HeapEvent event, InvokeExpr invokeExpr, Unit stmt, CallFrame root) {
    this(ParsedMethodMap.v().getOrParseToShimple(event.method), invokeExpr, stmt, root);
  }

  public void updateValuesWithHeapEvent(HeapEvent event) {
    method.updateValuesWithHeapEvent(allVariableValues, event);
  }

  public boolean hasNextInvokeStmt() {
    return pc.hasNextStmt();
  }

  private Pair<InvokeExpr, Unit> nextInvokeExpr() {
    Unit currStmt = null;
    while (pc.hasNextStmt()) {
      currStmt = pc.nextStmt();
      for (ValueBox use : currStmt.getUseBoxes()) {
        if (use.getValue() instanceof InvokeExpr) {
          return Pair.v((InvokeExpr)use.getValue(), currStmt);
        }
      }
    }
    return null;
    // Utils.debugPrintln(currStmt.toString());
    // Utils.debugPrintln(pc.getCurrBlock().toString());
    // while (!(currStmt instanceof InvokeStmt) && counter.hasNext()) {
    //   currStmt = counter.next();
    // }

    // Utils.debugPrintln(currStmt.toString());

    // if (currStmt instanceof InvokeStmt) {
    //   pc.setCurrStmt(currStmt);
    //   return (InvokeStmt)currStmt;
    // }

    // Utils.debugAssert(!counter.hasNext(), "sanity");

    // //TODO: going to all successors if possible
    // List<Block> succs = pc.getCurrBlock().getSuccs();
    // if (succs.size() == 0) return null;
    // //TODO: Avoid loops
    // for (Block b : succs) {
    //   if (!method.isDominator(b, pc.getCurrBlock()) && b != pc.getCurrBlock()) {
    //     Utils.debugPrintln(pc.getCurrBlock() + " --> " + b);
    //     pc.setCurrBlock(b);
    //     return nextInvokeStmt();
    //   }
    // }

    // return null;
  }

  public CallFrame nextInvokeMethod() {
    Pair<InvokeExpr, Unit> invokeExprAndStmt = nextInvokeExpr();
    if (invokeExprAndStmt == null) return null;
    InvokeExpr invokeExpr = invokeExprAndStmt.first;
    SootMethod method = null;
    if (!Utils.methodToCare(invokeExpr.getMethod()))
      return null;
    System.out.println(invokeExpr.getClass() + " " +  invokeExpr.toString());
    if (invokeExpr instanceof JSpecialInvokeExpr) {
      method = invokeExpr.getMethod();
    } else if (invokeExpr instanceof AbstractInstanceInvokeExpr) {
      AbstractInstanceInvokeExpr virtInvoke = (AbstractInstanceInvokeExpr)invokeExpr;
      VariableValues vals = allVariableValues.get(virtInvoke.getBase());
      // if (this.method.sootMethod.getDeclaringClass().getName().contains("QueryProcessor") &&
      //     this.method.sootMethod.getName().contains("run")) {
      //   Utils.debugPrintln("454: " + stmt.toString() + " " + virtInvoke.getBase() + " " + vals.size());
      // }
      if (vals.size() == 0) {
        Utils.debugPrintln("0 values for " + virtInvoke.getBase());
        method = virtInvoke.getMethod();
      } else {
        // JavaHeapElem[] valuesArray = new JavaHeapElem[vals.size()];
        // valuesArray = vals.toArray(valuesArray);
        JavaHeapElem val = vals.iterator().next();
        Type type = val.getType();
        Utils.debugAssert(type instanceof RefType, "");
        SootClass klass = ((RefType)type).getSootClass();
        while(klass != null && !klass.declaresMethod(virtInvoke.getMethod().getSubSignature())) {
          klass = klass.getSuperclass();
        }

        method = klass.getMethod(virtInvoke.getMethod().getSubSignature());
        Utils.debugPrintln("new method " + method.toString());
      }
    } else if (invokeExpr instanceof JStaticInvokeExpr) {
      method = invokeExpr.getMethod();
    } else {
      Utils.debugAssert(false, "Unknown invoke expr type " + invokeExpr.toString());
    }

    Utils.debugAssert(ParsedMethodMap.v().getOrParseToShimple(method) != null, "%s not found\n", Utils.methodFullName(method));
    Utils.debugPrintFileAndLine();
    return new CallFrame(ParsedMethodMap.v().getOrParseToShimple(method), invokeExpr, invokeExprAndStmt.second, this);
  }
}