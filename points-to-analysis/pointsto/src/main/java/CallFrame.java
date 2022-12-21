public class CallFrame {
  JavaMethod method;
  int bci;
  long[] localVarValue;

  CallFrame(JavaMethod m, int b) {
    method = m;
    bci = b;
    localVarValue = new long[m.getMethod().getLocalVariableTable().getLength()];
  }
}