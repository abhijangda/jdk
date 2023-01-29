package parsedmethod;

import java.util.ArrayList;

import soot.toolkits.graph.Block;

public class CFGPath extends ArrayList<Block> {
  public String toString() {
    String o = "[";
    for (Block node : this) {
      o += node.getIndexInMethod() + ", ";
    }
    o += "]";
  
    return o;
  }
}
