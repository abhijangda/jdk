import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Queue;
import java.util.TreeSet;
import java.util.concurrent.BlockingDeque;

import org.apache.bcel.Const;
import org.apache.bcel.classfile.Method;
import org.apache.bcel.util.ByteSequence;

import soot.BooleanType;
import soot.ByteType;
import soot.CharType;
import soot.DoubleType;
import soot.FloatType;
import soot.IntegerType;
import soot.LongType;
import soot.SootField;
import soot.SootFieldRef;
import soot.SootMethod;
import soot.Unit;
import soot.Value;
import soot.ValueBox;
import soot.jimple.BinopExpr;
import soot.jimple.CaughtExceptionRef;
import soot.jimple.StaticFieldRef;
import soot.jimple.Stmt;
import soot.jimple.UnopExpr;
import soot.jimple.internal.JArrayRef;
import soot.jimple.internal.JAssignStmt;
import soot.jimple.internal.JCastExpr;
import soot.jimple.internal.JEnterMonitorStmt;
import soot.jimple.internal.JExitMonitorStmt;
import soot.jimple.internal.JGotoStmt;
import soot.jimple.internal.JIdentityStmt;
import soot.jimple.internal.JIfStmt;
import soot.jimple.internal.JInstanceFieldRef;
import soot.jimple.internal.JInstanceOfExpr;
import soot.jimple.internal.JInterfaceInvokeExpr;
import soot.jimple.internal.JInvokeStmt;
import soot.jimple.internal.JLookupSwitchStmt;
import soot.jimple.internal.JNewArrayExpr;
import soot.jimple.internal.JNewExpr;
import soot.jimple.internal.JNewMultiArrayExpr;
import soot.jimple.internal.JNopStmt;
import soot.jimple.internal.JRetStmt;
import soot.jimple.internal.JReturnStmt;
import soot.jimple.internal.JReturnVoidStmt;
import soot.jimple.internal.JSpecialInvokeExpr;
import soot.jimple.internal.JStaticInvokeExpr;
import soot.jimple.internal.JTableSwitchStmt;
import soot.jimple.internal.JThrowStmt;
import soot.jimple.internal.JVirtualInvokeExpr;
import soot.shimple.ShimpleBody;
import soot.shimple.ShimpleMethodSource;
import soot.shimple.internal.SPhiExpr;
import soot.shimple.internal.SPiExpr;
import soot.toolkits.graph.Block;
import soot.toolkits.graph.ExceptionalBlockGraph;
import soot.toolkits.scalar.ValueUnitPair;

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
    Method bcelMethod = BCELClassCollection.v().getMethod(Main.methodFullName(method));
    Main.debugAssert(bcelMethod != null, "%s is null\n", Main.methodFullName(method));
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
    Main.debugAssert(stmt instanceof soot.jimple.internal.JAssignStmt, "");
 
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

    if (Main.DEBUG_PRINT) {
      for (short i = 0; i < Const.OPCODE_NAMES_LENGTH; i++) {
        if (i == Const.PUTFIELD || i == Const.AASTORE || i == Const.NEW || i == Const.NEWARRAY || i == Const.ANEWARRAY || i == Const.MULTIANEWARRAY) {
          int x = opcodeTypeToBCIndex.get(i).size();
          int y = numJExprsForOpcodeType[i];
          Main.debugAssert(x == y, "%d != %d\n", x, y);
        }
      }
    }

    return bciToJAssignStmt;
  }

  private final BciToJAssignStmt bciToJAssignStmt;
  private final SootMethod sootMethod;
  private final ShimpleBody shimpleBody;
  private final ExceptionalBlockGraph basicBlockGraph;
  private final HashMap<Block, ArrayList<Unit>> blockStmts;
  private final HashMap<Value, Unit> valueToDefStmt;
  private final HashMap<Unit, Block> stmtToBlock;

  private HashMap<Value, VariableValues> allVariableValues;

  public JAssignStmt getAssignStmtForBci(int bci) {return bciToJAssignStmt.get(bci);}
  public SootMethod  getSootMethod()              {return sootMethod;}
  public ShimpleBody getShimpleBody()             {return shimpleBody;}

  private ShimpleMethod(BciToJAssignStmt bciToJAssignStmt, SootMethod sootMethod, ShimpleBody shimpleBody) {
    this.bciToJAssignStmt = bciToJAssignStmt;
    this.sootMethod       = sootMethod;
    this.shimpleBody      = shimpleBody;

    ExceptionalBlockGraph basicBlockGraph            = new ExceptionalBlockGraph(shimpleBody);
    HashMap<Block, ArrayList<Unit>> blockStmts       = new HashMap<>();
    HashMap<Value, Unit> valueToDefStmt              = new HashMap<>();
    HashMap<Unit, Block> stmtToBlock                 = new HashMap<>();
    allVariableValues = new HashMap<>();

    for (Block block : basicBlockGraph.getBlocks()) {
      ArrayList<Unit> stmts = new ArrayList<Unit>();
      blockStmts.put(block, stmts);
      Iterator<Unit> unitIter = block.iterator();
      while (unitIter.hasNext()) {
        Unit unit = unitIter.next();
        for (ValueBox def : unit.getDefBoxes()) {
          allVariableValues.put(def.getValue(), new VariableValues(def.getValue(), unit));
          Main.debugAssert(!valueToDefStmt.containsKey(def.getValue()), "value already in map");
          valueToDefStmt.put(def.getValue(), unit);
        }
        stmts.add(unit);
        stmtToBlock.put(unit, block);
      }
    }

    Value thisLocal = shimpleBody.getThisLocal();
    allVariableValues.get(thisLocal).add(new VariableValue(thisLocal.getType(), VariableValue.ThisPtr));

    this.basicBlockGraph = basicBlockGraph;
    this.blockStmts      = blockStmts;
    this.valueToDefStmt  = valueToDefStmt;
    this.stmtToBlock     = stmtToBlock;
  }

  public static ShimpleMethod v(SootMethod method) {
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

  private VariableValues obtainVariableValues(Unit stmt, Value val) {
    if (val instanceof JNewExpr) {
      return null;
    } else if (val instanceof JNewArrayExpr) {
      Main.debugAssert(false, stmt.toString());
    } else if (val instanceof JNewMultiArrayExpr) {
      Main.debugAssert(false, stmt.toString());
    } else if (val instanceof BinopExpr) {
      VariableValues vals = new VariableValues(val, stmt);
      Value v1 = ((BinopExpr)val).getOp1();
      Value v2 = ((BinopExpr)val).getOp2();
      vals.add(new VariableValue(val.getType()));
      return vals;
    } else if (val instanceof UnopExpr) {
      VariableValues vals = new VariableValues(val, stmt);
      vals.add(new VariableValue(val.getType()));
      return vals;
    } else if (val instanceof JCastExpr) {
      VariableValues vals = new VariableValues(val, stmt);
      Value op = ((JCastExpr)val).getOp();
      for (VariableValue v : allVariableValues.get(op)) {
        vals.add(new VariableValue(op.getType(), v.refValue));
      }
      return vals;
    } else if (val instanceof JInstanceOfExpr) {
      Main.debugAssert(false, stmt.toString());
    } else if (val instanceof JStaticInvokeExpr) {
      return null;
    } else if (val instanceof JVirtualInvokeExpr) {
      return null;
    } else if (val instanceof JSpecialInvokeExpr) {
      boolean isspecial = ((JSpecialInvokeExpr)val).getMethod().isConstructor();
      Main.debugAssert(isspecial, "not special? " + val.toString());
      return null;
    } else if (val instanceof JInstanceFieldRef) {
      VariableValues vals = new VariableValues(val, stmt);
      Value base = ((JInstanceFieldRef)val).getBase();
      SootFieldRef field = ((JInstanceFieldRef)val).getFieldRef();
      VariableValues baseVals = allVariableValues.get(base);
      for (VariableValue baseVal : baseVals) {
        vals.add(new FieldRefValue(baseVal, field));
      }
      return vals;
    } else if (val instanceof JInterfaceInvokeExpr) {
      Main.debugAssert(false, stmt.toString());
    } else if (val instanceof SPhiExpr) {
      SPhiExpr phi = (SPhiExpr)val;
      VariableValues vals = new VariableValues(val, stmt);
      for (ValueUnitPair pair : phi.getArgs()) {
        vals.addAll(allVariableValues.get(pair.getValue()));
      }
      return vals;
    } else if (val instanceof SPiExpr) {
      Main.debugAssert(false, stmt.toString());
    } else {
      Main.debugAssert(false, "Unsupported Jimple expr " + val.getClass() + "'" + stmt.toString() + "'");
    }

    return null;
  }

  private void propogateValues(Unit stmt, HashMap<Value, VariableValues> varVals) {
    if (stmt instanceof JIdentityStmt) {
      if (((JIdentityStmt)stmt).getRightOp() instanceof CaughtExceptionRef) {
        return;
      } else {
        Main.debugAssert(false, "");
      }
    } else if (stmt instanceof JAssignStmt) {
      Value leftVal = ((JAssignStmt)stmt).getLeftOp();
      Value rightVal =((JAssignStmt)stmt).getRightOp();
      VariableValues valsForLeft = obtainVariableValues(stmt, rightVal);
      if (valsForLeft != null) {
        allVariableValues.put(leftVal, valsForLeft);
        // blockVarVals.put(stmt, valsForLeft);
      }
    } else if (stmt instanceof JEnterMonitorStmt) {
      Main.debugAssert(false, stmt.toString());
    } else if (stmt instanceof JExitMonitorStmt) {
      Main.debugAssert(false, stmt.toString());
    } else if (stmt instanceof JReturnStmt) {
      Main.debugAssert(false, stmt.toString());
    } else if (stmt instanceof JThrowStmt) {
      Main.debugAssert(false, stmt.toString());
    } else if (stmt instanceof JLookupSwitchStmt) {
      Main.debugAssert(false, stmt.toString());
    } else if (stmt instanceof JTableSwitchStmt) {
      Main.debugAssert(false, stmt.toString());
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
      obtainVariableValues(stmt, ((JInvokeStmt)stmt).getInvokeExpr());
    } else if (stmt instanceof JNopStmt) {
      Main.debugAssert(false, stmt.toString());
    } else if (stmt instanceof JReturnVoidStmt) {
      return;
    } else if (stmt instanceof JRetStmt) {
      Main.debugAssert(false, stmt.toString());
    } else if (stmt instanceof JReturnStmt) {
      Main.debugAssert(false, stmt.toString());
    } else {
      Main.debugAssert(false, "Unhandled statement " + stmt.getClass());
    }
  }

  private void propogateValues(Block block, boolean fwdOrBckwd) {
    ArrayList<Unit> stmts = blockStmts.get(block);
    for (Unit unit : stmts) {
      propogateValues(unit, allVariableValues);
      // obtainVariableValues(val, useToVals);
    }
  }

  private void propogateValuesToSucc(Block block) {
    Queue<Block> q = new LinkedList<Block>();
    q.add(block);
    HashSet<Block> visited = new HashSet<>();
    while (!q.isEmpty()) {
      Block b = q.remove();
      if (visited.contains(b)) continue;
      visited.add(b);
      for (var succ : b.getSuccs()) {
        propogateValues(succ, false);
        q.add(succ);
      }
    }
  }

  public void updateValuesWithHeapEvent(HeapEvent heapEvent) {
    System.out.println(heapEvent.toString());
    System.out.print(basicBlockGraph.toString());
    
    JAssignStmt stmt = getAssignStmtForBci(heapEvent.bci);
    Block block = blockForUnit(stmt);
    short opcode = ShimpleMethod.opcodeForJAssign(stmt);

    //Add value of the heap event
    switch (opcode) {
      case Const.PUTFIELD:
        // lvalSet.add(new ActualValue(currEvent.dstClass_, currEvent.dstPtr_));
        // rvalSet.add(new ActualValue(currEvent.srcClass, currEvent.srcPtr));
        Main.debugAssert(stmt.getUseBoxes().size() <= 2, "Only one use in " + stmt.toString());
        break;
      case Const.AASTORE:
        // lvalSet.add(new ActualValue(currEvent.dstClass_, currEvent.dstPtr_));
        // rvalSet.add(new ActualValue(currEvent.srcClass, currEvent.srcPtr));
        Main.debugAssert(stmt.getUseBoxes().size() <= 2, "Only one use in " + stmt.toString());
        break;
      case Const.PUTSTATIC:
        break;
      case Const.NEW: {
        Main.debugAssert(stmt.getRightOp() instanceof JNewExpr, "sanity");
        VariableValues varVals = allVariableValues.get(stmt.getLeftOp());
        varVals.add(new VariableValue(heapEvent.dstClass, heapEvent.dstPtr));
        Main.debugAssert(stmt.getUseBoxes().size() <= 1, "Only one use in " + stmt.toString());
        break;
      }
      case Const.NEWARRAY:
        // lvalSet.add(new ActualValue(currEvent.dstClass, currEvent.dstPtr));
        Main.debugAssert(stmt.getUseBoxes().size() <= 1, "Only one use in " + stmt.toString());
        break;
      case Const.ANEWARRAY:
        Main.debugAssert(stmt.getUseBoxes().size() <= 1, "Only one use in " + stmt.toString());
        break;
      case Const.MULTIANEWARRAY:
        // Main.debugAssert(stmt.getUseBoxes().size() <= 1, "Only one use in " + stmt.toString());
        break;
      
      default:
        Main.debugAssert(false, "not handling " + Const.getOpcodeName(opcode));
    }

    //Propagate values inside the block
    propogateValues(block, true);

    //Propagate values to the successors
    propogateValuesToSucc(block);

    //Propagate values to the predecessors
  }
}
