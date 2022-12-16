import java.io.IOException;

import org.apache.bcel.classfile.*;
import org.apache.bcel.*;
import org.apache.bcel.generic.Type;
import org.apache.bcel.util.*;
import java.util.jar.*;

import javax.print.attribute.IntegerSyntax;
import javax.swing.plaf.basic.BasicInternalFrameTitlePane.SystemMenuBar;
import java.nio.file.*;
import java.util.*;
import java.io.*;
import java.util.zip.ZipInputStream;

public class BytecodeAnalyzer {
  public static boolean wide;

  public static String analyzeBytecode(final ByteSequence bytes, int bcIndex, final ConstantPool constantPool, 
                                  HashMap<Integer, String> invokeMethods) throws IOException {
    final short opcode = (short) bytes.readUnsignedByte();
    int defaultOffset = 0;
    int low;
    int high;
    int npairs;
    int index;
    int vindex;
    int constant;
    int[] match;
    int[] jumpTable;
    int noPadBytes = 0;
    int offset;
    boolean verbose = false;
    final StringBuilder buf = new StringBuilder(Const.getOpcodeName(opcode));
    /*
     * Special case: Skip (0-3) padding bytes, i.e., the following bytes are 4-byte-aligned
     */
    if (opcode == Const.TABLESWITCH || opcode == Const.LOOKUPSWITCH) {
        final int remainder = bytes.getIndex() % 4;
        noPadBytes = remainder == 0 ? 0 : 4 - remainder;
        for (int i = 0; i < noPadBytes; i++) {
            byte b;
            if ((b = bytes.readByte()) != 0) {
                System.err.println("Warning: Padding byte != 0 in " + Const.getOpcodeName(opcode) + ":" + b);
            }
        }
        // Both cases have a field default_offset in common
        defaultOffset = bytes.readInt();
    }
    switch (opcode) {
    /*
     * Table switch has variable length arguments.
     */
    case Const.TABLESWITCH:
        low = bytes.readInt();
        high = bytes.readInt();
        offset = bytes.getIndex() - 12 - noPadBytes - 1;
        defaultOffset += offset;
        jumpTable = new int[high - low + 1];
        for (int i = 0; i < jumpTable.length; i++) {
            jumpTable[i] = offset + bytes.readInt();
            if (i < jumpTable.length - 1) {
            }
        }
        break;
    /*
     * Lookup switch has variable length arguments.
     */
    case Const.LOOKUPSWITCH: {
        npairs = bytes.readInt();
        offset = bytes.getIndex() - 8 - noPadBytes - 1;
        match = new int[npairs];
        jumpTable = new int[npairs];
        defaultOffset += offset;
        for (int i = 0; i < npairs; i++) {
            match[i] = bytes.readInt();
            jumpTable[i] = offset + bytes.readInt();
            if (i < npairs - 1) {
            }
        }
    }
        break;
    /*
     * Two address bytes + offset from start of byte stream form the jump target
     */
    case Const.GOTO:
    case Const.IFEQ:
    case Const.IFGE:
    case Const.IFGT:
    case Const.IFLE:
    case Const.IFLT:
    case Const.JSR:
    case Const.IFNE:
    case Const.IFNONNULL:
    case Const.IFNULL:
    case Const.IF_ACMPEQ:
    case Const.IF_ACMPNE:
    case Const.IF_ICMPEQ:
    case Const.IF_ICMPGE:
    case Const.IF_ICMPGT:
    case Const.IF_ICMPLE:
    case Const.IF_ICMPLT:
    case Const.IF_ICMPNE:
      bytes.getIndex();
      bytes.readShort();
      break;
    /*
     * 32-bit wide jumps
     */
    case Const.GOTO_W:
    case Const.JSR_W:
      bytes.getIndex();
      bytes.readInt();
      break;
    /*
     * Index byte references local variable (register)
     */
    case Const.ALOAD:
    case Const.ASTORE:
    case Const.DLOAD:
    case Const.DSTORE:
    case Const.FLOAD:
    case Const.FSTORE:
    case Const.ILOAD:
    case Const.ISTORE:
    case Const.LLOAD:
    case Const.LSTORE:
    case Const.RET:
      if (wide) {
        vindex = bytes.readUnsignedShort();
        wide = false; // Clear flag
      } else {
        vindex = bytes.readUnsignedByte();
      }
      break;
    /*
     * Remember wide byte which is used to form a 16-bit address in the following instruction. Relies on that the method is
     * called again with the following opcode.
     */
    case Const.WIDE:
        wide = true;
        break;
    /*
     * Array of basic type.
     */
    case Const.NEWARRAY:
      Const.getTypeName(bytes.readByte());
      break;
    /*
     * Access object/class fields.
     */
    case Const.GETFIELD:
    case Const.GETSTATIC:
    case Const.PUTFIELD:
    case Const.PUTSTATIC:
      index = bytes.readUnsignedShort();
      constantPool.constantToString(index, Const.CONSTANT_Fieldref);
      break;
    /*
     * Operands are references to classes in constant pool
     */
    case Const.NEW:
    case Const.CHECKCAST:
        //$FALL-THROUGH$
    case Const.INSTANCEOF:
      index = bytes.readUnsignedShort();
      constantPool.constantToString(index, Const.CONSTANT_Class);
      break;
    /*
     * Operands are references to methods in constant pool
     */
    case Const.INVOKESPECIAL:
    case Const.INVOKESTATIC:
      index = bytes.readUnsignedShort();
      final Constant c = constantPool.getConstant(index);
      // With Java8 operand may be either a CONSTANT_Methodref
      // or a CONSTANT_InterfaceMethodref. (markro)
      invokeMethods.put(bcIndex, constantPool.constantToString(index, c.getTag()).replace(" ", ""));
      break;
    case Const.INVOKEVIRTUAL:
      index = bytes.readUnsignedShort();
      invokeMethods.put(bcIndex, constantPool.constantToString(index, Const.CONSTANT_Methodref).replace(" ", ""));
      break;
    case Const.INVOKEINTERFACE:
      index = bytes.readUnsignedShort();
      final int nargs = bytes.readUnsignedByte(); // historical, redundant
      invokeMethods.put(bcIndex, constantPool.constantToString(index, Const.CONSTANT_InterfaceMethodref).replace(" ", ""));
      bytes.readUnsignedByte(); // Last byte is a reserved space
      break;
    case Const.INVOKEDYNAMIC:
      index = bytes.readUnsignedShort();
      bytes.readUnsignedByte(); // Thrid byte is a reserved space
      bytes.readUnsignedByte(); // Last byte is a reserved space
      constantPool.constantToString(index, Const.CONSTANT_InvokeDynamic).replace(" ", "");
      break;
    /*
     * Operands are references to items in constant pool
     */
    case Const.LDC_W:
    case Const.LDC2_W:
      index = bytes.readUnsignedShort();
      constantPool.constantToString(index, constantPool.getConstant(index).getTag());
      break;
    case Const.LDC:
      index = bytes.readUnsignedByte();
      constantPool.constantToString(index, constantPool.getConstant(index).getTag());
      break;
    /*
     * Array of references.
     */
    case Const.ANEWARRAY:
      index = bytes.readUnsignedShort();
      Utility.compactClassName(constantPool.getConstantString(index, Const.CONSTANT_Class), false);
      break;
    /*
     * Multidimensional array of references.
     */
    case Const.MULTIANEWARRAY:
      index = bytes.readUnsignedShort();
      final int dimensions = bytes.readUnsignedByte();
      Utility.compactClassName(constantPool.getConstantString(index, Const.CONSTANT_Class), false);
      break;
    /*
     * Increment local variable.
     */
    case Const.IINC:
      if (wide) {
          vindex = bytes.readUnsignedShort();
          constant = bytes.readShort();
          wide = false;
      } else {
          vindex = bytes.readUnsignedByte();
          constant = bytes.readByte();
      }
      break;
    default:
        if (Const.getNoOfOperands(opcode) > 0) {
            for (int i = 0; i < Const.getOperandTypeCount(opcode); i++) {
                buf.append("\t\t");
                switch (Const.getOperandType(opcode, i)) {
                case Const.T_BYTE:
                    bytes.readByte();
                    break;
                case Const.T_SHORT:
                    bytes.readShort();
                    break;
                case Const.T_INT:
                    bytes.readInt();
                    break;
                default: // Never reached
                    throw new IllegalStateException("Unreachable default case reached!");
                }
            }
        }
    }
    return buf.toString();
  }

  public static void analyzeEvent(HeapEvent event, CallFrame frame, StaticValue staticValues) {
    Code code = event.method_.getMethod().getCode();
    ConstantPool constPool = code.getConstantPool();
    int opcode = Byte.toUnsignedInt(code.getCode()[event.bci_]);
    
    switch(opcode) {
      case Const.NEW:
      case Const.NEWARRAY:
      case Const.ANEWARRAY:
      case Const.PUTFIELD:
      case Const.GETFIELD:

      default:
        System.out.println("Unhandled " + Const.getOpcodeName(opcode));
    }

    // for (int i = 0; i < code.getCode().length; i++) { //stream.available() > 0
      // if (i == event.bci_) 
        // System.out.println(Const.getOpcodeName(code.getCode()[i]));
      // analyzeBytecode(stream, i, constPool, frame, staticValues);
    // }
    // try (ByteSequence stream = new ByteSequence(code.getCode())) {
    //     for (int i = 0; i < code.getCode().length; i++) { //stream.available() > 0
    //       if (i == event.bci_) 
    //         System.out.println(Const.getOpcodeName(code.getCode()[i]));
    //       // analyzeBytecode(stream, i, constPool, frame, staticValues);
    //     }
    // } catch (final IOException e) {
    //    e.printStackTrace();
    // }
  }
}
