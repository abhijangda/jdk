package callgraphanalysis;

import parsedmethod.*;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

public class CallEdges {
  HashMap<ShimpleMethod, HashSet<ShimpleMethod>> hashMap;
  
  public CallEdges() {
    hashMap = new HashMap<>();
  }

  public void add(ShimpleMethod caller, ShimpleMethod callee) {
    if (!hashMap.containsKey(caller)) {
      hashMap.put(caller, new HashSet<>());
    }
    hashMap.get(caller).add(callee);
  }

  public void add(ShimpleMethod caller, HashSet<ShimpleMethod> callees) {
    if (!hashMap.containsKey(caller)) {
      hashMap.put(caller, new HashSet<>());
    }
    hashMap.get(caller).addAll(callees);
  }

  public void add(CallEdges edges) {
    for (Map.Entry<ShimpleMethod, HashSet<ShimpleMethod>> entry : edges.hashMap.entrySet()) {
      add(entry.getKey(), entry.getValue());
    }
  }

  public CallEdges clone() {
    CallEdges edges = new CallEdges();

    edges.add(this);

    return edges;
  }

  public String toString() {
    StringBuilder builder = new StringBuilder();
    for (Map.Entry<ShimpleMethod, HashSet<ShimpleMethod>> entry : this.hashMap.entrySet()) {
      for (ShimpleMethod callee : entry.getValue()) {
        builder.append(entry.getKey().fullname() + " " + callee.fullname() + "\n");
      }
    }

    return builder.toString();
  }
}
