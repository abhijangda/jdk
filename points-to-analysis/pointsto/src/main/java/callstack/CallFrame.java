package callstack;

import soot.NullType;
import soot.RefLikeType;
import soot.RefType;
import soot.SootClass;
import soot.SootMethod;
import soot.Type;
import soot.Unit;
import soot.UnitPatchingChain;
import soot.Value;
import soot.ValueBox;
import soot.shimple.PhiExpr;
import soot.shimple.Shimple;
import soot.shimple.ShimpleBody;
import soot.toolkits.graph.Block;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;

import callgraphanalysis.CallGraphException;
import callgraphanalysis.InvalidCallStackException;
import callgraphanalysis.MultipleNextBlocksException;
import javaheap.*;
import javavalues.*;
import parsedmethod.ParsedMethodMap;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;

import soot.jimple.*;
import soot.jimple.internal.*;
import soot.jimple.toolkits.annotation.callgraph.MethInfo;

import utils.ArrayListIterator;
import utils.Pair;
import utils.Utils;
import parsedmethod.*;

class FuncCall extends Pair<Value, Unit> {
  public boolean isStaticInit;
  private final ShimpleMethod callee;

  private boolean isStaticInit() {
    return this.isStaticInit;
  }

  public FuncCall(Value v, Unit stmt, ShimpleMethod callee) {
    super(v, stmt);
    this.isStaticInit = false;
    this.callee = callee;
  }

  public FuncCall(JStaticInvokeExpr v, Unit stmt, ShimpleMethod callee, boolean isStaticInit) {
    this((Value)v, stmt, callee);
    this.isStaticInit = isStaticInit;
  }

  public FuncCall(JNewExpr v, Unit stmt, ShimpleMethod callee) {
    this((Value)v, stmt, callee);
    this.isStaticInit = true;
  }

  public ShimpleMethod getCallee() {
    if (this.first instanceof JStaticInvokeExpr && isStaticInit() || 
        this.first instanceof InvokeExpr ||
        this.first instanceof StaticFieldRef ||
        this.first instanceof JNewExpr) {
        return callee;
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

    if (this.first instanceof JNewExpr && isStaticInit()) {
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
  private HashMap<Value, JavaValue> allVariableValues;
  public HashMap<Value, JavaValue> getAllVariableValues() {
    return allVariableValues;
  }

  public final CallFrame parent;
  private final ProgramCounter pc;
  public Unit getPC() {return method.statements.get(pc.counter);}
  private final Unit parentStmt;
  public boolean canPrint = false;
  public boolean isSegmentReaderGet;
  public boolean isSegmentReaderOpenNorms;
  public boolean isQueryParseModifiers;
  public boolean isQueryParseTerm;
  private CFGPath cfgPathExecuted;
  public final JavaHeap heap;
  public final StaticInitializers staticInits;
  
  public CallFrame(JavaHeap heap, StaticInitializers staticInits, ShimpleMethod m, Value invokeExpr, Unit stmt, CallFrame parent) {
    this.heap = heap;
    method = m;
    allVariableValues = method.initVarValues(invokeExpr, (parent == null) ? null : parent.allVariableValues);
    Utils.debugPrintln(toString());
    this.parent = parent;
    pc = new ProgramCounter();
    this.parentStmt = stmt;
    cfgPathExecuted = new CFGPath();
    Utils.debugAssert(invokeExpr != null || (invokeExpr == null && parent == null), "sanity");
    canPrint = this.method.fullname().contains("org.dacapo.lusearch.Search$QueryProcessor.run()V");//"org.apache.lucene.index.IndexReader.open(Lorg/apache/lucene/store/Directory;ZLorg/apache/lucene/index/IndexDeletionPolicy;Lorg/apache/lucene/index/IndexCommit;Z)Lorg/apache/lucene/index/IndexReader;");//this.method.fullname().contains("org.apache.lucene.index.SegmentInfos$FindSegmentsFile.run()");//this.method.fullname().contains("org.apache.lucene.index.SegmentInfos$FindSegmentsFile.run()");//this.method.fullname().contains("org.apache.lucene.store.FSDirectory.init"); //this.method.fullname().contains("org.apache.lucene.store.FSDirectory.getLockID()Ljava/lang/String;"); //this.method.fullname().contains("org.apache.lucene.index.DirectoryIndexReader.open(Lorg/apache/lucene/store/Directory;ZLorg/a");//this.method.fullname().contains("org.apache.lucene.store.SimpleFSLockFactory.<init>") || this.method.fullname().contains("org.apache.lucene.store.FSDirectory.init");
    isSegmentReaderGet = this.method.fullname().contains("org.apache.lucene.index.SegmentReader.get(ZLorg/apache/lucene/store/Directory;Lorg/apache/lucene/index/SegmentInfo;Lorg/apache/lucene/index/SegmentInfos;ZZIZ)Lorg/apache/lucene/index/SegmentReader;");
    isSegmentReaderOpenNorms = method.fullname().contains("SegmentReader.openNorms");
    isQueryParseModifiers = this.method.fullname().contains("org.apache.lucene.queryParser.QueryParser.Modifiers()I");
    isQueryParseTerm = this.method.fullname().contains("org.apache.lucene.queryParser.QueryParser.Term(Ljava/lang/String;)Lorg/apache/lucene/search/Query;");
    this.staticInits = staticInits;
    // if (canPrint) {
    //   Utils.debugPrintln(method.basicBlockStr());
    // }
    // if (canPrint) {
    //   Utils.debugPrintln(method.fullname());
    //   Utils.debugPrintln(method.basicBlockStr());
    //   System.exit(0);
    // }
  }

  public CallFrame(JavaHeap heap, StaticInitializers staticInits, HeapEvent event, InvokeExpr invokeExpr, Unit stmt, CallFrame root) {
    this(heap, staticInits, ParsedMethodMap.v().getOrParseToShimple(event.method), invokeExpr, stmt, root);
  }

  private CallFrame(JavaHeap newHeap, StaticInitializers staticInits, CallFrame source) {
    this.heap = newHeap;
    this.method = source.method;
    this.parent = source.parent;
    this.pc = source.pc;
    this.cfgPathExecuted = (CFGPath)source.cfgPathExecuted.clone();
    this.canPrint = source.canPrint;
    this.isSegmentReaderGet = source.isSegmentReaderGet;
    this.isSegmentReaderOpenNorms = source.isSegmentReaderOpenNorms;
    this.isQueryParseModifiers = source.isQueryParseModifiers;
    this.parentStmt = source.parentStmt;
    this.staticInits = staticInits;
    this.allVariableValues = new HashMap<>();
    for (Map.Entry<Value, JavaValue> entry : source.allVariableValues.entrySet()) {
      if (entry.getValue() instanceof JavaRefValue) {
        this.allVariableValues.put(entry.getKey(), JavaValueFactory.v(this.heap.get(((JavaRefValue)entry.getValue()).ref.getAddress())));
      } else {
        this.allVariableValues.put(entry.getKey(), entry.getValue());
      }
    }
  }

  public CallFrame clone(JavaHeap newHeap, StaticInitializers staticInits) {
    CallFrame newFrame = new CallFrame(newHeap, staticInits, this);
    
    return newFrame;
  }

  public void setPC(Block block) {
    Utils.debugAssert(method.stmtToIndex.containsKey(block.getHead()), "");
    pc.counter = method.stmtToIndex.get(block.getHead());
  }

  public void updateValuesWithHeapEvent(HeapEvent event) {
    method.updateValuesWithHeapEvent(this, event);
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
          stmt instanceof JReturnVoidStmt)
        return false;

      return true;
    } else {
      return false;
    }
  }

  private JavaValue evaluate(Value val) {
    Utils.debugPrintln(val.getClass());
    if (val instanceof JimpleLocal) {
      Utils.debugPrintln(val);
      Utils.debugPrintln(allVariableValues.get(val));
      return allVariableValues.get(val);
    } else if (val instanceof NullConstant) {
      Utils.debugPrintln(val);
      return JavaNull.v();
    } else if (val instanceof IntConstant) {
      return JavaValueFactory.v(((IntConstant)val).value);
    } else if (val instanceof LongConstant) {
      return JavaValueFactory.v(((LongConstant)val).value);
    }
    Utils.debugAssert(false, val + " " + val.getClass());
    return null;
  }

  private boolean evaluateCond(AbstractJimpleIntBinopExpr cond) {
    JavaValue obj1 = evaluate(cond.getOp1());
    JavaValue obj2 = evaluate(cond.getOp2());

    if (cond instanceof CmpExpr) {
      Utils.debugAssert(false, "to handle");
    } else if (cond instanceof CmpgExpr) {

    } else if (cond instanceof CmplExpr) {

    } else if (cond instanceof EqExpr) {
      return obj1.equals(obj2);
    } else if (cond instanceof NeExpr) {
      return !obj1.equals(obj2);
    } else if (cond instanceof JGeExpr) {
      return ((JavaPrimValue)obj1).ge((JavaPrimValue)obj2).value;
    } else if (cond instanceof JLeExpr) {
      return ((JavaPrimValue)obj1).le((JavaPrimValue)obj2).value;
    }

    Utils.debugAssert(false, cond + " " + cond.getClass());

    return false;
  }

  private Boolean evaluateIfStmt(JIfStmt ifstmt) {
    //An Ifstmt can be evaluated if the values are in allVariables.
    Value cond = ifstmt.getCondition();
    //Can only evaluate a condition if all uses are of RefType or NULL
    boolean canEvalCond = true;
    for (ValueBox valBox : cond.getUseBoxes()) {
      Value val = valBox.getValue();
      if (val instanceof Constant || allVariableValues.get(val) != null) {
      } else {
        canEvalCond = false;
        break;
      }
    }

    Utils.debugAssert(cond instanceof BinopExpr, "sanity " + cond.getClass());
    
    if (canEvalCond) {
      boolean value = evaluateCond((AbstractJimpleIntBinopExpr)cond);
      Utils.debugPrintln(value);
      return value;
      
    }

    return null;
  }

  private void updateParentFromRet(JReturnStmt retStmt) {
    Value retVal = retStmt.getOp();
    // Utils.debugPrintln(this.parent.method.shimpleBody);
    // Utils.debugPrintln(this.method.shimpleBody);
    if (this.parentStmt instanceof JAssignStmt) {
      //Only matters if the callee statement in parent is an assignment
      // Utils.debugAssert(this.parentStmt instanceof JAssignStmt, "%s", this.parentStmt.toString());
      Value leftVal = ((JAssignStmt)this.parentStmt).getLeftOp();

      if (retVal.getType() instanceof RefLikeType) {
        Utils.debugPrintln(retStmt);
        Utils.debugPrintln(retVal);
        if (this.allVariableValues.get(retVal) == null) {
          Utils.debugPrintln("0 values for " + retVal);
        }
        JavaValue retValue = this.allVariableValues.get(retVal);
        this.parent.allVariableValues.put(leftVal, retValue);

        // this.parent.method.propogateValuesToSucc(this.parent.allVariableValues, this.parent.method.getBlockForStmt(this.parentStmt));
      }
    }
  }

  static int d = 0;
  private FuncCall nextFuncCall(ArrayListIterator<HeapEvent> eventsIterator) throws CallGraphException {
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
        if (!(currStmt instanceof JIdentityStmt) || !(currStmt instanceof JIdentityStmt && ((JIdentityStmt)currStmt).getRightOp() instanceof CaughtExceptionRef))
          this.method.propogateValues(this, cfgPathExecuted, currStmt);
        Block block = method.getBlockForStmt(currStmt);
        if (cfgPathExecuted.isEmpty()) {
          cfgPathExecuted.add(block);
        } else if (cfgPathExecuted.get(cfgPathExecuted.size() - 1) != block) {
          cfgPathExecuted.add(block);
        }

        if (currStmt instanceof JIfStmt) {
        } else if (methodMatches && this.method.getAssignStmtForBci(currEvent.bci) == currStmt) {
          heap.update(currEvent);
          updateValuesWithHeapEvent(currEvent);
          eventsIterator.moveNext();
        }

        funcToCall = null;

        for (ValueBox use : currStmt.getUseBoxes()) {
          Value val = use.getValue();
          if (val instanceof InvokeExpr) {
            funcToCall = new FuncCall(val, currStmt, Utils.getMethodForInvokeExpr((InvokeExpr)val));
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
      Block block = method.getBlockForStmt(currStmt);

      if (currStmt instanceof JAssignStmt && ((JAssignStmt)currStmt).getRightOp() instanceof PhiExpr) {
        Utils.debugPrintln(cfgPathExecuted.get(cfgPathExecuted.size() - 1).getIndexInMethod());
      }
      
      if (GlobalException.exception != null) {
        //Find succ with caughtexception
        Unit prevStmt = method.statements.get(pc.counter-1);
        Block prevBlock = method.getBlockForStmt(prevStmt);
        Block catchBlock = null;
        for (Block succ : prevBlock.getSuccs()) {
          Utils.debugPrintln(succ.getHead() + " " + succ.getIndexInMethod() + " " + succ.getHead().getClass());
          if (succ.getHead() instanceof JIdentityStmt && 
              ((JIdentityStmt)succ.getHead()).getRightOp() instanceof CaughtExceptionRef) {
            catchBlock = succ;
            //Assuming there is only one of these
            break;
          }
        }
        Utils.debugPrintln(catchBlock);
        if (catchBlock != null) {
          pc.counter = method.stmtToIndex.get(catchBlock.getHead());
          currStmt = catchBlock.getHead();
        }
      }

      Utils.debugPrintln(currStmt + " at " + pc.counter + " " + currStmt.getClass());
      if (cfgPathExecuted.isEmpty()) {
        cfgPathExecuted.add(block);
      } else if (cfgPathExecuted.get(cfgPathExecuted.size() - 1) != block) {
        cfgPathExecuted.add(block);
      }

      this.method.propogateValues(this, cfgPathExecuted, currStmt);
      if (this.method.fullname().contains("org.apache.lucene.store.SimpleFSLockFactory.<init>")) {
        // Utils.debugPrintln(pc.counter + " " + this.method.statements.size());
        // Utils.debugPrintln(this.method.fullname() + "   " + this.method.shimpleBody.toString());
        // System.exit(0);
      }

      if (currStmt instanceof JIfStmt) {
        JIfStmt ifstmt = (JIfStmt)currStmt;
        //An Ifstmt can be evaluated if the values are in allVariables.
        Value cond = ifstmt.getCondition();
        //Can only evaluate a condition if all uses are of RefType or NULL
        boolean canEvalCond = true;
        for (ValueBox valBox : cond.getUseBoxes()) {
          Value val = valBox.getValue();
          if (val instanceof Constant || allVariableValues.get(val) != null) {
            Utils.debugPrintln(val + " " + allVariableValues.get(val));
          } else {
            canEvalCond = false;
            break;
          }
        }
        Utils.debugPrintln("can evaluate " + cond + " " + canEvalCond);
        Unit target = ((JIfStmt)currStmt).getTarget();
        Utils.debugAssert(method.stmtToIndex.containsKey(target), "sanity");
        if (canEvalCond) {
          boolean condValue = evaluateCond((AbstractJimpleIntBinopExpr)cond);

          if (condValue) {
            pc.counter = method.stmtToIndex.get(ifstmt.getTarget());
          } else {
            pc.counter += 1;
          }
        } else {
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
            
            ArrayList<CFGPath> allPaths1 = method.allPathsToEvent(succ1, currEvent);
            ArrayList<CFGPath> allPaths2 = method.allPathsToEvent(succ2, currEvent);

            if (allPaths1.size() > 0 && allPaths2.size() > 0) {
              Utils.debugPrintln("Found in both");
              //TODO: Currently goes through one of the successors, but should go through both
              //and search through the call graph to find which succ should be taken.
              // Utils.debugPrintln(succ1);
              // Utils.debugPrintln(succ2);
              pc.counter++;
              // currStmt = method.statements.get(++pc.counter);
              // Utils.debugPrintln(currStmt);
            } else if (allPaths1.size() > 0) {
              currStmt = succ1.getHead();
              pc.counter = method.stmtToIndex.get(currStmt);
            } else if (allPaths2.size() > 0) {
              currStmt = succ2.getHead();
              pc.counter = method.stmtToIndex.get(currStmt);
            } else {
              Utils.debugPrintln("NOT found in any " + currEvent + " " + currStmt);
              // Utils.debugPrintln(method.fullname() + "   " + method.shimpleBody.toString());
              // Utils.debugPrintln(method.basicBlockStr());
              System.exit(0);
            }
          } else {
            if (isSegmentReaderOpenNorms && ifBlock.getIndexInMethod() == 6) {
              if (succ1.getIndexInMethod() == 7) {
                pc.counter = method.stmtToIndex.get(succ1.getHead());
              } else if (succ2.getIndexInMethod() == 7) {
                pc.counter = method.stmtToIndex.get(succ2.getHead());
              } else {
                Utils.shouldNotReachHere();
              }
              continue;
            }

            if(isMethodInCallStack(this, ParsedMethodMap.v().getOrParseToShimple(currEvent.method))) {
              //End current function
              pc.counter = method.statements.size();
            } else {
              Utils.debugPrintln(succ1);
              HashMap<Block, ArrayList<CFGPath>> allPaths1 = method.allPathsToCallee(succ1, ParsedMethodMap.v().getOrParseToShimple(currEvent.method));
              for (Map.Entry<Block, ArrayList<CFGPath>> entry : allPaths1.entrySet()) {
                for (ArrayList<Block> _path : entry.getValue()) {
                  String o = entry.getKey().getIndexInMethod() + "-> " + succ1.getIndexInMethod() + ": [";
                  for (Block node : _path) {
                    o += node.getIndexInMethod() + ", ";
                  }
                  Utils.debugPrintln(o+"]");
                }
              }
              Utils.debugPrintln(succ2);
              HashMap<Block, ArrayList<CFGPath>> allPaths2 = method.allPathsToCallee(succ2, ParsedMethodMap.v().getOrParseToShimple(currEvent.method));
              for (Map.Entry<Block, ArrayList<CFGPath>> entry : allPaths2.entrySet()) {
                for (ArrayList<Block> _path : entry.getValue()) {
                  String o = entry.getKey().getIndexInMethod() + "-> " + succ2.getIndexInMethod() + ": [";
                  for (Block node : _path) {
                    o += node.getIndexInMethod() + ", ";
                  }
                  Utils.debugPrintln(o+"]");
                }
              }
              Utils.debugPrintln(allPaths1.size());
              Utils.debugPrintln(allPaths2.size());

              if (allPaths1.size() > 0 && allPaths2.size() > 0) {
                Set<Block> commonCalleeBlocks = new HashSet<>(allPaths1.keySet());
                commonCalleeBlocks.retainAll(allPaths2.keySet());
                Utils.debugPrintln("commonCalleeBlocks " + commonCalleeBlocks.size());

                if (commonCalleeBlocks.isEmpty()) {
                  //If no common blocks then, there is a path from each block can do the call
                  //Select the block which does a heap event that exists 
                  if (isSegmentReaderOpenNorms) {
                    if (d == 1) {
                      Utils.shouldNotReachHere();
                    }
                    d += 1;
                    if (succ2.getIndexInMethod() == 9) {
                      pc.counter = method.stmtToIndex.get(succ2.getHead());
                    } else if (succ1.getIndexInMethod() == 9) {
                      pc.counter = method.stmtToIndex.get(succ1.getHead());
                    } else {
                      Utils.shouldNotReachHere();
                    }
                  } else if (isQueryParseModifiers) {
                    if (succ2.getIndexInMethod() == 1) {
                      pc.counter = method.stmtToIndex.get(succ2.getHead());
                    } else if (succ1.getIndexInMethod() == 1) {
                      pc.counter = method.stmtToIndex.get(succ1.getHead());
                    } else {
                      Utils.shouldNotReachHere();
                    }
                  } else if (isQueryParseTerm) {

                  } else {
                    throw new MultipleNextBlocksException(this, succ1, succ2);
                  }
                } else {
                  Block calleeBlock = commonCalleeBlocks.iterator().next();
                  CFGPath path1 = allPaths1.get(calleeBlock).get(0);
                  CFGPath path2 = allPaths2.get(calleeBlock).get(0);
                  Collections.reverse(path1);
                  Collections.reverse(path2);

                  Block nextBlock = null;
                  int minLength = Math.min(path1.size(), path2.size());
                  int i = 0;
                  for (i = 0; i < minLength; i++) {
                    Utils.debugPrintln(path1.get(i).getIndexInMethod() + " == " + path2.get(i).getIndexInMethod());
                    if (path1.get(i) != path2.get(i)) {
                      Utils.debugPrintln(path1.get(i));
                      Utils.debugPrintln(path2.get(i));
                      nextBlock = path2.get(i-1);
                      break;
                    }
                  }
                  if (i == minLength) {
                    nextBlock = path2.get(minLength - 1);
                  }
                  pc.counter = method.stmtToIndex.get(nextBlock.getHead());
                }
              } else if (allPaths1.size() > 0) {
                currStmt = succ1.getHead();
                pc.counter = method.stmtToIndex.get(currStmt);
              } else if (allPaths2.size() > 0) {
                currStmt = succ2.getHead();
                pc.counter = method.stmtToIndex.get(currStmt);
              } else {
                HashMap<Block, ArrayList<CFGPath>> allPathsToExit1 = method.pathToExits(succ1);
                HashMap<Block, ArrayList<CFGPath>> allPathsToExit2 = method.pathToExits(succ2);

                boolean allPathsHasHeapUpdStmt1 = true;
                boolean allPathsHasHeapUpdStmt2 = true;
                for (ArrayList<CFGPath> paths : allPathsToExit1.values()) {
                  boolean r = Utils.hasheapUpdateStmtInAllPaths(paths);
                  if (!r) {
                    allPathsHasHeapUpdStmt1 = false;
                    break;
                  }
                }
                for (ArrayList<CFGPath> paths : allPathsToExit2.values()) {
                  if (!Utils.hasheapUpdateStmtInAllPaths(paths)) {
                    allPathsHasHeapUpdStmt2 = false;
                    break;
                  }
                }

                Utils.debugPrintln("allPathsHasHeapUpdStmt1 "+ allPathsHasHeapUpdStmt1 + " allPathsHasHeapUpdStmt2 " + allPathsHasHeapUpdStmt2);

                if (allPathsToExit1.size() > 0 && allPathsToExit2.size() > 0 &&
                    allPathsHasHeapUpdStmt1 && allPathsHasHeapUpdStmt2)
                  throw new InvalidCallStackException(this, eventsIterator, currStmt);
                else //TODO: Should go to all paths that do not have heap update statement
                  pc.counter = method.statements.size();
                // throw new MultipleNextBlocksException(this, succ1, succ2);
              }
            }
          }
        }
      } else if (currStmt instanceof JTableSwitchStmt) {
        JTableSwitchStmt tableSwitch = (JTableSwitchStmt)currStmt;
        if (isQueryParseModifiers) {
          pc.counter = method.statements.size();
        } else {
          if (methodMatches) {
            Utils.shouldNotReachHere();
          } else {
            HashSet<Block> targets = new HashSet<>();
            ShimpleMethod eventMethod = ParsedMethodMap.v().getOrParseToShimple(currEvent.method);
            for (Unit targetstmt : tableSwitch.getTargets()) {
              Block targetBlock = method.getBlockForStmt(targetstmt);
              if (method.allPathsToCallee(targetBlock, eventMethod).size() > 0) {
                targets.add(targetBlock);
              }
            }
            if (tableSwitch.getDefaultTarget() != null) {
              Block targetBlock = method.getBlockForStmt(tableSwitch.getDefaultTarget());
              if (method.allPathsToCallee(targetBlock, eventMethod).size() > 0) {
                targets.add(targetBlock);
              }
            }

            throw new MultipleNextBlocksException(this, targets);
          }
        }
      } else if (currStmt instanceof JLookupSwitchStmt) {
        JLookupSwitchStmt lookup = (JLookupSwitchStmt)currStmt;
        if (method.fullname().contains("org.apache.lucene.queryParser.Token.newToken(ILjava/lang/String;)")) {
          pc.counter = method.stmtToIndex.get(lookup.getDefaultTarget());
        } else {
          if (methodMatches) {
            Utils.shouldNotReachHere();
          } else {
            ArrayList<Block> targets = new ArrayList<>();
            ShimpleMethod eventMethod = ParsedMethodMap.v().getOrParseToShimple(currEvent.method);
            for (Unit targetstmt : lookup.getTargets()) {
              Block targetBlock = method.getBlockForStmt(targetstmt);
              if (method.allPathsToCallee(targetBlock, eventMethod).size() > 0) {
                targets.add(targetBlock);
              }
            }
            if (lookup.getDefaultTarget() != null) {
              Block targetBlock = method.getBlockForStmt(lookup.getDefaultTarget());
              if (method.allPathsToCallee(targetBlock, eventMethod).size() > 0) {
                targets.add(targetBlock);
              }
            }

            throw new MultipleNextBlocksException(this, targets);
          }
        }
      } else if (currStmt instanceof JGotoStmt) {
        //Has to go to target
        Unit target = ((JGotoStmt)currStmt).getTarget();
        Utils.debugAssert(method.stmtToIndex.containsKey(target), "sanity");
        pc.counter = method.stmtToIndex.get(target);
        currStmt = method.statements.get(pc.counter);
      } else if (currStmt instanceof JReturnStmt) { 
        updateParentFromRet((JReturnStmt)currStmt);
        pc.counter = method.statements.size();
        return null;
      } else if (currStmt instanceof JThrowStmt) {
        GlobalException.exception = ((JavaObjectRef)allVariableValues.get(((JThrowStmt)currStmt).getOp())).getObject();
        Utils.debugPrintln(currStmt);
        pc.counter = method.statements.size();
        return null;
      } else {        
        funcToCall = null;
        boolean incrementPC = true;
        for (ValueBox use : currStmt.getUseBoxes()) {
          Value val = use.getValue();
          if (val instanceof JNewExpr) {
            Utils.debugPrintln(val.getClass());
            ShimpleMethodList clinits = Utils.getAllStaticInitializers((JNewExpr)val);
            ShimpleMethod clinit = clinits.nextUnexecutedStaticInit(this.staticInits);
            if (((JNewExpr)val).getType().toString().contains("StandardTokenizer")) {
              for (ShimpleMethod m : clinits) {
                Utils.debugPrintln(m.fullname() + "  " + this.staticInits.wasExecuted(m));
              }
            }
            Utils.debugPrintln(clinits.size() + " " + clinit + " " + this.staticInits.wasExecuted(clinit) + " " + this.staticInits.hashCode());
            if (!this.staticInits.wasExecuted(clinit)) {
              funcToCall = new FuncCall((JNewExpr)val, currStmt, clinit);
              if (funcToCall.getCallee() != null) {
                incrementPC = false;
                break;
              }
            }
            funcToCall = null;
          } else if (val instanceof StaticFieldRef) {
            //TODO: Should only happen when for GETSTATIC?
            Utils.debugPrintln(val.getClass());
            ShimpleMethodList clinits = Utils.getAllStaticInitializers((StaticFieldRef)val);
            ShimpleMethod clinit = clinits.nextUnexecutedStaticInit(this.staticInits);
            if (!this.staticInits.wasExecuted(clinit)) {
              funcToCall = new FuncCall(val, currStmt, clinit);
              if (funcToCall.getCallee() != null) {
                incrementPC = false;
                break;
              }
            }
            funcToCall = null;
          } else if (val instanceof JStaticInvokeExpr) {
            Utils.debugPrintln("");
            ShimpleMethodList clinits = Utils.getAllStaticInitializers((JStaticInvokeExpr)val);
            ShimpleMethod unexecClinit = clinits.nextUnexecutedStaticInit(this.staticInits);
            Utils.debugPrintln("clinit " + unexecClinit);
            if (unexecClinit != null) {
              Utils.debugPrintln("clinit " + unexecClinit + " " + this.staticInits.wasExecuted(unexecClinit));
              funcToCall = new FuncCall((JStaticInvokeExpr)val, currStmt, unexecClinit, true);
              if (funcToCall.getCallee() != null) {
                incrementPC = false;
                break;
              }
            }
            funcToCall = null;
          } 
          if (val instanceof InvokeExpr) {
            InvokeExpr invoke = (InvokeExpr)val;
            funcToCall = new FuncCall(invoke, currStmt, 
                                       Utils.getMethodForInvokeExpr(invoke));
            break;
          }
        }
        
        if (incrementPC) {
          if (Utils.canStmtUpdateHeap(currStmt)) {
            boolean throwException = false;
            if (!(currEvent.method == method.sootMethod &&
              method.getAssignStmtForBci(currEvent.bci) == currStmt)) {
              if (!currEvent.method.getName().contains("<clinit>") &&
                  !Utils.methodFullName(currEvent.method).contains("org.apache.lucene.store.BufferedIndexInput.newBuffer([B)V") &&
                  !Utils.methodFullName(currEvent.method).contains("org.apache.lucene.store.IndexInput.readString()Ljava/lang/String;") && 
                  !Utils.methodFullName(currEvent.method).contains("org.dacapo.lusearch.Search$QueryProcessor.<init>") && 
                  !method.fullname().contains("<clinit>")) {
                throwException = true;
              }
            }
            
            if (method.fullname().contains("org.apache.lucene.queryParser.QueryParser.<init>(Lorg/apache/lucene/queryParser/CharStream;)V"))
              throwException = false;
              
            if (throwException) {
              throw new InvalidCallStackException(this, eventsIterator, currStmt);
            }
          }
          
          if (methodMatches && this.method.getAssignStmtForBci(currEvent.bci) == currStmt) {
            heap.update(currEvent);
            updateValuesWithHeapEvent(currEvent);
            eventsIterator.moveNext();
  
            Utils.debugPrintln("next event " + eventsIterator.get());
          }
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
                         this.staticInits.wasExecuted(funcToCall.getCallee()));
      Utils.debugPrintln(funcToCall.getCallee() + " " + Utils.methodToCare(funcToCall.getCallee()) + " " + 
                         this.staticInits.wasExecuted(funcToCall.getCallee()));
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

  public CallFrame nextInvokeMethod(ArrayListIterator<HeapEvent> eventIterator) throws CallGraphException {
    if (isSegmentReaderGet) {
      HeapEvent currEvent = eventIterator.get();
      if (currEvent.methodStr.contains("org.apache.lucene.index.DirectoryIndexReader.<init>()V")) {
        JavaHeapElem segmentInfoObj = null;
        while (!eventIterator.get().methodStr.contains("org.apache.lucene.index.DirectoryIndexReader.init")) {  
          currEvent = eventIterator.get();
          heap.update(eventIterator.get());
          if (currEvent.methodStr.contains("org.apache.lucene.index.SegmentReader.<init>()V") && 
              currEvent.eventType == HeapEvent.EventType.ObjectFieldSet) {
            segmentInfoObj = heap.get(currEvent.dstPtr);
          }
          eventIterator.moveNext();
        }

        Utils.debugAssert(segmentInfoObj != null, "sanity");

        //Find the instance variable and set it to segmentInfoObj
        for (Value val : allVariableValues.keySet()) {
          if (val.getType() instanceof RefType &&
              ((RefType)val.getType()).getClassName().contains("org.apache.lucene.index.SegmentReader")) {
              Utils.debugPrintln("setting value of " + val + " to SegmentReader");
              allVariableValues.put(val, JavaValueFactory.v(segmentInfoObj));    
            }
        }
      }
    }
    FuncCall calleeExprAndStmt = nextFuncCall(eventIterator);
    Utils.debugPrintln(calleeExprAndStmt);
    if (calleeExprAndStmt != null) Utils.debugPrintln(calleeExprAndStmt.first.toString());
    if (calleeExprAndStmt == null) return null;
    
    Value invokeExpr = calleeExprAndStmt.first;
    ShimpleMethod invokeMethod = null;
    if (method.fullname().contains("org.apache.lucene.store.FSDirectory.getDirectory(Ljava/io/File;Lorg/apache/lucene/store/LockFactory;)")) {
      //Go through FSDirectory.<init> events 
      while(eventIterator.get().methodStr.contains("org.apache.lucene.store.FSDirectory.<init>")) {
        heap.update(eventIterator.get());
        eventIterator.moveNext();
      }
    }
    
    Utils.debugPrintln(eventIterator.get());
    Utils.debugPrintln(calleeExprAndStmt.getCallee() + " " + Utils.methodToCare(calleeExprAndStmt.getCallee()) + " " + this.staticInits.wasExecuted(calleeExprAndStmt.getCallee()));

    while (!Utils.methodToCare(calleeExprAndStmt.getCallee()) ||
           (calleeExprAndStmt.first instanceof StaticFieldRef && 
           this.staticInits.wasExecuted(calleeExprAndStmt.getCallee()))) {
      // HeapEvent currEvent = eventIterator.current();
      // boolean executed = false;
      // while (!Utils.methodToCare(currEvent.method)) {
      //   JavaHeap.v().updateWithHeapEvent(currEvent);
      //   currEvent = eventIterator.next();
      //   executed = true;
      // }
      if (calleeExprAndStmt.callsStaticInit()) {
        this.staticInits.setExecuted(calleeExprAndStmt.getCallee());
      }
      Utils.debugPrintln(calleeExprAndStmt.getCallee() + " " + Utils.methodToCare(calleeExprAndStmt.getCallee()) + " " + this.staticInits.wasExecuted(calleeExprAndStmt.getCallee()));

      calleeExprAndStmt = nextFuncCall(eventIterator);
      if (calleeExprAndStmt == null) return null;
      invokeExpr = calleeExprAndStmt.first;
    }
    
    Utils.debugPrintln(invokeExpr.toString() + " in " + this.method.fullname() + " at " + eventIterator.get());

    if (invokeExpr instanceof JSpecialInvokeExpr) {
      invokeMethod = calleeExprAndStmt.getCallee();
    } else if (invokeExpr instanceof AbstractInstanceInvokeExpr) {
      AbstractInstanceInvokeExpr virtInvoke = (AbstractInstanceInvokeExpr)invokeExpr;
      // if (this.method.sootMethod.getDeclaringClass().getName().contains("QueryProcessor") &&
      //     this.method.sootMethod.getName().contains("run")) {
      //   Utils.debugPrintln("454: " + stmt.toString() + " " + virtInvoke.getBase() + " " + vals.size());
      // }
      if (allVariableValues.get(virtInvoke.getBase()) == null) {
        Utils.debugPrintln("0 values for " + virtInvoke.getBase());
        invokeMethod = ParsedMethodMap.v().getOrParseToShimple(virtInvoke.getMethod());
      } else {
        JavaValue val = allVariableValues.get(virtInvoke.getBase());
        if (val instanceof JavaNull) {
          return null;
        }
        // JavaHeapElem[] valuesArray = new JavaHeapElem[vals.size()];
        // valuesArray = vals.toArray(valuesArray);
        Type type = val.getType();
        Utils.debugAssert(type instanceof RefType, "type instanceof " + type.getClass() + " " + val.toString());
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
    } else if (invokeExpr instanceof JNewExpr) {
      invokeMethod = calleeExprAndStmt.getCallee();
    } else {
      Utils.debugAssert(false, "Unknown invoke expr type " + invokeExpr.toString());
    }

    if (calleeExprAndStmt.callsStaticInit()) {
      Utils.debugPrintln("set executed " + invokeMethod);
      this.staticInits.setExecuted(invokeMethod);
    }
    Utils.debugAssert(invokeMethod != null, "%s not found\n", invokeMethod.fullname());
    return new CallFrame(heap, this.staticInits, invokeMethod, invokeExpr, calleeExprAndStmt.second, this);
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
    builder.append("staticInits = " + ((this.staticInits == null) ? "null" : this.staticInits.hashCode()));
    return builder.toString();
  }

  public String getAllVarValsToString() {
    StringBuilder builder = new StringBuilder();

    builder.append("[\n");
    for (var val : allVariableValues.entrySet()) {
      if (val.getValue() == null)
        continue;
      builder.append(val.getKey() + " : " + val.getKey().getType());
      builder.append(" = {");
      
      if (val instanceof JavaNull) {
        builder.append("null");
      } else {
        builder.append(val.getValue().getType());
      }
      builder.append(", ");
      
      builder.append("};\n");
    }
    builder.append("]\n");

    return builder.toString();
  }
}