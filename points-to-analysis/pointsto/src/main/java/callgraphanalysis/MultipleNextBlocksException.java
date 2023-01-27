package callgraphanalysis;

import callstack.*;
import java.util.*;
import soot.toolkits.graph.Block;

public class MultipleNextBlocksException extends CallGraphException {
  public final CallFrame frame;
  public final ArrayList<Block> nextBlocks;

  public MultipleNextBlocksException(CallFrame frame, ArrayList<Block> nextBlocks) {
    this.frame = frame;
    this.nextBlocks = nextBlocks;
  }

  public MultipleNextBlocksException(CallFrame frame, Block block1, Block block2) {
    this(frame, new ArrayList<Block>(List.of(block1, block2)));
  }
}
