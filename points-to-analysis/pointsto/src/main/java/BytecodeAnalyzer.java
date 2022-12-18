import java.io.IOException;

import org.apache.bcel.classfile.*;
import org.apache.bcel.*;
import org.apache.bcel.generic.GETFIELD;
import org.apache.bcel.generic.PUTFIELD;
import org.apache.bcel.generic.PUTSTATIC;
import org.apache.bcel.generic.Type;
import org.apache.bcel.util.*;
import java.util.jar.*;

import javax.print.attribute.IntegerSyntax;
import javax.swing.plaf.basic.BasicInternalFrameTitlePane.SystemMenuBar;
import java.nio.file.*;
import java.util.*;
import java.io.*;
import java.util.zip.ZipInputStream;

import threeaddresscode.*;

public class BytecodeAnalyzer {
  public static boolean wide;

  static void unhandledBytecode(int opcode) {
    System.out.println("Not handling " + Const.getOpcodeName(opcode)); 
  }

  public static BytecodeUpdate createThreeAddressCode(final ByteSequence bytes, int bci, final ConstantPool constantPool, 
                                                      Stack<Var> operandStack, LocalVar[] localVars, JavaClassCollection classCollection) throws IOException {
    final short opcode = (short) bytes.readUnsignedByte();
    BytecodeUpdate bcUpdate = new BytecodeUpdate(bci, opcode);
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
        unhandledBytecode(opcode);
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
      unhandledBytecode(opcode);
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
    case Const.DLOAD:
    case Const.FLOAD:
    case Const.ILOAD:
    case Const.LLOAD:
      if (wide) {
        vindex = bytes.readUnsignedShort();
        wide = false; // Clear flag
      } else {
        vindex = bytes.readUnsignedByte();
      }
      // bcUpdate.addInput(localVars[vindex]);
      // bcUpdate.addOutput(localVars[vindex]);
      operandStack.push(localVars[vindex]);
      break;
  
    case Const.ASTORE:
    case Const.FSTORE:
    case Const.DSTORE:
    case Const.ISTORE:
    case Const.LSTORE:
      if (wide) {
        vindex = bytes.readUnsignedShort();
        wide = false; // Clear flag
      } else {
        vindex = bytes.readUnsignedByte();
      }
      Var v = operandStack.pop();
      bcUpdate.addInput(v);
      bcUpdate.addOutput(localVars[vindex]);
      break;

    case Const.RET:
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
      String elem = Const.getTypeName(bytes.readByte());
      JavaClass elemClass = classCollection.getClassForString(elem);
      Var count = operandStack.pop();
      Var arr = new IntermediateVar(elemClass, bci);
      bcUpdate.addInput(count);
      bcUpdate.addOutput(arr);
      break;
    /*
     * Access object/class fields.
     */
    case Const.GETFIELD:
    case Const.GETSTATIC:
    case Const.PUTFIELD:
    case Const.PUTSTATIC: {
      index = bytes.readUnsignedShort();
      String field = constantPool.constantToString(index, Const.CONSTANT_Fieldref);
      String[] split = field.split(" ");
      String fieldPath = split[0];
      String fieldTypeSig = split[1];
      JavaClass fieldType = classCollection.getClassForSignature(fieldTypeSig);
      
      if (opcode == Const.PUTFIELD  || opcode == Const.PUTSTATIC) {
        Var value = operandStack.pop();
        if (opcode == Const.PUTFIELD) {
          Var obj = operandStack.pop();
          bcUpdate.addInput(obj);
        }
        bcUpdate.addInput(new FieldVar(fieldType, fieldPath));
        bcUpdate.addInput(value);
      } else if (opcode == Const.GETFIELD || opcode == Const.GETSTATIC) {
        IntermediateVar value = new IntermediateVar(fieldType, bci);
        if (opcode == Const.GETFIELD) {
          Var obj = operandStack.pop();
          bcUpdate.addInput(obj);
        }
        bcUpdate.addInput(new FieldVar(fieldType, fieldPath));
        bcUpdate.addOutput(value);
        operandStack.push(value);
      }
      break;
    }
    /*
     * Operands are references to classes in constant pool
     */
    case Const.NEW:
      index = bytes.readUnsignedShort();
      String klass = constantPool.constantToString(index, Const.CONSTANT_Class);
      JavaClass c = classCollection.getClassForString(klass);
      assert (c != null);
      IntermediateVar obj = new IntermediateVar(c, bci);
      bcUpdate.addOutput(obj);
      operandStack.push(obj);
      break;

    case Const.CHECKCAST:
    case Const.INSTANCEOF:
      index = bytes.readUnsignedShort();
      constantPool.constantToString(index, Const.CONSTANT_Class);
      unhandledBytecode(opcode);
      //TODO: 
      break;
    /*
     * Operands are references to methods in constant pool
     */
    case Const.INVOKESPECIAL:
    case Const.INVOKESTATIC: {
      index = bytes.readUnsignedShort();
      String method = constantPool.constantToString(index, constantPool.getConstant(index).getTag()).replace(" ", "");
      // With Java8 operand may be either a CONSTANT_Methodref
      // or a CONSTANT_InterfaceMethodref. (markro)
      // invokeMethods.put(bci, constantPool.constantToString(index, c.getTag()).replace(" ", ""));
      break;
    }
    case Const.INVOKEVIRTUAL: {
      index = bytes.readUnsignedShort();
      // invokeMethods.put(bci, constantPool.constantToString(index, Const.CONSTANT_Methodref).replace(" ", ""));
      break;
    }
    case Const.INVOKEINTERFACE: {
      index = bytes.readUnsignedShort();
      final int nargs = bytes.readUnsignedByte(); // historical, redundant
      // invokeMethods.put(bci, constantPool.constantToString(index, Const.CONSTANT_InterfaceMethodref).replace(" ", ""));
      bytes.readUnsignedByte(); // Last byte is a reserved space
      break;
    }
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
    
    return bcUpdate;
  }

  public static void analyzeMethod(JavaMethod method, CallFrame frame, StaticValue staticValues, JavaClassCollection classCollection) {
    Code code = method.getMethod().getCode();
    ConstantPool constPool = code.getConstantPool();
    Stack<Var> operandStack = new Stack<>();
    LocalVar[] localVars = new LocalVar[code.getLocalVariableTable().getLength()];
    for (LocalVariable v : code.getLocalVariableTable()) {
      localVars[v.getIndex()] = new LocalVar(classCollection.getClassForSignature(v.getSignature()), v.getIndex());
    }
    try (ByteSequence stream = new ByteSequence(code.getCode())) {
        for (int bci = 0; bci < stream.available(); bci++) { //stream.available() > 0
          // if (i == event.bci_) 
          //   System.out.println(Const.getOpcodeName(code.getCode()[i]));
          createThreeAddressCode(stream, bci, constPool, 
                                 operandStack, localVars, classCollection);
        }
    } catch (final IOException e) {
       e.printStackTrace();
    }
    
    // int opcode = Byte.toUnsignedInt(code.getCode()[event.bci_]);
    
    // switch(opcode) {
    //   case Const.NEW:
    //   case Const.NEWARRAY:
    //   case Const.ANEWARRAY:
    //   case Const.PUTFIELD:
    //   case Const.GETFIELD:

    //   default:
    //     System.out.println("Unhandled " + Const.getOpcodeName(opcode));
    // }

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
