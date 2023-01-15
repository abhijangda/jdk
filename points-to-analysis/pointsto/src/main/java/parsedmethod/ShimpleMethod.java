package parsedmethod;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.Stack;
import java.util.TreeSet;
import java.util.concurrent.BlockingDeque;

import org.apache.bcel.Const;
import org.apache.bcel.classfile.Method;
import org.apache.bcel.util.ByteSequence;

import classcollections.BCELClassCollection;
import classhierarchyanalysis.ClassHierarchyAnalysis;
import classhierarchyanalysis.ClassHierarchyGraph;
import javaheap.HeapEvent;
import javaheap.JavaHeap;
import javaheap.JavaHeapElem;
import javaheap.JavaObject;
import soot.*;
import soot.jimple.*;
import soot.jimple.internal.*;
import soot.shimple.ShimpleBody;
import soot.shimple.ShimpleMethodSource;
import soot.shimple.internal.SPhiExpr;
import soot.shimple.internal.SPiExpr;
import soot.toolkits.graph.*;
import soot.toolkits.scalar.ValueUnitPair;

import utils.Utils;
import callstack.*;

public class ShimpleMethod {
  public static class BciToJAssignStmt extends HashMap<Integer, JAssignStmt> {}

  //Need to use Apache BCEL to find bytecode size of a method.
  private static int opcodeSize(int opcode) {
    int size = 0;
    if (Const.getNoOfOperands(opcode) > 0) {
      for (int i = 0; i < Const.getOperandTypeCount(opcode); i++) {
          switch (Const.getOperandType(opcode, i)) {
          case Const.T_BYTE:
              size+= 1;
              break;
          case Const.T_SHORT:
              size += 2;
              break;
          case Const.T_INT:
              size += 4;
              break;
          default: // Never reached
              throw new IllegalStateException("Unreachable default case reached!");
          }
      }
    }
    return size;
  }

  private static HashMap<Short, ArrayList<Integer>> createOpcodeTypeToBCIndexMap(SootMethod method, boolean hasRet[]) {
    Method bcelMethod = BCELClassCollection.v().getMethod(Utils.methodFullName(method));
    Utils.debugAssert(bcelMethod != null, "%s is null\n", Utils.methodFullName(method));
    byte[] code = bcelMethod.getCode().getCode();
    HashMap<Short, ArrayList<Integer>> opcodeTypeToBCIndex = new HashMap<>();
    
    for (short i = 0; i < Const.OPCODE_NAMES_LENGTH; i++) {
      opcodeTypeToBCIndex.put(i, new ArrayList<Integer>());
    }
    
    //Parse bytecode and create the map of {opcode type : [bc index #1, bc index #2, ...]}
    try (ByteSequence stream = new ByteSequence(code)) {      
      for (; stream.available() > 0;) {
        int opcodeStart = stream.getIndex();
        final short opcode = (short)stream.readUnsignedByte();
        int sz = opcodeSize(opcode);
        int noPadBytes = 0;
        if (opcode == Const.TABLESWITCH || opcode == Const.LOOKUPSWITCH) {
            final int remainder = stream.getIndex() % 4;
            noPadBytes = remainder == 0 ? 0 : 4 - remainder;
            for (int i = 0; i < noPadBytes; i++) {
                byte b;
                if ((b = stream.readByte()) != 0) {
                    System.err.println("Warning: Padding byte != 0 in " + Const.getOpcodeName(opcode) + ":" + b);
                }
            }
            stream.readInt(); //default offset
        }
        switch (opcode) {
          case Const.TABLESWITCH: {
            int low = stream.readInt();
            int high = stream.readInt();
            int jumpTableLen = high - low + 1;
            for (int i = 0; i < jumpTableLen; i++) {
              stream.readInt();
            }
            break;
          }
          case Const.LOOKUPSWITCH: {
            int npairs = stream.readInt();
            for (int i = 0; i < npairs; i++) {
                stream.readInt(); //Read match
                stream.readInt(); //Read jump table offset
                if (i < npairs - 1) {
                }
            }
            break;
          }
          case Const.RET:
            hasRet[0] = true;
            break;

          default:
            // int sz = opcodeSize(opcode);
            for (; sz > 0; sz--) {
              stream.readUnsignedByte();
            }
            break;
        }
        opcodeTypeToBCIndex.get(opcode).add(opcodeStart);
      }
    } catch(Exception e) {
      e.printStackTrace();
    }

    return opcodeTypeToBCIndex;
  }

  public static boolean isPrimitiveType(soot.Type sootType) {
    if (sootType instanceof BooleanType || 
        sootType instanceof IntegerType ||
        sootType instanceof ByteType    ||
        sootType instanceof FloatType   ||
        sootType instanceof CharType    ||
        sootType instanceof DoubleType  ||
        sootType instanceof LongType)
      return true;

    return false;
  }

  public static short opcodeForJAssign(soot.jimple.internal.JAssignStmt stmt) {
    Utils.debugAssert(stmt instanceof soot.jimple.internal.JAssignStmt, "");
 
    Value left = stmt.getLeftOp();
    Value right = stmt.getRightOp();
    short opcode = -1;
    
    if (left instanceof StaticFieldRef) {
      opcode = Const.PUTSTATIC;
    } if (left instanceof JInstanceFieldRef) {
      opcode = Const.PUTFIELD;
    } else if (left instanceof JArrayRef) {
      if (!isPrimitiveType(((JArrayRef)left).getType())) {
        opcode = Const.AASTORE;
      }
    } else if (right instanceof JNewExpr) {
      opcode = Const.NEW;
    } else if (right instanceof JNewArrayExpr) {
      if (isPrimitiveType(((JNewArrayExpr)right).getBaseType())) {
        opcode = Const.NEWARRAY;
      } else {
        opcode = Const.ANEWARRAY;
      }
    } else if (right instanceof JNewMultiArrayExpr) {
      opcode = Const.MULTIANEWARRAY;
    }

    return opcode;
  }
  
  private static BciToJAssignStmt buildBytecodeIndexToInsnMap(SootMethod method, ShimpleBody sb) {
    boolean[] hasRet = new boolean[1];
    HashMap<Short, ArrayList<Integer>> opcodeTypeToBCIndex = createOpcodeTypeToBCIndexMap(method, hasRet);

    int[] numJExprsForOpcodeType = new int[Const.OPCODE_NAMES_LENGTH];
    Arrays.fill(numJExprsForOpcodeType, 0);
    
    BciToJAssignStmt bciToJAssignStmt = new BciToJAssignStmt();

    for (Unit u : sb.getUnits()) {
      if (u instanceof soot.jimple.internal.JAssignStmt) {
        soot.jimple.internal.JAssignStmt stmt = (soot.jimple.internal.JAssignStmt) u;
        short opcode = opcodeForJAssign(stmt);
        if (opcode != -1) {
          if (numJExprsForOpcodeType[opcode] >= opcodeTypeToBCIndex.get(opcode).size() && hasRet[0]) {
            //TODO: With ret/jsr Shimple copies two nodes, so ignore it.
            continue;
          } else {
            int bci = opcodeTypeToBCIndex.get(opcode).get(numJExprsForOpcodeType[opcode]);
            numJExprsForOpcodeType[opcode]++;
            bciToJAssignStmt.put(bci, stmt);
          }
        }
      }
    }

    if (Utils.DEBUG_PRINT) {
      for (short i = 0; i < Const.OPCODE_NAMES_LENGTH; i++) {
        if (i == Const.PUTFIELD || i == Const.AASTORE || i == Const.NEW || i == Const.NEWARRAY || i == Const.ANEWARRAY || i == Const.MULTIANEWARRAY) {
          int x = opcodeTypeToBCIndex.get(i).size();
          int y = numJExprsForOpcodeType[i];
          Utils.debugAssert(x == y, "%d != %d\n", x, y);
        }
      }
    }

    return bciToJAssignStmt;
  }

  private final BciToJAssignStmt bciToJAssignStmt;
  public final SootMethod sootMethod;
  public final ShimpleBody shimpleBody;
  private final ExceptionalBlockGraph basicBlockGraph;
  private final DominatorTree<Block> dominatorTree;
  private final DominatorTree<Block> postDominatorTree;
  private final HashMap<Block, ArrayList<Unit>> blockStmts;
  private final HashMap<Value, Unit> valueToDefStmt;
  private final HashMap<Value, ArrayList<Unit>> valueToUseStmts;
  private final HashMap<Unit, Block> stmtToBlock;
  public final ArrayList<Unit> statements;
  public final HashMap<Unit, Integer> stmtToIndex;
  public final ArrayList<ParameterRef> parameterRefs;
  public boolean canPrint;
  public JAssignStmt getAssignStmtForBci(int bci) {return bciToJAssignStmt.get(bci);}
  
  private ShimpleMethod(BciToJAssignStmt bciToJAssignStmt, SootMethod sootMethod, ShimpleBody shimpleBody) {
    this.bciToJAssignStmt = bciToJAssignStmt;
    this.sootMethod       = sootMethod;
    this.shimpleBody      = shimpleBody;

    canPrint = false;

    HashMap<Block, ArrayList<Unit>> blockStmts       = new HashMap<>();
    HashMap<Value, Unit> valueToDefStmt              = new HashMap<>();
    HashMap<Unit, Block> stmtToBlock                 = new HashMap<>();
    
    if (shimpleBody != null) {
      // for (Unit b : shimpleBody.getUnits()) {
      //   if (canPrint) {
      //     Utils.debugPrintln(b.toString());
      //   }
      // }

      ExceptionalBlockGraph basicBlockGraph            = new ExceptionalBlockGraph(shimpleBody);
      HashMap<Value, ArrayList<Unit>> valueToUseStmts  = new HashMap<>();

      for (Block block : basicBlockGraph.getBlocks()) {
        ArrayList<Unit> stmts = new ArrayList<Unit>();
        blockStmts.put(block, stmts);
        Iterator<Unit> unitIter = block.iterator();
        while (unitIter.hasNext()) {
          Unit unit = unitIter.next();
          for (ValueBox def : unit.getDefBoxes()) {
            Utils.debugAssert(!valueToDefStmt.containsKey(def.getValue()), "value already in map");
            valueToDefStmt.put(def.getValue(), unit);

            //Also update the valeuToUseStmts with defs
            if (!valueToUseStmts.containsKey(def.getValue())) {
              valueToUseStmts.put(def.getValue(), new ArrayList<>());
            }
          }
          stmts.add(unit);
          stmtToBlock.put(unit, block);

          for (ValueBox used : unit.getUseBoxes()) {
            if (!valueToUseStmts.containsKey(used.getValue())) {
              valueToUseStmts.put(used.getValue(), new ArrayList<>());
            }
             
            valueToUseStmts.get(used.getValue()).add(unit);
          }
        }
      }
    
      this.basicBlockGraph = basicBlockGraph;
      this.valueToUseStmts = valueToUseStmts;
      this.dominatorTree = new DominatorTree<>(new MHGDominatorsFinder<>(basicBlockGraph));
      this.postDominatorTree = new DominatorTree<>(new MHGPostDominatorsFinder<>(basicBlockGraph));
      this.parameterRefs = new ArrayList<>();
      for (Value param : shimpleBody.getParameterRefs()) {
        Utils.debugAssert(param instanceof ParameterRef, "sanity");
        this.parameterRefs.add((ParameterRef)param);
      }
      
      this.statements      = new ArrayList<>();
      this.stmtToIndex     = new HashMap<>();

      Iterator<Unit> iter  = shimpleBody.getUnits().iterator();
      while (iter.hasNext()) {
        Unit stmt = iter.next();
        this.stmtToIndex.put(stmt, this.statements.size());
        this.statements.add(stmt);
        // if (fullname().contains("org.apache.lucene.store.FSDirectory.getDirectory(")) {
        //   Utils.debugPrintln(this.statements.size() + "   " + stmt.toString());
        // }
      }
    } else {
      this.basicBlockGraph   = null;
      this.dominatorTree     = null;
      this.postDominatorTree = null;
      this.parameterRefs     = null;
      this.valueToUseStmts   = null;
      this.statements        = null;
      this.stmtToIndex       = null;
    }

    this.blockStmts      = blockStmts;
    this.valueToDefStmt  = valueToDefStmt;
    this.stmtToBlock     = stmtToBlock;
  }

  public Block getBlockForStmt(Unit stmt) {
    return stmtToBlock.get(stmt);
  }

  public Block getBlockForBci(int bci) {
    return stmtToBlock.get(getAssignStmtForBci(bci));
  }

  public boolean isEventInPathFromBlock(Block block, HeapEvent event) {
    Block eventBlock = getBlockForBci(event.bci);
    Queue<Block> q = new LinkedList<>();
    Set<Block> visited = new HashSet<>();

    q.add(block);
    while (!q.isEmpty()) {
      Block b = q.remove();
      if (visited.contains(b)) continue;
      if (b == eventBlock) return true;

      visited.add(b);
      for (Block succ : b.getSuccs()) {
        if (isDominator(succ, b)) {
          Utils.debugPrintf("found back edge: %s -> %s", succ.getIndexInMethod(), b.getIndexInMethod());
          continue;
        }
        q.add(succ);
      }
    }

    return false;
  }

  public boolean mayCallMethodInPathFromBlock(Block block, SootMethod method) {
    return mayCallMethodInPathFromBlock(block, ParsedMethodMap.v().getOrParseToShimple(method));
  }

  public boolean mayCallMethodInPathFromBlock(Block block, ShimpleMethod method) {
    Queue<Block> q = new LinkedList<>();
    Set<Block> visited = new HashSet<>();

    q.add(block);
    while (!q.isEmpty()) {
      Block b = q.remove();
      if (visited.contains(b)) continue;
      
      Iterator<Unit> stmtIter = b.iterator();
      Utils.debugPrintln(b.getIndexInMethod());
      while (stmtIter.hasNext()) {
        Unit stmt = stmtIter.next();
        for (ValueBox val : stmt.getUseBoxes()) {
          if (val.getValue() instanceof InvokeExpr) {
            if (ClassHierarchyAnalysis.v().mayCallInExpr(ClassHierarchyGraph.v(), this, val.getValue(), method))
              return true;
          }
        }

        //TODO: If a heap event bytecode is in this block then this path is not taken
      }
      visited.add(b);
      for (Block succ : b.getSuccs()) {
        if (!isDominator(succ, b)) {
          //Should not consider loop
          q.add(succ);
        }
      }
    }
    
    return false;
  }

  private ArrayList<DominatorNode<Block>> pathToRoot(DominatorNode<Block> node) {
    ArrayList<DominatorNode<Block>> pathToRoot = new ArrayList<>();
    while(node != null) {
      pathToRoot.add(node);
      node = node.getParent();
    }
    
    return pathToRoot;
  }

  private String printTree(DominatorTree<Block> tree) {
    StringBuilder builder = new StringBuilder();

    Queue<DominatorNode<Block>> queue = new LinkedList<>();
    queue.addAll(tree.getHeads());

    while (!queue.isEmpty()) {
      DominatorNode<Block> b = queue.remove();
      builder.append(b.getGode().getIndexInMethod() + " --> " + "{");
      for(DominatorNode<Block> child : b.getChildren()) {
        builder.append(child.getGode().getIndexInMethod() + ", ");
        queue.add(child);
      }
      builder.append("}\n");
    }

    return builder.toString();
  }

  private void allPathBetweenNodes(Block start, Block dest, ArrayList<Block> path,
                                   HashSet<Block> visited,
                                   HashMap<Block, ArrayList<Block>> allPaths) {
    // Mark the current node and store it in path[]
    visited.add(start);
    path.add(start);
    // If current vertex is same as destination, then print
    // current path[]
    if (start == dest) {
      ArrayList<Block> _path = new ArrayList<Block>();
      for (Block n : path) {
        _path.add(n);
      }
      allPaths.put(dest, _path);
    } else { 
      // If current vertex is not destination
      // Recur for all the vertices adjacent to current
      // vertex
      for (Block succ : start.getSuccs()) {
        if (!visited.contains(succ) && !isDominator(succ, start)) {
          allPathBetweenNodes(succ, dest, path, visited, allPaths);
        }
      }
    }
    
    // Remove current vertex from path[] and mark it as
    // unvisited
    path.remove(path.size() - 1);
    visited.remove(start);
  }

  private HashMap<Block, ArrayList<Block>> pathToExits(Block start) {
    HashSet<Block> exits = new HashSet<>();

    HashSet<Block> visited = new HashSet<>();
    Stack<Block> stack = new Stack<>();

    stack.add(start);

    while (!stack.isEmpty()) {
      Block b = stack.pop();
      if (visited.contains(b)) continue;
      visited.add(b);
      
      if (b.getSuccs().size() == 0) {
        if (!Utils.blockHasThrowStmt(b))
          exits.add(b);
      }
      
      stack.addAll(b.getSuccs());
    }
    
    HashMap<Block, ArrayList<Block>> allPaths = new HashMap<>();
    ArrayList<Block> path = new ArrayList<>();
    for (Block exit : exits) {
      visited.clear();
      allPathBetweenNodes(start, exit, path, visited, allPaths);
    }

    for (Map.Entry<Block, ArrayList<Block>> entry : allPaths.entrySet()) {
      String o = entry.getKey().getIndexInMethod() + "-> " + start.getIndexInMethod() + ": [";
      for (Block node : entry.getValue()) {
        o += node.getIndexInMethod() + ", ";
      }
      Utils.debugPrintln(o+"]");
    }
    return allPaths;
  }

  public Block findLCAInPostDom(Block block1, Block block2, ArrayList<Block> blockToExit1, ArrayList<Block> blockToExit2) {
    HashMap<Block, ArrayList<Block>> paths1 = pathToExits(block1);
    HashMap<Block, ArrayList<Block>> paths2 = pathToExits(block2);
    
    //Reverse all the paths
    for (Map.Entry<Block, ArrayList<Block>> entry : paths1.entrySet()) {
      Collections.reverse(entry.getValue());
    }

    for (Map.Entry<Block, ArrayList<Block>> entry : paths2.entrySet()) {
      Collections.reverse(entry.getValue());
    }

    for (Map.Entry<Block, ArrayList<Block>> entry : paths1.entrySet()) {
      String o = entry.getKey().getIndexInMethod() + "-> " + block1.getIndexInMethod() + ": [";
      for (Block node : entry.getValue()) {
        o += node.getIndexInMethod() + ", ";
      }
      Utils.debugPrintln(o+"]");
    }

    for (Map.Entry<Block, ArrayList<Block>> entry : paths2.entrySet()) {
      String o = entry.getKey().getIndexInMethod() + "-> " + block2.getIndexInMethod() + ": [";
      for (Block node : entry.getValue()) {
        o += node.getIndexInMethod() + ", ";
      }
      Utils.debugPrintln(o+"]");
    }
    Utils.debugPrintln("find lca for " + block1.getIndexInMethod() + " " + block2.getIndexInMethod());
    boolean hasCommonExit = false;
    //For the same exits get the common node
    for (Block exit1 : paths1.keySet()) {
      if (paths2.containsKey(exit1)) {
        Utils.debugPrintln("both contains " + exit1.getIndexInMethod());
        hasCommonExit = true;
        ArrayList<Block> path1 = paths1.get(exit1);
        ArrayList<Block> path2 = paths2.get(exit1);

        int minLength = Math.min(path1.size(), path2.size());
        for (int i = 0; i < minLength; i++) {
          Utils.debugPrintln(path1.get(i).getIndexInMethod() + " == " + path2.get(i).getIndexInMethod());
          if (path1.get(i) != path2.get(i)) {
            Utils.debugPrintln(path1.get(i));
            Utils.debugPrintln(path2.get(i));

            if (blockToExit1 != null)
              for (int j = path1.size() - 1; j >= i; j--) {
                blockToExit1.add(path1.get(j));
              }
            
            if (blockToExit2 != null)
              for (int j = path2.size() - 1; j >= i; j--) {
                blockToExit2.add(path2.get(j));
              }

            return path2.get(i-1);
          }
        }

        if (path1.get(minLength - 1) == path2.get(minLength - 1)) {
          if (blockToExit1 != null)
            for (int j = path1.size() - 1; j >= minLength - 1; j--) {
              blockToExit1.add(path1.get(j));
            }

          if (blockToExit2 != null)
            for (int j = path2.size() - 1; j >= minLength - 1; j--) {
              blockToExit2.add(path2.get(j));
            }

          return path1.get(minLength - 1);
        }
      }
    }

    if (paths1.size() == 0) {
      return block2;
    } else if (paths2.size() == 0) {
      return block1;
    }

    Utils.debugPrintln(this.basicBlockGraph);
    // Utils.debugAssert(false, "sanity");
    return null;
  }

  public Block getStartBlock() {
    if (basicBlockGraph == null) {
      return null;
    }
    List<Block> heads = basicBlockGraph.getHeads();
    Utils.debugAssert(heads.size() == 1, "");
    return heads.get(0);
  }

  public boolean mayCallInPath(Block start, ArrayList<Block> path, boolean falseOnHeapEventBci) {
    if (path.size() > 0) {
      
      for (Block b : path) {
        Iterator<Unit> stmtIter = b.iterator();
  
        while (stmtIter.hasNext()) {
          Unit stmt = stmtIter.next();
          Utils.debugPrintln(stmt);
          for (ValueBox valBox : stmt.getUseBoxes()) {
            Value val = valBox.getValue();
            if (val instanceof InvokeExpr && 
                Utils.methodToCare(((InvokeExpr)val).getMethodRef().resolve())) {
              return true;
            } else if (val instanceof StaticFieldRef) {
              SootClass klass = ((StaticFieldRef)val).getFieldRef().declaringClass();            
              ShimpleMethodList clinits = Utils.getAllStaticInitializers(klass);
              ShimpleMethod unexecClinit = clinits.nextUnexecutedStaticInit();
              if (unexecClinit != null) {
                return true;
              }
              return false;
            } else if (falseOnHeapEventBci && Utils.canStmtUpdateHeap(stmt)) {
              return false;
            }
          }
        }
      }
      
      return false;
    } else {
      Queue<Block> q = new LinkedList<>();
      Set<Block> visited = new HashSet<>();

      q.add(start);

      while (!q.isEmpty()) {
        Block b = q.remove();
        if (visited.contains(b)) continue;
        
        Iterator<Unit> stmtIter = b.iterator();

        while (stmtIter.hasNext()) {
          Unit stmt = stmtIter.next();
          Utils.debugPrintln(stmt);
          for (ValueBox valBox : stmt.getUseBoxes()) {
            Value val = valBox.getValue();
            if (val instanceof InvokeExpr && 
                Utils.methodToCare(((InvokeExpr)val).getMethodRef().resolve())) {
              return true;
            } else if (val instanceof StaticFieldRef) {
              SootClass klass = ((StaticFieldRef)val).getFieldRef().declaringClass();            
              ShimpleMethodList clinits = Utils.getAllStaticInitializers(klass);
              ShimpleMethod unexecClinit = clinits.nextUnexecutedStaticInit();
              if (unexecClinit != null) {
                return true;
              }
              return false;
            } else if (falseOnHeapEventBci && Utils.canStmtUpdateHeap(stmt)) {
              return false;
            }
          }
        }

        visited.add(b);
        for (Block succ : b.getSuccs()) {
          if (!isDominator(succ, b)) {
            //Should not consider loop
            q.add(succ);
          }
        }
      }
    }
    
    return false;
  }

  public boolean isDominator(Block parent, Block child) {
    var childNode = this.dominatorTree.getDode(child);
    var parentNode = this.dominatorTree.getDode(parent);
    
    return this.dominatorTree.isDominatorOf(parentNode, childNode);
  }

  public HashMap<Value, VariableValues> initVarValues(Value calleeExpr, HashMap<Value, VariableValues> callerVariableValues) {
    HashMap<Value, VariableValues> allVariableValues = new HashMap<>();
    
    if (basicBlockGraph != null) {
      for (Block block : basicBlockGraph.getBlocks()) {
        Iterator<Unit> unitIter = block.iterator();
        while (unitIter.hasNext()) {
          Unit unit = unitIter.next();
          for (ValueBox def : unit.getDefBoxes()) {
            allVariableValues.put(def.getValue(), new VariableValues(def.getValue(), unit));
          }
        }
      }

      if (calleeExpr != null) {
        // utils.Utils.debugPrintln(invokeExpr.toString() + "   " + utils.Utils.methodFullName(sootMethod));
        // Utils.debugPrintln(invokeStmt.toString() + "   " + m.toString());

        if (sootMethod.isStaticInitializer()) {
          //There are no parameters and this variable in a static initializer
        } else {
          if (calleeExpr instanceof InvokeExpr) {
            InvokeExpr invokeExpr = (InvokeExpr)calleeExpr;
            for (int i = 0; i < parameterRefs.size(); i++) {
              ParameterRef param = parameterRefs.get(i);
              Value arg = invokeExpr.getArg(i);
              // utils.Utils.debugPrintln(arg.toString() + " has values " + callerVariableValues.get(arg));
              if (callerVariableValues.containsKey(arg))
                allVariableValues.put(param, callerVariableValues.get(arg));
            }

            if (!sootMethod.isStatic()) {
              //Fill the "this" variable value from parameter of an instance method
              Value base = ((AbstractInstanceInvokeExpr)invokeExpr).getBase();
              
              utils.Utils.debugAssert(!(invokeExpr instanceof JStaticInvokeExpr), "sanity");
              Unit thisUnit = shimpleBody.getThisUnit();
              utils.Utils.debugAssert(thisUnit instanceof JIdentityStmt, "sanity");
              JIdentityStmt thisIdentityStmt = (JIdentityStmt)thisUnit;
              allVariableValues.put(thisIdentityStmt.getRightOp(), callerVariableValues.get(base));
              // if (allVariableValues.get(thisIdentityStmt.getRightOp()) == null) {
              //   // utils.Utils.debugPrintln(thisUnit.toString());
              // }
              //TODO: Pass parameters as a reference or as a copy?
              allVariableValues.put(thisIdentityStmt.getLeftOp(), allVariableValues.get(thisIdentityStmt.getRightOp()));
            }
          }
        }
      }

      //For all statements using these parameters set their values too
      Queue<Value> q = new LinkedList<>();
      for (Value variable : allVariableValues.keySet()) {
        // if (canPrint)
        //   Utils.debugPrintln("381: " + variable.toString());
        if (allVariableValues.get(variable).size() > 0)
          q.add(variable);
      }


      //TODO: do it until there is no change?
      Set<Value> visited = new HashSet<>();

      while(!q.isEmpty()) {
        Value variable = q.remove();
        if (visited.contains(variable)) continue;
        visited.add(variable);
        if (allVariableValues.get(variable).size() == 0) continue;
        // if (canPrint)
        //   Utils.debugPrintln("388: " + variable.toString() + " " + variable.getClass());
        for (Unit use : valueToUseStmts.get(variable)) {
          Utils.debugAssert(use instanceof Unit, "not of Unit " + use.toString() + " " + variable.toString());
          // if (canPrint)
          //   Utils.debugPrintln("400: " + use.toString());    
          propogateValues(allVariableValues, use);
      
          for (ValueBox def : use.getDefBoxes()) {
            // if (canPrint)
            //   Utils.debugPrintln("394: " + def.getValue().toString() + " " + allVariableValues.get(def.getValue()).size());
            q.add(def.getValue());
          }
        }
      }


    }

    return allVariableValues;
  }

  public ArrayList<Value> getCallExprs() {
    ArrayList<Value> invokes = new ArrayList<>();
    for (Unit stmt : stmtToBlock.keySet()) {
      for (ValueBox valBox : stmt.getUseBoxes()) {
        if (valBox.getValue() instanceof InvokeExpr ||
            valBox.getValue() instanceof StaticFieldRef ||
            valBox.getValue() instanceof JNewExpr) {
          //Before a reference to static fields and invoking a static method
          //<clinit>()V for the class may be called
          invokes.add(valBox.getValue());
        }
      }
    }

    return invokes;
  }
  public static ShimpleMethod v(SootMethod method) {
    if (method.getSource() == null)
      return new ShimpleMethod(null, method, null);
    ShimpleMethodSource sm = new ShimpleMethodSource(method.getSource());
    ShimpleBody sb = (ShimpleBody)sm.getBody(method, "");
    BciToJAssignStmt bciToJAssignStmt = buildBytecodeIndexToInsnMap(method, sb);
    return new ShimpleMethod(bciToJAssignStmt, method, sb);
  }

  private Block blockForUnit(Unit unit) {
    for (Block block : basicBlockGraph.getBlocks()) {
      Iterator<Unit> unitIter = block.iterator();
      while (unitIter.hasNext()) {
        Unit unit1 = unitIter.next();
        if (unit1 == unit) return block;
      }
    }

    return null;
  }

  private VariableValues obtainVariableValues(HashMap<Value, VariableValues> allVariableValues,
                                              Unit stmt, Value val) {
    if (val instanceof JNewExpr) {
      return null;
    } else if (val instanceof JNewArrayExpr) {
      return null;// Utils.debugAssert(false, stmt.toString());
    } else if (val instanceof JNewMultiArrayExpr) {
      Utils.debugAssert(false, stmt.toString());
    } else if (val instanceof BinopExpr) {
      utils.Utils.debugAssert(!(val.getType() instanceof RefLikeType), stmt.toString());
      // VariableValues vals = new VariableValues(val, stmt);
      // Value v1 = ((BinopExpr)val).getOp1();
      // Value v2 = ((BinopExpr)val).getOp2();
      // return vals;
    } else if (val instanceof UnopExpr) {
      utils.Utils.debugAssert(!(val.getType() instanceof RefLikeType), stmt.toString());
      // VariableValues vals = new VariableValues(val, stmt);
      // vals.add(new VariableValue(val.getType()));
      // return vals;
    } else if (val instanceof JCastExpr) {
      if (val.getType() instanceof RefLikeType) {
        VariableValues vals = new VariableValues(val, stmt);
        Value op = ((JCastExpr)val).getOp();
        for (var v : allVariableValues.get(op)) {
          vals.add(v);
        }
        return vals;
      }
      return null;
    } else if (val instanceof JInstanceOfExpr) {
      Utils.debugAssert(false, stmt.toString());
    } else if (val instanceof JStaticInvokeExpr) {
      return null;
    } else if (val instanceof JVirtualInvokeExpr) {
      return null;
    } else if (val instanceof JSpecialInvokeExpr) {
      return null;
    } else if (val instanceof JInstanceFieldRef) {
      VariableValues vals = new VariableValues(val, stmt);
      Value base = ((JInstanceFieldRef)val).getBase();
      SootFieldRef field = ((JInstanceFieldRef)val).getFieldRef();
      VariableValues baseVals = allVariableValues.get(base);
      
      for (JavaHeapElem baseVal : baseVals) {
        utils.Utils.debugAssert(baseVal instanceof JavaObject, "");
        vals.add(((JavaObject)baseVal).getField(field.name()));
      }
      return vals;
    } else if (val instanceof JInterfaceInvokeExpr) {
      return null;
    } else if (val instanceof SPhiExpr) {
      //TODO: do this based on the previous block
      SPhiExpr phi = (SPhiExpr)val;
      VariableValues vals = new VariableValues(val, stmt);
      for (ValueUnitPair pair : phi.getArgs()) {
        vals.addAll(allVariableValues.get(pair.getValue()));
      }
      return vals;
    } else if (val instanceof SPiExpr) {
      Utils.debugAssert(false, stmt.toString());
    } else if (val instanceof JimpleLocal) {
      VariableValues vals = new VariableValues(val, stmt);
      vals.addAll(allVariableValues.get(val));
      return vals;
    } else if (val instanceof Constant) {
      if (val.getType() instanceof RefType && 
          ((RefType)val.getType()).getSootClass().getName().equals("java.lang.String")) {
        VariableValues vals = new VariableValues(val, stmt);
        vals.add(JavaHeap.v().createNewObject(((RefType)val.getType())));  
      } else if (val instanceof NullConstant) {
        VariableValues vals = new VariableValues(val, stmt);
        vals.add(null);  
      } else {
        utils.Utils.debugAssert(!(val.getType() instanceof RefLikeType), stmt.toString() + " " + val.getClass());
      }

      // VariableValues vals = new VariableValues(val, stmt);
      // //TODO:
      // vals.add(new VariableValue(val.getType()));
      // return vals;
    } else if (val instanceof StaticFieldRef) {
      VariableValues vals = new VariableValues(val, stmt);
      vals.add(StaticFieldValues.v().get(((StaticFieldRef)val).getField()));
      return vals;
    } else if (val instanceof JArrayRef) {
      JArrayRef arrayRef = (JArrayRef)val;
      Utils.debugPrintln(arrayRef.getType());
      if (arrayRef.getType() instanceof RefLikeType) {
        Utils.debugAssert(false, "");
      } else {
        return null;
      }
    } else {
      Utils.debugAssert(false, "Unsupported Jimple expr " + val.getClass() + "'" + stmt.toString() + "'");
    }
    return null;
  }

  public void propogateValues(HashMap<Value, VariableValues> allVariableValues,
                               Unit stmt) {
    if (stmt instanceof JIdentityStmt) {
      if (((JIdentityStmt)stmt).getRightOp() instanceof CaughtExceptionRef) {
        return;
      } else if (!sootMethod.isStatic() && stmt == shimpleBody.getThisUnit()) {
        //Ignore because this is already assigned
      } else if (((JIdentityStmt)stmt).getRightOp() instanceof ParameterRef) {
        Value leftVal = ((JIdentityStmt)stmt).getLeftOp();
        Value rightVal =((JIdentityStmt)stmt).getRightOp();
        VariableValues valsForLeft = allVariableValues.get(rightVal);
        if (valsForLeft != null) {
          allVariableValues.put(leftVal, valsForLeft);
          // blockVarVals.put(stmt, valsForLeft);
        }
      } else {
        Utils.debugAssert(false, "%s %s %s\n", stmt.toString(), stmt.getClass(), ((JIdentityStmt)stmt).getRightOp().getClass());
      }
    } else if (stmt instanceof JAssignStmt) {

      Value leftVal = ((JAssignStmt)stmt).getLeftOp();
      Value rightVal =((JAssignStmt)stmt).getRightOp();
      VariableValues valsForLeft = obtainVariableValues(allVariableValues, stmt, rightVal);
      if (valsForLeft != null) {
        allVariableValues.put(leftVal, valsForLeft);
        // blockVarVals.put(stmt, valsForLeft);
      }

    } else if (stmt instanceof JEnterMonitorStmt) {
      // Utils.debugAssert(false, stmt.toString());
    } else if (stmt instanceof JExitMonitorStmt) {
      // Utils.debugAssert(false, stmt.toString());
    } else if (stmt instanceof JReturnStmt) {
      Utils.debugLog("613: To handle return");
    } else if (stmt instanceof JThrowStmt) {
      Utils.debugLog("613: To handle throw");
    } else if (stmt instanceof JLookupSwitchStmt) {
      Utils.debugAssert(false, stmt.toString());
    } else if (stmt instanceof JTableSwitchStmt) {
      Utils.debugAssert(false, stmt.toString());
    } else if (stmt instanceof JGotoStmt) {
      return;
    } else if (stmt instanceof JIfStmt) {
      Stmt target = ((JIfStmt)stmt).getTarget();
      Value cond = ((JIfStmt)stmt).getCondition();
      // System.out.println("398: " + val);
      // Unit u = ((JGotoStmt)target).getTarget();
      // System.out.println("402: " + u.toString());
      // System.out.println("403: " + stmtToBlock.get(u));
      // for (Unit uu : stmtToBlock.keySet()) {
      //   System.out.print(uu.toString() + " "); //stmtToBlock.get(uu).getIndexInMethod()
      // }
    } else if (stmt instanceof JInvokeStmt) {
      obtainVariableValues(allVariableValues, stmt, ((JInvokeStmt)stmt).getInvokeExpr());
    } else if (stmt instanceof JNopStmt) {
      Utils.debugAssert(false, stmt.toString());
    } else if (stmt instanceof JReturnVoidStmt) {
      return;
    } else if (stmt instanceof JRetStmt) {
      Utils.debugAssert(false, stmt.toString());
    } else {
      Utils.debugAssert(false, "Unhandled statement " + stmt.getClass());
    }
  }

  // private void propogateValues(HashMap<Value, VariableValues> allVariableValues,
  //                              Block block, boolean fwdOrBckwd) {
  //   ArrayList<Unit> stmts = blockStmts.get(block);
  //   for (Unit unit : stmts) {
  //     propogateValues(allVariableValues, unit);
  //     // obtainVariableValues(val, useToVals);
  //   }
  // }

  // public void propogateValuesToSucc(HashMap<Value, VariableValues> allVariableValues, Block block) {
  //   Queue<Block> q = new LinkedList<Block>();
  //   q.add(block);
  //   HashSet<Block> visited = new HashSet<>();
  //   while (!q.isEmpty()) {
  //     Block b = q.remove();
  //     if (visited.contains(b)) continue;
  //     for (var succ : b.getSuccs()) {
  //       propogateValues(allVariableValues, succ, false);
  //       q.add(succ);
  //       visited.add(succ);
  //     }
  //   }
  // }

  public void updateValuesWithHeapEvent(HashMap<Value, VariableValues> allVariableValues,
                                        HeapEvent heapEvent) {
    JAssignStmt stmt = getAssignStmtForBci(heapEvent.bci);
    Block block = blockForUnit(stmt);
    short opcode = ShimpleMethod.opcodeForJAssign(stmt);
    utils.Utils.debugPrintln(stmt.toString() + "  for " + heapEvent.toString());
    //Add value of the heap event
    Value left = stmt.getLeftOp();
    Value right = stmt.getRightOp();
    VariableValues leftVals = allVariableValues.get(left);
    switch (opcode) {
      //TODO: Combine all of three cases below
      case Const.PUTFIELD: {
        leftVals.add(JavaHeap.v().get(heapEvent.srcPtr));
        if (!(right instanceof Constant)) {
          VariableValues rightVals = allVariableValues.get(right);
          rightVals.add(JavaHeap.v().get(heapEvent.srcPtr));
        }
        Utils.debugAssert(left instanceof JInstanceFieldRef, "sanity");
        Value base = ((JInstanceFieldRef)left).getBase();
        allVariableValues.get(base).add(JavaHeap.v().get(heapEvent.dstPtr));
        Utils.debugAssert(stmt.getUseBoxes().size() <= 2, "Only one use in " + stmt.toString());
        break;
      }
      case Const.AASTORE: {
        JArrayRef arrayRef = (JArrayRef)left;
        leftVals.add(JavaHeap.v().get(heapEvent.srcPtr));
        allVariableValues.get(arrayRef.getBase()).add(JavaHeap.v().get(heapEvent.dstPtr));
        if (!(right instanceof Constant)) {
          VariableValues rightVals = allVariableValues.get(right);
          rightVals.add(JavaHeap.v().get(heapEvent.srcPtr));
          //TODO: Can populate string value to src object
        }
        Utils.debugAssert(stmt.getUseBoxes().size() <= 3, "Only one use in " + stmt.toString());
        break;
      }
      case Const.PUTSTATIC: {
        StaticFieldRef staticFieldRef = (StaticFieldRef)left;
        StaticFieldValues.v().set(staticFieldRef.getFieldRef(), heapEvent.srcPtr);
        
        leftVals.add(JavaHeap.v().get(heapEvent.srcPtr));
        if (!(right instanceof Constant)) {
          VariableValues rightVals = allVariableValues.get(right);
          rightVals.add(JavaHeap.v().get(heapEvent.srcPtr));
        }
        break;
      }
      case Const.NEW: {
        Utils.debugAssert(right instanceof JNewExpr, "sanity");
        
        leftVals.add(JavaHeap.v().get(heapEvent.dstPtr));
        Utils.debugAssert(stmt.getUseBoxes().size() <= 1, "Only one use in " + stmt.toString());
        break;
      }
      case Const.NEWARRAY:
        // lvalSet.add(new ActualValue(currEvent.dstClass, currEvent.dstPtr));
        Utils.debugAssert(stmt.getUseBoxes().size() <= 1, "Only one use in " + stmt.toString());
        break;
      case Const.ANEWARRAY:
        Utils.debugAssert(stmt.getUseBoxes().size() <= 1, "Only one use in " + stmt.toString());
        break;
      case Const.MULTIANEWARRAY:
        // Utils.debugAssert(stmt.getUseBoxes().size() <= 1, "Only one use in " + stmt.toString());
        break;
      
      default:
        Utils.debugAssert(false, "not handling " + Const.getOpcodeName(opcode));
    }

    // //Propagate values inside the block
    // propogateValues(allVariableValues, block, true);

    // //Propagate values to the successors
    // propogateValuesToSucc(allVariableValues, block);
    // //Propagate values to the predecessors
  }

  public String basicBlockStr() {
    return this.basicBlockGraph.toString();
  }

  public String fullname() {
    return Utils.methodFullName(sootMethod);
  }

  public boolean isStaticInitializer() {
    return sootMethod.isStaticInitializer();
  }

  public ArrayList<Block> filterNonCatchBlocks(List<Block> blocks) {
    ArrayList<Block> nonCatchBlocks = new ArrayList<>();

    for (Block b : blocks) {
      if(b.getHead() instanceof JIdentityStmt &&
        ((JIdentityStmt)b.getHead()).getRightOp() instanceof CaughtExceptionRef) {
          continue;
      }

      nonCatchBlocks.add(b);
    }

    return nonCatchBlocks;
  }

  public String toString() {
    return fullname();
  }
}
