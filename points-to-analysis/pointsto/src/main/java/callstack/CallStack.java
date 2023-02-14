package callstack;

import java.util.Stack;

public class CallStack extends Stack<CallFrame> {
  private static int numCallStacks = 0;
  private int id = 0;
  public CallStack() {
    super();
    id = numCallStacks;
    numCallStacks++;
  }

  public int getId() {
    return id;
  }

  public String toString() {
    StringBuilder builder = new StringBuilder();
    for (CallFrame frame : this) {
      builder.append(frame.method.fullname());
      builder.append(" at ");
      builder.append(frame.getCurrStmt());
      builder.append("\n");
    }

    return builder.toString();
  }
}