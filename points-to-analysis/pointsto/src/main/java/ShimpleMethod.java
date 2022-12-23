import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;

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
import soot.SootMethod;
import soot.Unit;
import soot.Value;
import soot.jimple.internal.JArrayRef;
import soot.jimple.internal.JAssignStmt;
import soot.jimple.internal.JInstanceFieldRef;
import soot.jimple.internal.JNewArrayExpr;
import soot.jimple.internal.JNewExpr;
import soot.jimple.internal.JNewMultiArrayExpr;
import soot.shimple.ShimpleBody;
import soot.shimple.ShimpleMethodSource;

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

  private static HashMap<Short, ArrayList<Integer>> createopcodeTypeToBCIndexMap(SootMethod method) {
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
            return null;
            // break;
          }
          case Const.LOOKUPSWITCH: {
            int npairs = stream.readInt();
            for (int i = 0; i < npairs; i++) {
                stream.readInt(); //Read match
                stream.readInt(); //Read jump table offset
                if (i < npairs - 1) {
                }
            }
          return null;
            // break;
          }
          case Const.RET:
            System.out.println("RET");
            return null;

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

    if (left instanceof JInstanceFieldRef) {
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
    HashMap<Short, ArrayList<Integer>> opcodeTypeToBCIndex = createopcodeTypeToBCIndexMap(method);
    if (opcodeTypeToBCIndex == null) return null;

    int[] numJExprsForOpcodeType = new int[Const.OPCODE_NAMES_LENGTH];
    Arrays.fill(numJExprsForOpcodeType, 0);
    
    BciToJAssignStmt bciToJAssignStmt = new BciToJAssignStmt();

    for (Unit u : sb.getUnits()) {
      if (u instanceof soot.jimple.internal.JAssignStmt) {
        soot.jimple.internal.JAssignStmt stmt = (soot.jimple.internal.JAssignStmt) u;
        short opcode = opcodeForJAssign(stmt);
        if (opcode != -1) {
          int bci = opcodeTypeToBCIndex.get(opcode).get(numJExprsForOpcodeType[opcode]);
          numJExprsForOpcodeType[opcode]++;
          bciToJAssignStmt.put(bci, stmt);
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
  public BciToJAssignStmt getBciToJAssignStmt() {return bciToJAssignStmt;}
  public SootMethod       getSootMethod()       {return sootMethod;}
  public ShimpleBody      getShimpleBody()      {return shimpleBody;}

  private ShimpleMethod(BciToJAssignStmt bciToJAssignStmt, SootMethod sootMethod, ShimpleBody shimpleBody) {
    this.bciToJAssignStmt = bciToJAssignStmt;
    this.sootMethod = sootMethod;
    this.shimpleBody = shimpleBody;
  }

  public static ShimpleMethod v(SootMethod method) {
    ShimpleMethodSource sm = new ShimpleMethodSource(method.getSource());
    ShimpleBody sb = (ShimpleBody)sm.getBody(method, "");
    BciToJAssignStmt bciToJAssignStmt = buildBytecodeIndexToInsnMap(method, sb);

    return new ShimpleMethod(bciToJAssignStmt, method, sb);
  }
}
