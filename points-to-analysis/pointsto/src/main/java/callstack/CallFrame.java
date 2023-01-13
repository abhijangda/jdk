package callstack;

import soot.RefLikeType;
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
import parsedmethod.ParsedMethodMap;

import java.util.ArrayList;

import soot.jimple.BinopExpr;
import soot.jimple.CmpExpr;
import soot.jimple.CmpgExpr;
import soot.jimple.CmplExpr;
import soot.jimple.Constant;
import soot.jimple.EqExpr;
import soot.jimple.IntConstant;
import soot.jimple.InvokeExpr;
import soot.jimple.InvokeStmt;
import soot.jimple.NeExpr;
import soot.jimple.ParameterRef;
import soot.jimple.StaticFieldRef;
import soot.jimple.StaticInvokeExpr;
import soot.jimple.VirtualInvokeExpr;
import soot.jimple.internal.AbstractInstanceInvokeExpr;
import soot.jimple.internal.JGotoStmt;
import soot.jimple.internal.JIfStmt;
import soot.jimple.internal.JInterfaceInvokeExpr;
import soot.jimple.internal.JRetStmt;
import soot.jimple.internal.JReturnStmt;
import soot.jimple.internal.JReturnVoidStmt;
import soot.jimple.internal.JSpecialInvokeExpr;
import soot.jimple.internal.JStaticInvokeExpr;
import soot.jimple.internal.JVirtualInvokeExpr;
import soot.jimple.internal.JimpleLocal;
import soot.jimple.toolkits.annotation.callgraph.MethInfo;

import utils.ArrayListIterator;
import utils.Pair;
import utils.Utils;
import parsedmethod.*;

class FuncCall extends Pair<Value, Unit> {
  public boolean isStaticInit;

  private boolean isStaticInit() {
    return this.isStaticInit;
  }

  public FuncCall(Value v, Unit stmt) {
    super(v, stmt);
    this.isStaticInit = false;
  }

  public FuncCall(JStaticInvokeExpr v, Unit stmt, boolean isStaticInit) {
    this((Value)v, stmt);
    this.isStaticInit = isStaticInit;
  }

  public ShimpleMethod getCallee() {
    if (this.first instanceof JStaticInvokeExpr && isStaticInit()) {
      return Utils.getStaticInitializer((JStaticInvokeExpr)this.first);
    }

    if (this.first instanceof InvokeExpr) {
      return ParsedMethodMap.v().getOrParseToShimple(((InvokeExpr)this.first).getMethod());
    }

    if (this.first instanceof StaticFieldRef) {
      return Utils.getStaticInitializer((StaticFieldRef)this.first);
    }

    Utils.debugAssert(false, "");

    return null;
  }

  public boolean callsStaticInit() {
    if (this.first instanceof StaticFieldRef) {
      return true;
    }

    if (this.first instanceof JStaticInvokeExpr && isStaticInit()) {
      return true;
    }

    return false;
  }
}

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
  public final CallFrame parent;
  private final ProgramCounter pc;
  private final HashMap<ParameterRef, VariableValues> paramValues;
  public boolean canPrint = false;
  
  public CallFrame(ShimpleMethod m, Value invokeExpr, Unit stmt, CallFrame parent) {
    method = m;
    allVariableValues = method.initVarValues(invokeExpr, (parent == null) ? null : parent.allVariableValues);
    this.parent = parent;
    pc = new ProgramCounter();
    this.paramValues = new HashMap<>();
    Utils.debugAssert(invokeExpr != null || (invokeExpr == null && parent == null), "sanity");
    canPrint = this.method.fullname().contains("org.apache.lucene.index.SegmentInfos$FindSegmentsFile.run()");//this.method.fullname().contains("org.apache.lucene.index.SegmentInfos$FindSegmentsFile.run()");//this.method.fullname().contains("org.apache.lucene.store.FSDirectory.init"); //this.method.fullname().contains("org.apache.lucene.store.FSDirectory.getLockID()Ljava/lang/String;"); //this.method.fullname().contains("org.apache.lucene.index.DirectoryIndexReader.open(Lorg/apache/lucene/store/Directory;ZLorg/a");//this.method.fullname().contains("org.apache.lucene.store.SimpleFSLockFactory.<init>") || this.method.fullname().contains("org.apache.lucene.store.FSDirectory.init");

    if (canPrint) {
      Utils.debugPrintln(method.shimpleBody);
    }
  }

  public CallFrame(HeapEvent event, InvokeExpr invokeExpr, Unit stmt, CallFrame root) {
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
    if (method.statements != null && method.statements.size() > pc.counter) {
      Unit stmt = method.statements.get(pc.counter);
      if (stmt instanceof JRetStmt || 
          stmt instanceof JReturnStmt || 
          stmt instanceof JReturnVoidStmt)
        return false;

      return true;
    } else {
      return false;
    }
  }

  private JavaHeapElem evaluate(Value val) {
    Utils.debugPrintln(val.getClass());
    if (val instanceof CmpExpr) {
      Utils.debugAssert(false, "to handle");
    } else if (val instanceof CmpgExpr) {

    } else if (val instanceof CmplExpr) {

    } else if (val instanceof EqExpr) {

    } else if (val instanceof NeExpr) {
      NeExpr cmp = (NeExpr)val;
      evaluate(cmp.getOp1());
      evaluate(cmp.getOp2());
    } else if (val instanceof JimpleLocal) {
      Utils.debugPrintln(val);
      Utils.debugPrintln(allVariableValues.get(val).size());
      return allVariableValues.get(val).iterator().next();
    } else if (val instanceof IntConstant) {
      Utils.debugPrintln(((IntConstant)val).value);

    }
    return null;
  }

  private Block evaluateIfStmt(JIfStmt ifstmt) {
    //An Ifstmt can be evaluated if the values are in allVariables.
    Value cond = ifstmt.getCondition();
    //Can only evaluate a condition if all uses are of RefType or NULL
    boolean canEvalCond = true;
    for (ValueBox valBox : cond.getUseBoxes()) {
      Value val = valBox.getValue();
      if ((val instanceof IntConstant && ((IntConstant)val).value == 0) || 
          (val.getType() instanceof RefLikeType)) {
      } else {
        canEvalCond = false;
        break;
      }
    }

    Utils.debugAssert(cond instanceof BinopExpr, "sanity " + cond.getClass());

    if (canPrint) {
      if (canEvalCond)
        evaluate(cond);
    }

    return null;
  }

  private FuncCall nextFuncCall(ArrayListIterator<HeapEvent> eventsIterator) {
    if (!hasNextInvokeStmt()) return null;
    FuncCall funcToCall = null;
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

        funcToCall = null;

        for (ValueBox use : currStmt.getUseBoxes()) {
          if (use.getValue() instanceof InvokeExpr) {
            funcToCall = new FuncCall((InvokeExpr)use.getValue(), currStmt);
            if (funcToCall.getCallee().fullname().contains("org.apache.lucene.store.FSDirectory.init")) {
              break;
            }
          }
        }

        pc.counter++;
        
        if (funcToCall != null) {
          break;
        } 
      }

      return funcToCall;
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
        evaluateIfStmt((JIfStmt)currStmt);
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
        Block succ1 = ifBlockSuccs.get(0);
        Block succ2 = ifBlockSuccs.get(1);

        if (methodMatches) {
          //See if the event is in the paths starting from succ1 and succ2.
          //if the event is in both paths then the event is in the path starting 
          //from exit of if-else
          //otherwise set the pc to either of the successor
          
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
            //End current function
            pc.counter = method.statements.size();
          } else {
            boolean succCanCall1 = ShimpleMethod.mayCallInPath(succ1, true);
            boolean succCanCall2 = ShimpleMethod.mayCallInPath(succ2, true);

            Utils.debugPrintln(succ1.getIndexInMethod() + " -> " + succCanCall1 + " " + succ2.getIndexInMethod() + " -> " + succCanCall2);
            boolean mayCallMeth1 = false;
            boolean mayCallMeth2 = false;

            if (succCanCall1 && succCanCall2) {
              mayCallMeth1 = method.mayCallMethodInPathFromBlock(succ1, currEvent.method);
              mayCallMeth2 = method.mayCallMethodInPathFromBlock(succ2, currEvent.method);

            } else if (succCanCall1) {
              mayCallMeth1 = method.mayCallMethodInPathFromBlock(succ1, currEvent.method);
            } else if (succCanCall2) {
              mayCallMeth2 = method.mayCallMethodInPathFromBlock(succ1, currEvent.method);
            } else {
              mayCallMeth1 = false;
              mayCallMeth2 = false;
              //No point in going to next instructions because none of the blocks have
              //any more invoke statements
              pc.counter = method.statements.size();
              continue;
            }

            Utils.debugPrintln(currStmt + " " + mayCallMeth1 + " " + mayCallMeth2);
            if (mayCallMeth1 && mayCallMeth2) {
              pc.counter++;
            } else if (mayCallMeth1) {
              currStmt = succ1.getHead();
              pc.counter = method.stmtToIndex.get(currStmt);
            } else if (mayCallMeth2) {
              currStmt = succ2.getHead();
              pc.counter = method.stmtToIndex.get(currStmt);
            } else {
              Block lca = method.findLCAInPostDom(succ1, succ2);
              Utils.debugPrintln(lca.getIndexInMethod());
              pc.counter = method.stmtToIndex.get(lca.getHead());
            }
            // //Otherwise?
            // Utils.debugPrintln(method.fullname() + "\n" +  method.shimpleBody.toString());
            // Utils.debugPrintln("method not matches currevent " + currEvent + " at " + currStmt);
            // System.exit(0);
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

          Utils.debugPrintln("next event " + eventsIterator.get());
        }

        funcToCall = null;
        boolean incrementPC = true;
        for (ValueBox use : currStmt.getUseBoxes()) {
          if (use.getValue() instanceof StaticFieldRef) {
            Utils.debugPrintln(use.getValue().getClass());
            ShimpleMethod clinit = Utils.getStaticInitializer((StaticFieldRef)use.getValue());
            Utils.debugPrintln("clinit " + clinit.toString() + " " + StaticInitializers.v().wasExecuted(clinit));
            if (!StaticInitializers.v().wasExecuted(clinit)) {
              funcToCall = new FuncCall(use.getValue(), currStmt);
              if (funcToCall.getCallee() != null) {
                break;
              }
            }
            funcToCall = null;
          } else if (use.getValue() instanceof JStaticInvokeExpr) {
            ShimpleMethod clinit = Utils.getStaticInitializer((JStaticInvokeExpr)use.getValue());
            Utils.debugPrintln("clinit " + clinit + " " + StaticInitializers.v().wasExecuted(clinit));
            if (!StaticInitializers.v().wasExecuted(clinit)) {
              funcToCall = new FuncCall((JStaticInvokeExpr)use.getValue(), currStmt, true);
              if (funcToCall.getCallee() != null) {
                incrementPC = false;
                break;
              }
            }
            funcToCall = null;
          } 
          if (use.getValue() instanceof InvokeExpr) {
            funcToCall = new FuncCall((InvokeExpr)use.getValue(), currStmt);
            break;
          }
        }
        
        if (incrementPC) {
          pc.counter += 1;
          if (pc.counter < method.statements.size())
            currStmt = method.statements.get(pc.counter);
        }
    
        if (funcToCall != null) {
          break;
        }
      }
    }

    Utils.debugPrintln(funcToCall);
    if (funcToCall != null) {
      Utils.debugPrintln(funcToCall.getCallee() + " " + Utils.methodToCare(funcToCall.getCallee()) + " " + 
                         StaticInitializers.v().wasExecuted(funcToCall.getCallee()));
      Utils.debugPrintln(funcToCall.getCallee() + " " + Utils.methodToCare(funcToCall.getCallee()) + " " + 
                         StaticInitializers.v().wasExecuted(funcToCall.getCallee()));
    }

    return funcToCall;
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
    FuncCall calleeExprAndStmt = nextFuncCall(eventIterator);
    Utils.debugPrintln(calleeExprAndStmt);
    if (calleeExprAndStmt != null) Utils.debugPrintln(calleeExprAndStmt.first.toString());
    if (calleeExprAndStmt == null) return null;
    
    Value invokeExpr = calleeExprAndStmt.first;
    ShimpleMethod invokeMethod = null;
    if (method.fullname().contains("org.apache.lucene.store.FSDirectory.getDirectory(Ljava/io/File;Lorg/apache/lucene/store/LockFactory;)")) {
      //Go through FSDirectory.<init> events 
      while(eventIterator.get().methodStr.contains("org.apache.lucene.store.FSDirectory.<init>")) {
        JavaHeap.v().update(eventIterator.get());
        eventIterator.moveNext();
      }
    }
    
    Utils.debugPrintln(eventIterator.get());
    Utils.debugPrintln(calleeExprAndStmt.getCallee() + " " + Utils.methodToCare(calleeExprAndStmt.getCallee()) + " " + StaticInitializers.v().wasExecuted(calleeExprAndStmt.getCallee()));

    while (!Utils.methodToCare(calleeExprAndStmt.getCallee()) ||
           (calleeExprAndStmt.first instanceof StaticFieldRef && 
            StaticInitializers.v().wasExecuted(calleeExprAndStmt.getCallee()))) {
      // HeapEvent currEvent = eventIterator.current();
      // boolean executed = false;
      // while (!Utils.methodToCare(currEvent.method)) {
      //   JavaHeap.v().updateWithHeapEvent(currEvent);
      //   currEvent = eventIterator.next();
      //   executed = true;
      // }
      if (calleeExprAndStmt.callsStaticInit()) {
        StaticInitializers.v().setExecuted(calleeExprAndStmt.getCallee());
      }
      Utils.debugPrintln(calleeExprAndStmt.getCallee() + " " + Utils.methodToCare(calleeExprAndStmt.getCallee()) + " " + StaticInitializers.v().wasExecuted(calleeExprAndStmt.getCallee()));

      calleeExprAndStmt = nextFuncCall(eventIterator);
      if (calleeExprAndStmt == null) return null;
      invokeExpr = calleeExprAndStmt.first;
    }
    
    Utils.debugPrintln(invokeExpr.toString() + " in " + this.method.fullname() + " for " + eventIterator.get());

    if (invokeExpr instanceof JSpecialInvokeExpr) {
      invokeMethod = calleeExprAndStmt.getCallee();
    } else if (invokeExpr instanceof AbstractInstanceInvokeExpr) {
      AbstractInstanceInvokeExpr virtInvoke = (AbstractInstanceInvokeExpr)invokeExpr;
      VariableValues vals = allVariableValues.get(virtInvoke.getBase());
      // if (this.method.sootMethod.getDeclaringClass().getName().contains("QueryProcessor") &&
      //     this.method.sootMethod.getName().contains("run")) {
      //   Utils.debugPrintln("454: " + stmt.toString() + " " + virtInvoke.getBase() + " " + vals.size());
      // }
      if (vals.size() == 0) {
        Utils.debugPrintln("0 values for " + virtInvoke.getBase());
        invokeMethod = ParsedMethodMap.v().getOrParseToShimple(virtInvoke.getMethod());
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

        invokeMethod = ParsedMethodMap.v().getOrParseToShimple(klass.getMethod(virtInvoke.getMethod().getSubSignature()));
        Utils.debugPrintln("new method " + invokeMethod.toString());
      }
    } else if (invokeExpr instanceof JStaticInvokeExpr) {
      invokeMethod = calleeExprAndStmt.getCallee();
    } else if (invokeExpr instanceof StaticFieldRef) {
      invokeMethod = calleeExprAndStmt.getCallee();

    } else {
      Utils.debugAssert(false, "Unknown invoke expr type " + invokeExpr.toString());
    }

    if (calleeExprAndStmt.callsStaticInit()) {
      Utils.debugPrintln("set executed " + invokeMethod);
      StaticInitializers.v().setExecuted(invokeMethod);
    }
    Utils.debugAssert(invokeMethod != null, "%s not found\n", invokeMethod.fullname());
    return new CallFrame(invokeMethod, invokeExpr, calleeExprAndStmt.second, this);
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