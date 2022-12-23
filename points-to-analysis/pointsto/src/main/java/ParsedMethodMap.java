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
import soot.SootMethod;
import soot.Unit;
import soot.UnitBox;
import soot.asm.AsmMethodSource;
import soot.jimple.JimpleBody;
import soot.jimple.internal.JAssignStmt;
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

  public void buildBytecodeIndexToInsnMap(SootMethod method) {
    Method bcelMethod = BCELClassCollection.v().getMethod(Main.methodFullName(method));
    Main.debugAssert(bcelMethod != null, "%s is null\n", Main.methodFullName(method));
    byte[] code = bcelMethod.getCode().getCode();
    int[] bcIndexToNum = new int[code.length];
    int[] bcNumToIndex = new int[code.length];
    //build bytecode index to bytecode number map 
    int numCodes = 0;
    for (int idx = 0; idx < code.length;) {
      int opcode = code[idx] & 0xff;
      for (int j = 0; j < 1 + opcodeSize(opcode); j++)
        bcIndexToNum[idx+j] = numCodes;
      bcNumToIndex[numCodes] = idx;
      idx += 1 + opcodeSize(opcode);
      numCodes++;
    }
    AsmMethodSource asm = (AsmMethodSource)method.getSource();
    try {
      //Access private instructions field using reflection
      Field instructionsField = AsmMethodSource.class.getDeclaredField("instructions");
      instructionsField.setAccessible(true);
      InsnList instrs = (InsnList)instructionsField.get(asm);
      ArrayList<AbstractInsnNode> nonLabelInstrs = new ArrayList<>();
      for (AbstractInsnNode insn : instrs) {
        if (insn.getOpcode() != -1) {
          nonLabelInstrs.add(insn);
        }
      }
      
      Main.debugAssert(numCodes == nonLabelInstrs.size(), numCodes + " != " + nonLabelInstrs.size());
      // AbstractInsnNode[] bciToInsn = new AbstractInsnNode[numCodes];
      // int currBytecodeIndex = 0;
      // for (int instrIdx = 0; instrIdx < instrs.size(); instrIdx++) {
      //   AbstractInsnNode insn = instrs.get(instrIdx);
      //   if (insn.getOpcode() != -1) {
      //     bciToInsn[currBytecodeIndex] = insn;
      //     currBytecodeIndex += 1 + opcodeSize(insn.getOpcode());
      //   }
      // }
      
      // for (int i = 0; i < code.length; i++) {
      //   System.out.printf("%d: %s\n", i, Const.getOpcodeName(nonLabelInstrs.get(bcIndexToNum[i]).getOpcode()));
      // }
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  public ShimpleBody getOrParseToShimple(SootMethod method) {
    if (!containsKey(method)) {
      ShimpleMethodSource sm = new ShimpleMethodSource(method.getSource());
      ShimpleBody sb = (ShimpleBody)sm.getBody(method, "");
      buildBytecodeIndexToInsnMap(method);
      // JimpleBody asmb = (JimpleBody)asm.getBody(method, null);
      
      // List<UnitBox> units = asmb.getAllUnitBoxes();
      

      // System.exit(0);
      put(method, sb);
    }
    return get(method);
  }
}
