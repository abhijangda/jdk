import soot.SootClass;

public class CallFrame {
  SootClass method;
  int bci;
  long[] localVarValue;

  CallFrame(SootClass m, int b) {
    method = m;
    bci = b;
    // localVarValue = new long[m.getLocalVariableTable().getLength()];
  }
}