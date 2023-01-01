import java.util.ArrayList;

public class CallGraphNode {
  public final CallFrame frame;
  public final CallGraphNode parent;
  public final ArrayList<CallGraphNode> children;

  CallGraphNode(CallFrame frame, CallGraphNode parent) {
    this.frame = frame;
    this.parent = parent;
    this.children = new ArrayList<>();
  }

  public void addChild(CallGraphNode child) {
    children.add(child);
  }

  public String toString() {
    return toString(0);
  }
  public String toString(int stackDepth) {
    StringBuffer buf = new StringBuffer();
    
    buf.append(">[" + stackDepth + "]");
    buf.append("[]");
    buf.append(Main.methodFullName(frame.method.sootMethod));
    buf.append("\n");

    for (CallGraphNode g : children) {
      buf.append(g.toString(stackDepth + 1));
    }

    buf.append("<[" + stackDepth + "]");
    buf.append("[]");
    buf.append(Main.methodFullName(frame.method.sootMethod));
    buf.append("\n");

    return buf.toString();
  }
}