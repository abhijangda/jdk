package callgraphanalysis;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

import soot.shimple.Shimple;
import utils.Pair;
import utils.Utils;

import parsedmethod.*;
import callstack.*;

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
    buf.append(Utils.methodFullName(frame.method.sootMethod));
    buf.append("\n");

    for (CallGraphNode g : children) {
      buf.append(g.toString(stackDepth + 1));
    }

    buf.append("<[" + stackDepth + "]");
    buf.append("[]");
    buf.append(Utils.methodFullName(frame.method.sootMethod));
    buf.append("\n");

    return buf.toString();
  }
  
  private void getEdges(Set<Pair<ShimpleMethod, ShimpleMethod>> callEdges) {
    for (CallGraphNode child : children) {
      callEdges.add(Pair.v(frame.method, child.frame.method));
    }

    for (CallGraphNode child : children) {
      child.getEdges(callEdges);
    }
  }

  public Set<Pair<ShimpleMethod, ShimpleMethod>> getEdges() {
    Set<Pair<ShimpleMethod, ShimpleMethod>> callEdges = new HashSet<>();
    getEdges(callEdges);
    return callEdges;
  }

  public String edgesToString() {
    Set<Pair<ShimpleMethod, ShimpleMethod>> edges = getEdges();
    StringBuilder builder = new StringBuilder();
  
    for (Pair<ShimpleMethod, ShimpleMethod> edge : edges) {
      builder.append(Utils.methodFullName(edge.first.sootMethod) + " " + 
                     Utils.methodFullName(edge.second.sootMethod) + "\n");
    }

    return builder.toString();
  }
}