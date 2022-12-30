import soot.SootClass;
import soot.Value;
import soot.shimple.Shimple;

import java.util.HashMap;
import java.util.Iterator;
import java.util.ArrayList;
import soot.jimple.InvokeStmt;

public class CallFrame {
  public final ShimpleMethod method;
  private HashMap<Value, VariableValues> allVariableValues;
  public final ArrayList<InvokeStmt> invokeStmts;
  public final CallFrame root;

  CallFrame(ShimpleMethod m, CallFrame root) {
    method = m;
    allVariableValues = method.initVarValues();
    invokeStmts = method.getInvokeStmts();
    this.root = root;
  }

  CallFrame(HeapEvent event, CallFrame root) {
    this(ParsedMethodMap.v().getOrParseToShimple(event.method), root);
  }

  public void updateValuesWithHeapEvent(HeapEvent event) {
    method.updateValuesWithHeapEvent(allVariableValues, event);
  }

  public Iterator<InvokeStmt> iterateInvokeStmts() {
    return invokeStmts.iterator();
  }
}