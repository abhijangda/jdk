import soot.RefType;
import soot.SootClass;
import soot.SootMethod;
import soot.Type;
import soot.Unit;
import soot.Value;
import soot.ValueBox;
import soot.shimple.Shimple;
import soot.shimple.ShimpleBody;
import soot.toolkits.graph.Block;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;

import javaheap.HeapEvent;
import javaheap.JavaHeap;
import javaheap.JavaHeapElem;

import java.util.ArrayList;

import soot.jimple.InvokeExpr;
import soot.jimple.InvokeStmt;
import soot.jimple.ParameterRef;
import soot.jimple.VirtualInvokeExpr;
import soot.jimple.internal.AbstractInstanceInvokeExpr;
import soot.jimple.internal.JGotoStmt;
import soot.jimple.internal.JIfStmt;
import soot.jimple.internal.JInterfaceInvokeExpr;
import soot.jimple.internal.JSpecialInvokeExpr;
import soot.jimple.internal.JStaticInvokeExpr;
import soot.jimple.internal.JVirtualInvokeExpr;
import soot.jimple.toolkits.annotation.callgraph.MethInfo;
import utils.ArrayListIterator;
import utils.Utils;

class ProgramCounter {
  public int counter;

  // public void setCurrBlock(Block currBlock) {
  //   // this.currBlock = currBlock;
  //   this.counter = currBlock.iterator();
  //   this.currStmt = this.counter.next();
  // }

  // public Block getCurrBlock() {
  //   return currBlock;
  // }

  // public Unit getCurrStmt() {
  //   return currStmt;
  // }

  // public void setCurrStmt(Unit currStmt) {
  //   this.currStmt = currStmt;
  // }

  public ProgramCounter() {
    this.counter = 0;
  }
}

public class CallFrame {
  public final ShimpleMethod method;
  private HashMap<Value, VariableValues> allVariableValues;
  public final ArrayList<InvokeStmt> invokeStmts;
  public final CallFrame parent;
  private Iterator<InvokeStmt> invokeStmtIterator;
  private final ProgramCounter pc;
  private final HashMap<ParameterRef, VariableValues> paramValues;
  boolean canPrint = false;
  CallFrame(ShimpleMethod m, InvokeExpr invokeExpr, Unit stmt, CallFrame parent) {
    method = m;
    allVariableValues = method.initVarValues(invokeExpr, (parent == null) ? null : parent.allVariableValues);
    invokeStmts = method.getInvokeStmts();
    this.parent = parent;
    invokeStmtIterator = invokeStmts.iterator();
    pc = new ProgramCounter();
    this.paramValues = new HashMap<>();
    Utils.debugAssert(invokeExpr != null || (invokeExpr == null && parent == null), "sanity");
    canPrint = this.method.fullname().contains("org.apache.lucene.store.FSDirectory.getLockID()Ljava/lang/String;"); //this.method.fullname().contains("org.apache.lucene.index.DirectoryIndexReader.open(Lorg/apache/lucene/store/Directory;ZLorg/a");//this.method.fullname().contains("org.apache.lucene.store.SimpleFSLockFactory.<init>") || this.method.fullname().contains("org.apache.lucene.store.FSDirectory.init");

    if (canPrint) {
      Utils.debugPrintln(method.shimpleBody);
    }
  }

  CallFrame(HeapEvent event, InvokeExpr invokeExpr, Unit stmt, CallFrame root) {
    this(ParsedMethodMap.v().getOrParseToShimple(event.method), invokeExpr, stmt, root);
  }

  public void updateValuesWithHeapEvent(HeapEvent event) {
    method.updateValuesWithHeapEvent(allVariableValues, event);
    if (canPrint) {
      Utils.debugPrintln("After updating event " + event + " for stmt " + method.getAssignStmtForBci(event.bci));
      Utils.debugPrintln(getAllVarValsToString());
    }
  }

  public boolean hasNextInvokeStmt() {
    //TODO: fix
    return method.statements != null && method.statements.size() > pc.counter;
  }

  private Pair<InvokeExpr, Unit> nextInvokeExpr(ArrayListIterator<HeapEvent> eventsIterator) {
    if (!hasNextInvokeStmt()) return null;
    Pair<InvokeExpr, Unit> invokeExpr = null;
    Unit currStmt = method.statements.get(pc.counter);
    HeapEvent currEvent = eventsIterator.get();
    boolean methodMatches = currEvent.method == method.sootMethod;
    
    boolean isFSDirGetDir = method.fullname().contains("org.apache.lucene.store.FSDirectory.getDirectory(Ljava/io/File;Lorg/apache/lucene/store/LockFactory;)Lorg/apache/lucene/store/FSDirectory;");
    if (isFSDirGetDir) {
      while (hasNextInvokeStmt()) {
        currEvent = eventsIterator.get();
        methodMatches = currEvent.method == method.sootMethod;
        currStmt = method.statements.get(pc.counter);
        if (currStmt instanceof JIfStmt) {
        } else if (methodMatches && this.method.getAssignStmtForBci(currEvent.bci) == currStmt) {
          JavaHeap.v().update(currEvent);
          updateValuesWithHeapEvent(currEvent);
          eventsIterator.moveNext();
        }

        invokeExpr = null;

        for (ValueBox use : currStmt.getUseBoxes()) {
          if (use.getValue() instanceof InvokeExpr) {
            invokeExpr = Pair.v((InvokeExpr)use.getValue(), currStmt);
            if (Utils.methodFullName(invokeExpr.first.getMethod()).contains("org.apache.lucene.store.FSDirectory.init")) {
              break;
            }
          }
        }

        pc.counter++;
        
        if (invokeExpr != null) {
          break;
        } 
      }

      return invokeExpr;
    } else {
    while (hasNextInvokeStmt()) {
      currEvent = eventsIterator.get();
      methodMatches = currEvent.method == method.sootMethod;
      currStmt = method.statements.get(pc.counter);
      Utils.debugPrintln(currStmt + " at " + pc.counter);
      if (this.method.fullname().contains("org.apache.lucene.store.SimpleFSLockFactory.<init>")) {
        // Utils.debugPrintln(pc.counter + " " + this.method.statements.size());
        // Utils.debugPrintln(this.method.fullname() + "   " + this.method.shimpleBody.toString());
        // System.exit(0);
      }
      if (currStmt instanceof JIfStmt) {
        Unit target = ((JIfStmt)currStmt).getTarget();
        Utils.debugAssert(method.stmtToIndex.containsKey(target), "sanity");

        // There are two conditions if the IfStmt has a corresponding else block or not.
        // If there is no else block then the code will be:
        // (1) ```
        // if <cond> <label> 
        // goto <label2>
        // label:
        // <<<if-then block>>>
        // label2:
        // <<<common exit>>>
        // ```
        // Or the code can be like:
        // (2) ```
        // if <not of original cond> label2
        // <<<if-then block>>>
        // label2:
        // <<<common exit>>>
        // ```

        //If there is an else block then the code will be:
        // (3) ```
        // if <not original cond> <label-else> 
        // <<<if-then block>>>
        // goto <label2>
        // label-else:
        // <<<label-else block>>>
        // label2:
        // <<<common exit>>>
        // ```
        // (4) ```
        // if <cond> <label-then> 
        // goto <label-else>
        // label-then:
        // <<<if-then block>>>
        // goto <label2>
        // label-else:
        // <<else block>>>
        // label2:
        // <<<common exit>>>
        // ```

        //Get the blocks that are part of the then branch
        Block ifBlock = method.getBlockForStmt(currStmt);
        List<Block> ifBlockSuccs = method.filterNonCatchBlocks(ifBlock.getSuccs());
        Utils.debugAssert(ifBlockSuccs.size() == 2, "ifBlockSuccs.size() == %d", ifBlockSuccs.size());
        //Get blocks that are part of the else branch

        if (methodMatches) {
          //See if the event is in the paths starting from succ1 and succ2.
          //if the event is in both paths then the event is in the path starting 
          //from exit of if-else
          //otherwise set the pc to either of the successor
          Block succ1 = ifBlockSuccs.get(0);
          Block succ2 = ifBlockSuccs.get(1);
          boolean inPath1 = method.isEventInPathFromBlock(succ1, currEvent);
          boolean inPath2 = method.isEventInPathFromBlock(succ2, currEvent);
          if (canPrint) {
            Utils.debugPrintln(currStmt);
            Utils.debugPrintln(succ1);
            Utils.debugPrintln(succ2);
          }
          if (inPath1 && inPath2) {
            Utils.debugPrintln("Found in both");
            //TODO: Currently goes through one of the successors, but should go through both
            //and search through the call graph to find which succ should be taken.
            // Utils.debugPrintln(succ1);
            // Utils.debugPrintln(succ2);
            pc.counter++;
            // currStmt = method.statements.get(++pc.counter);
            // Utils.debugPrintln(currStmt);
          } else if (inPath1) {
            currStmt = succ1.getHead();
            pc.counter = method.stmtToIndex.get(currStmt);
          } else if (inPath2) {
            currStmt = succ2.getHead();
            pc.counter = method.stmtToIndex.get(currStmt);
          } else {
            Utils.debugPrintln("NOT found in any " + currEvent + " " + currStmt);
            // Utils.debugPrintln(method.fullname() + "   " + method.shimpleBody.toString());
            // Utils.debugPrintln(method.basicBlockStr());
            System.exit(0);
          }
        } else {
          if(isMethodInCallStack(this, ParsedMethodMap.v().getOrParseToShimple(currEvent.method))) {
            //Then continue with next statement
            pc.counter++;
          } else {
            //Otherwise?
            Utils.debugPrintln(method.fullname() + "\n" +  method.shimpleBody.toString());
            Utils.debugPrintln("method not matches currevent " + currEvent + " at " + currStmt);
            System.exit(0);
          }
        }
      } else if (currStmt instanceof JGotoStmt) {
        //Has to go to target
        Unit target = ((JGotoStmt)currStmt).getTarget();
        Utils.debugAssert(method.stmtToIndex.containsKey(target), "sanity");
        pc.counter = method.stmtToIndex.get(target);
        currStmt = method.statements.get(pc.counter);
      } else {
        if (methodMatches && this.method.getAssignStmtForBci(currEvent.bci) == currStmt) {
          JavaHeap.v().update(currEvent);
          updateValuesWithHeapEvent(currEvent);
          eventsIterator.moveNext();
        }

        invokeExpr = null;

        for (ValueBox use : currStmt.getUseBoxes()) {
          if (use.getValue() instanceof InvokeExpr) {
            invokeExpr = Pair.v((InvokeExpr)use.getValue(), currStmt);
            break;
          }
        }
        
        pc.counter += 1;
        if (pc.counter < method.statements.size())
          currStmt = method.statements.get(pc.counter);
        if (invokeExpr != null) {
          break;
        }
      }
    }

    return invokeExpr;
    }
    // Utils.debugPrintln(currStmt.toString());
    // Utils.debugPrintln(pc.getCurrBlock().toString());
    // while (!(currStmt instanceof InvokeStmt) && counter.hasNext()) {
    //   currStmt = counter.next();
    // }

    // Utils.debugPrintln(currStmt.toString());

    // if (currStmt instanceof InvokeStmt) {
    //   pc.setCurrStmt(currStmt);
    //   return (InvokeStmt)currStmt;
    // }

    // Utils.debugAssert(!counter.hasNext(), "sanity");

    // //TODO: going to all successors if possible
    // List<Block> succs = pc.getCurrBlock().getSuccs();
    // if (succs.size() == 0) return null;
    // //TODO: Avoid loops
    // for (Block b : succs) {
    //   if (!method.isDominator(b, pc.getCurrBlock()) && b != pc.getCurrBlock()) {
    //     Utils.debugPrintln(pc.getCurrBlock() + " --> " + b);
    //     pc.setCurrBlock(b);
    //     return nextInvokeStmt();
    //   }
    // }

    // return null;
  }

  public CallFrame nextInvokeMethod(ArrayListIterator<HeapEvent> eventIterator) {
    Pair<InvokeExpr, Unit> invokeExprAndStmt = nextInvokeExpr(eventIterator);
    if (invokeExprAndStmt != null) Utils.debugPrintln(invokeExprAndStmt.first.toString());
    if (invokeExprAndStmt == null) return null;
    
    InvokeExpr invokeExpr = invokeExprAndStmt.first;
    SootMethod invokeMethod = null;
    if (method.fullname().contains("org.apache.lucene.store.FSDirectory.getDirectory(Ljava/io/File;Lorg/apache/lucene/store/LockFactory;)")) {
      //Go through FSDirectory.<init> events 
      while(eventIterator.get().methodStr.contains("org.apache.lucene.store.FSDirectory.<init>")) {
        JavaHeap.v().update(eventIterator.get());
        eventIterator.moveNext();
      }
    }
    
    Utils.debugPrintln(eventIterator.get());

    while (!Utils.methodToCare(invokeExpr.getMethod())) {
      // HeapEvent currEvent = eventIterator.current();
      // boolean executed = false;
      // while (!Utils.methodToCare(currEvent.method)) {
      //   JavaHeap.v().updateWithHeapEvent(currEvent);
      //   currEvent = eventIterator.next();
      //   executed = true;
      // }

      invokeExprAndStmt = nextInvokeExpr(eventIterator);
      if (invokeExprAndStmt == null) return null;
      invokeExpr = invokeExprAndStmt.first;
    }
    
    Utils.debugPrintln(invokeExpr.toString() + " in " + this.method.fullname() + " for " + eventIterator.get());

    if (invokeExpr instanceof JSpecialInvokeExpr) {
      invokeMethod = invokeExpr.getMethod();
    } else if (invokeExpr instanceof AbstractInstanceInvokeExpr) {
      AbstractInstanceInvokeExpr virtInvoke = (AbstractInstanceInvokeExpr)invokeExpr;
      VariableValues vals = allVariableValues.get(virtInvoke.getBase());
      // if (this.method.sootMethod.getDeclaringClass().getName().contains("QueryProcessor") &&
      //     this.method.sootMethod.getName().contains("run")) {
      //   Utils.debugPrintln("454: " + stmt.toString() + " " + virtInvoke.getBase() + " " + vals.size());
      // }
      if (vals.size() == 0) {
        Utils.debugPrintln("0 values for " + virtInvoke.getBase());
        invokeMethod = virtInvoke.getMethod();
      } else {
        // JavaHeapElem[] valuesArray = new JavaHeapElem[vals.size()];
        // valuesArray = vals.toArray(valuesArray);
        JavaHeapElem val = vals.iterator().next();
        Type type = val.getType();
        Utils.debugAssert(type instanceof RefType, "");
        SootClass klass = ((RefType)type).getSootClass();
        Utils.debugPrintln(klass.getName());
        while(klass != null && !klass.declaresMethod(virtInvoke.getMethod().getSubSignature())) {
          klass = klass.getSuperclass();
        }

        invokeMethod = klass.getMethod(virtInvoke.getMethod().getSubSignature());
        Utils.debugPrintln("new method " + invokeMethod.toString());
      }
    } else if (invokeExpr instanceof JStaticInvokeExpr) {
      invokeMethod = invokeExpr.getMethod();
    } else {
      Utils.debugAssert(false, "Unknown invoke expr type " + invokeExpr.toString());
    }

    Utils.debugAssert(ParsedMethodMap.v().getOrParseToShimple(invokeMethod) != null, "%s not found\n", Utils.methodFullName(invokeMethod));
    return new CallFrame(ParsedMethodMap.v().getOrParseToShimple(invokeMethod), invokeExpr, invokeExprAndStmt.second, this);
  }

  /*
   * Starting from the leaf CallFrame search if the method is present in any of the 
   * above call frames in the stack
   */
  public static boolean isMethodInCallStack(CallFrame leaf, ShimpleMethod method) {
    CallFrame frame = leaf;

    while (frame != null) {
      if (frame.method == method) {
        return true;
      }

      frame = frame.parent;
    }

    return false;
  }

  public String toString() {
    StringBuilder builder = new StringBuilder();

    builder.append(method.fullname() + "\n");
    builder.append(getAllVarValsToString());

    return builder.toString();
  }

  public String getAllVarValsToString() {
    StringBuilder builder = new StringBuilder();

    builder.append("[\n");
    for (var vals : allVariableValues.entrySet()) {
      if (vals.getValue().size() == 0)
        continue;
      builder.append(vals.getKey());
      builder.append(" = {");
      for (var val : vals.getValue()) {
        if (val != null) {
          builder.append(val.getType());
        } else {
          builder.append("null");
        }
        builder.append(", ");
      }
      builder.append("};\n");
    }
    builder.append("]\n");

    return builder.toString();
  }
}