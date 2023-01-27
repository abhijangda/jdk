package callgraphanalysis;

import callstack.CallFrame;
import javaheap.HeapEvent;
import soot.Unit;
import utils.ArrayListIterator;

public class InvalidCallStackException extends CallGraphException {
  public InvalidCallStackException(CallFrame frame, ArrayListIterator<HeapEvent> currEvent, Unit Stmt) {

  }
}
