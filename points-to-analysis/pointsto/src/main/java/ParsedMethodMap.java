import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

import org.apache.bcel.Const;
import org.apache.bcel.classfile.Method;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.util.Textifier;

import soot.Body;
import soot.BooleanType;
import soot.ByteType;
import soot.CharType;
import soot.DoubleType;
import soot.FloatType;
import soot.IntegerType;
import soot.LongType;
import soot.SootMethod;
import soot.Unit;
import soot.UnitBox;
import soot.Value;
import soot.ValueBox;
import soot.asm.AsmMethodSource;
import soot.jimple.FieldRef;
import soot.jimple.JimpleBody;
import soot.jimple.internal.JArrayRef;
import soot.jimple.internal.JAssignStmt;
import soot.jimple.internal.JInstanceFieldRef;
import soot.jimple.internal.JNewArrayExpr;
import soot.jimple.internal.JNewExpr;
import soot.jimple.internal.JNewMultiArrayExpr;
import soot.jimple.internal.StmtBox;
import soot.jimple.parser.Parse;
import soot.shimple.Shimple;
import soot.shimple.ShimpleBody;
import soot.shimple.ShimpleMethodSource;

public class ParsedMethodMap extends HashMap<SootMethod, ShimpleBody> {
  private ParsedMethodMap() {
    super();
  }

  private static ParsedMethodMap map = null;
  public static ParsedMethodMap v() {
    if (map == null) {
      map = new ParsedMethodMap();
    }

    return map;
  }

  //Need to use Apache BCEL to find bytecode size of a method.
  public static int opcodeSize(int opcode) {
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

  public boolean isPrimitiveType(soot.Type sootType) {
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
  public void buildBytecodeIndexToInsnMap(SootMethod method) {
    Method bcelMethod = BCELClassCollection.v().getMethod(Main.methodFullName(method));
    Main.debugAssert(bcelMethod != null, "%s is null\n", Main.methodFullName(method));
    byte[] code = bcelMethod.getCode().getCode();
    int[] bcIndexToOpcode = new int[code.length];
    HashMap<Short, ArrayList<Integer>> opcodeNumToBCIndex = new HashMap<>();
    for (short i = 0; i < Const.OPCODE_NAMES_LENGTH; i++) {
      opcodeNumToBCIndex.put(i, new ArrayList<Integer>());
    }
    // int[] bcIndexToNum = new int[code.length];
    // int[] bcNumToIndex = new int[code.length];
    //build bytecode index to bytecode number map 
    // HashMap<Integer, Integer> numHeapEventCodes = new HashMap<>();
    int[] numCodesForType = new int[Const.OPCODE_NAMES_LENGTH];
    int[] numJimpleExprsForType = new int[Const.OPCODE_NAMES_LENGTH];
    for (int i = 0; i < numCodesForType.length; i++) {
      numCodesForType[i] = numJimpleExprsForType[i] = 0;
    }
    for (int idx = 0; idx < code.length;) {
      short opcode = (short)(code[idx] & 0xff);
      if (opcode == Const.TABLESWITCH || opcode == Const.LOOKUPSWITCH || opcode == Const.RET) {
        System.out.println("Unhandled " + Const.getOpcodeName(opcode));
        return;
      }
      for (int j = 0; j < 1 + opcodeSize(opcode); j++) {
        bcIndexToOpcode[idx+j] = opcode;
      }
      opcodeNumToBCIndex.get(opcode).add(idx);
      idx += 1 + opcodeSize(opcode);
      numCodesForType[opcode]++;
    }

    ShimpleMethodSource sm = new ShimpleMethodSource(method.getSource());
    ShimpleBody sb = (ShimpleBody)sm.getBody(method, "");

    for (Unit u : sb.getUnits()) {
      if (u instanceof soot.jimple.internal.JAssignStmt) {
        soot.jimple.internal.JAssignStmt assign = (soot.jimple.internal.JAssignStmt)u;
        
        Value lval = assign.getLeftOp();
        Value rval = assign.getRightOp();

        if (lval instanceof JInstanceFieldRef) {

          int bci = opcodeNumToBCIndex.get(Const.PUTFIELD).get(numJimpleExprsForType[Const.PUTFIELD]);
          numJimpleExprsForType[Const.PUTFIELD]++;
          // System.out.println(lval.getClass().getName() + " at " + bci);
        } else if (lval instanceof JArrayRef) {
          if (!isPrimitiveType(((JArrayRef)lval).getType())) {
            int bci = opcodeNumToBCIndex.get(Const.AASTORE).get(numJimpleExprsForType[Const.AASTORE]);
            numJimpleExprsForType[Const.AASTORE]++;
            // System.out.println(lval.getClass().getName() + " at " + bci);
          }
        } else if (rval instanceof JNewExpr) {
          ArrayList<Integer> bcis = opcodeNumToBCIndex.get(Const.NEW);
          int bci = bcis.get(numJimpleExprsForType[Const.NEW]);
          numJimpleExprsForType[Const.NEW]++;
          // System.out.println(rval.getClass().getName() + " at " + bci);
        } else if (rval instanceof JNewArrayExpr) {
          //TODO: Check which one is this
          int bci = 0;
          if (isPrimitiveType(((JNewArrayExpr)rval).getBaseType())) {
            bci = opcodeNumToBCIndex.get(Const.NEWARRAY).get(numJimpleExprsForType[Const.NEWARRAY]);
            numJimpleExprsForType[Const.NEWARRAY]++;
          } else {
            bci = opcodeNumToBCIndex.get(Const.ANEWARRAY).get(numJimpleExprsForType[Const.ANEWARRAY]);
            numJimpleExprsForType[Const.ANEWARRAY]++;
          }
          
          // System.out.println(rval.getClass().getName() + " at " + bci);
        } else if (rval instanceof JNewMultiArrayExpr) {
          int bci = opcodeNumToBCIndex.get(Const.MULTIANEWARRAY).get(numJimpleExprsForType[Const.MULTIANEWARRAY]);
          numJimpleExprsForType[Const.MULTIANEWARRAY]++;
          // System.out.println(rval.getClass().getName() + " at " + bci);
        }
      }
      // System.out.println(u.toString() + " " + u.getClass().getName());
    }

    for (short i = 0; i < Const.OPCODE_NAMES_LENGTH; i++) {
      if (i == Const.PUTFIELD || i == Const.AASTORE || i == Const.NEW || i == Const.NEWARRAY || i == Const.ANEWARRAY || i == Const.MULTIANEWARRAY) {
        int x = opcodeNumToBCIndex.get(i).size();
        int y = numJimpleExprsForType[i];
        Main.debugAssert(x == y, "%d != %d\n", x, y);
      }
    }

    // AsmMethodSource asm = (AsmMethodSource)method.getSource();
    // try {
    //   asm.getBody(method, "");
    //   //Access private instructions field using reflection
    //   Field instructionsField = AsmMethodSource.class.getDeclaredField("instructions");
    //   instructionsField.setAccessible(true);
    //   InsnList instrs = (InsnList)instructionsField.get(asm);
    //   ArrayList<AbstractInsnNode> nonLabelInstrs = new ArrayList<>();
    //   for (AbstractInsnNode insn : instrs) {
    //     if (insn.getOpcode() != -1) {
    //       nonLabelInstrs.add(insn);
    //     }
    //   }
      
    //   Main.debugAssert(numCodes == nonLabelInstrs.size(), numCodes + " != " + nonLabelInstrs.size());

    //   //Access map of Insn to Jimple Unit box
    //   Field unitsField = AsmMethodSource.class.getDeclaredField("units");
    //   unitsField.setAccessible(true);
    //   Object o = unitsField.get(asm);
    //   System.out.println(o);
    //   Map<AbstractInsnNode, Unit> mapInsnToUnit = (Map<AbstractInsnNode, Unit>)unitsField.get(asm);
    //   // AbstractInsnNode[] bciToInsn = new AbstractInsnNode[numCodes];
    //   // int currBytecodeIndex = 0;
    //   // for (int instrIdx = 0; instrIdx < instrs.size(); instrIdx++) {
    //   //   AbstractInsnNode insn = instrs.get(instrIdx);
    //   //   if (insn.getOpcode() != -1) {
    //   //     bciToInsn[currBytecodeIndex] = insn;
    //   //     currBytecodeIndex += 1 + opcodeSize(insn.getOpcode());
    //   //   }
    //   // }
      
    //   for (int i = 0; i < code.length; i++) {
    //     System.out.printf("%d: %s -> %s\n", i, Const.getOpcodeName(nonLabelInstrs.get(bcIndexToNum[i]).getOpcode()),
    //                       mapInsnToUnit.get(nonLabelInstrs.get(bcIndexToNum[i])).toString());
    //   }
    // } catch (Exception e) {
    //   e.printStackTrace();
    // }
  }

  public ShimpleBody getOrParseToShimple(SootMethod method) {
    if (!containsKey(method)) {
      System.out.println(Main.methodFullName(method));
      buildBytecodeIndexToInsnMap(method);      

      // System.exit(0);
      put(method, null);
    }
    return get(method);
  }
}
