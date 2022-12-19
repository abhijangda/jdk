import java.io.IOException;

import org.apache.bcel.classfile.*;
import org.apache.bcel.*;
import org.apache.bcel.generic.GETFIELD;
import org.apache.bcel.generic.PUTFIELD;
import org.apache.bcel.generic.PUTSTATIC;
import org.apache.bcel.generic.Type;
import org.apache.bcel.util.*;

import javatypes.*;

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

  public static ConstantVal getOrSetConstantVal(int constIndex, Constant c, JavaClassCollection classCollection, ConstantVal[] constantVals) {
    if (constantVals[constIndex] != null)
      return constantVals[constIndex];

    Type type = null;
    int i;
    final byte tag = c.getTag();
    switch (tag) {
    // case Const.CONSTANT_Class:
    //     i = ((ConstantClass) c).getNameIndex();
    //     c = getConstantUtf8(i);
    //     str = Utility.compactClassName(((ConstantUtf8) c).getBytes(), false);
    //     break;
    case Const.CONSTANT_String:
      // i = ((ConstantString) c).getStringIndex();
      // c = getConstantUtf8(i);
      // str = "\"" + escape(((ConstantUtf8) c).getBytes()) + "\"";
      type = Type.STRING;
      break;
    case Const.CONSTANT_Utf8:
      type = new JavaArrayType(Type.BYTE, 1);
      break;
    case Const.CONSTANT_Double:
      type = Type.DOUBLE;
      break;
    case Const.CONSTANT_Float:
      type = Type.FLOAT;
      break;
    case Const.CONSTANT_Long:
      type = Type.LONG;
      break;
    case Const.CONSTANT_Integer:
      type = Type.INT;
      break;
    // case Const.CONSTANT_NameAndType:
    //     str = constantToString(((ConstantNameAndType) c).getNameIndex(), Const.CONSTANT_Utf8) + " "
    //             + constantToString(((ConstantNameAndType) c).getSignatureIndex(), Const.CONSTANT_Utf8);
    //     break;
    // case Const.CONSTANT_InterfaceMethodref:
    // case Const.CONSTANT_Methodref:
    // case Const.CONSTANT_Fieldref:
    //     str = constantToString(((ConstantCP) c).getClassIndex(), Const.CONSTANT_Class) + "."
    //             + constantToString(((ConstantCP) c).getNameAndTypeIndex(), Const.CONSTANT_NameAndType);
    //     break;
    // case Const.CONSTANT_MethodHandle:
    //     // Note that the ReferenceIndex may point to a Fieldref, Methodref or
    //     // InterfaceMethodref - so we need to peek ahead to get the actual type.
    //     final ConstantMethodHandle cmh = (ConstantMethodHandle) c;
    //     str = Const.getMethodHandleName(cmh.getReferenceKind()) + " "
    //             + constantToString(cmh.getReferenceIndex(), getConstant(cmh.getReferenceIndex()).getTag());
    //     break;
    // case Const.CONSTANT_MethodType:
    //     final ConstantMethodType cmt = (ConstantMethodType) c;
    //     str = constantToString(cmt.getDescriptorIndex(), Const.CONSTANT_Utf8);
    //     break;
    // case Const.CONSTANT_InvokeDynamic:
    //     final ConstantInvokeDynamic cid = (ConstantInvokeDynamic) c;
    //     str = cid.getBootstrapMethodAttrIndex() + ":" + constantToString(cid.getNameAndTypeIndex(), Const.CONSTANT_NameAndType);
    //     break;
    // case Const.CONSTANT_Dynamic:
    //     final ConstantDynamic cd = (ConstantDynamic) c;
    //     str = cd.getBootstrapMethodAttrIndex() + ":" + constantToString(cd.getNameAndTypeIndex(), Const.CONSTANT_NameAndType);
    //     break;
    // case Const.CONSTANT_Module:
    //     i = ((ConstantModule) c).getNameIndex();
    //     c = getConstantUtf8(i);
    //     str = Utility.compactClassName(((ConstantUtf8) c).getBytes(), false);
    //     break;
    // case Const.CONSTANT_Package:
    //     i = ((ConstantPackage) c).getNameIndex();
    //     c = getConstantUtf8(i);
    //     str = Utility.compactClassName(((ConstantUtf8) c).getBytes(), false);
    //     break;
    default: // Never reached
        System.out.println("Unknown constant type " + tag);
    }
    ConstantVal cval = new ConstantVal(type, c);
    constantVals[constIndex] = cval;
    return cval;
  }

  public static BytecodeUpdate createThreeAddressCode(final ByteSequence bytes, int bci, int byteIndex, final ConstantPool constantPool, 
                                                      Stack<Var> operandStack, LocalVar[] localVars, ConstantVal[] constantVals, JavaClassCollection classCollection) throws IOException {
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
    System.out.println(Const.getOpcodeName(opcode) + " " + byteIndex);
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
      break;
    case Const.JSR:
      unhandledBytecode(opcode);
      break;
    case Const.IFEQ:
    case Const.IFGE:
    case Const.IFGT:
    case Const.IFLE:
    case Const.IFLT:
    case Const.IFNE:
    case Const.IFNONNULL:
    case Const.IFNULL:
      bytes.getIndex();
      bytes.readShort();
      operandStack.pop();
      break;
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
      operandStack.pop();
      operandStack.pop();
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
     * Const push instructions 
     */
    case Const.ACONST_NULL: {
      operandStack.push(new ConstantVal(Type.NULL, null));
      break;
    }

    case Const.ICONST_0:
    case Const.ICONST_1:
    case Const.ICONST_2:
    case Const.ICONST_3:
    case Const.ICONST_4:
    case Const.ICONST_5: {
      int iconst = opcode - Const.ICONST_0;
      operandStack.push(new ConstantVal(Type.INT, new ConstantInteger(iconst)));
      break;
    }

    case Const.ICONST_M1: {
      operandStack.push(new ConstantVal(Type.INT, new ConstantInteger(-1)));
      break;
    }

    case Const.LCONST_0:
    case Const.LCONST_1: {
      long lconst = opcode - Const.LCONST_0;
      operandStack.push(new ConstantVal(Type.LONG, new ConstantLong(lconst)));
      break;
    }

    case Const.DCONST_0:
    case Const.DCONST_1: {
      double dconst = opcode - Const.DCONST_0;
      operandStack.push(new ConstantVal(Type.DOUBLE, new ConstantDouble(dconst)));
      break;
    }

    case Const.FCONST_0:
    case Const.FCONST_1: 
    case Const.FCONST_2: {
      float fconst = opcode - Const.FCONST_0;
      operandStack.push(new ConstantVal(Type.FLOAT, new ConstantFloat(fconst)));
      break;
    }

    /*
     * Local variable load instructions 
     */
      
    case Const.ALOAD_0:
    case Const.ALOAD_1:
    case Const.ALOAD_2:
    case Const.ALOAD_3: {
      int alocal = opcode - Const.ALOAD_0;
      operandStack.push(localVars[alocal]);
      break;
    }

    case Const.ILOAD_0:
    case Const.ILOAD_1:
    case Const.ILOAD_2:
    case Const.ILOAD_3: {
      int ilocal = opcode - Const.ILOAD_0;
      operandStack.push(localVars[ilocal]);
      break;
    }

    case Const.LLOAD_0:
    case Const.LLOAD_1:
    case Const.LLOAD_2:
    case Const.LLOAD_3: {
      int llocal = opcode - Const.LLOAD_0;
      operandStack.push(localVars[llocal]);
      break;
    }

    case Const.FLOAD_0:
    case Const.FLOAD_1:
    case Const.FLOAD_2:
    case Const.FLOAD_3: {
      int flocal = opcode - Const.FLOAD_0;
      operandStack.push(localVars[flocal]);
      break;
    }

    case Const.DLOAD_0:
    case Const.DLOAD_1:
    case Const.DLOAD_2:
    case Const.DLOAD_3: {
      int dlocal = opcode - Const.DLOAD_0;
      operandStack.push(localVars[dlocal]);
      break;
    }
    /*
     * Index byte references local variable (register)
     */
    case Const.ALOAD:
    case Const.DLOAD:
    case Const.FLOAD:
    case Const.ILOAD:
    case Const.LLOAD: {
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
    }
    
    /*
     * Local variable store instructions 
     */

    case Const.ASTORE_0:
    case Const.ASTORE_1:
    case Const.ASTORE_2:
    case Const.ASTORE_3: {
      int alocal = opcode - Const.ASTORE_0;
      Var v = operandStack.pop();
      bcUpdate.addInput(v);
      bcUpdate.addOutput(localVars[alocal]);
      break;
    }

    case Const.ISTORE_0:
    case Const.ISTORE_1:
    case Const.ISTORE_2:
    case Const.ISTORE_3: {
      int ilocal = opcode - Const.ISTORE_0;
      Var v = operandStack.pop();
      bcUpdate.addInput(v);
      bcUpdate.addOutput(localVars[ilocal]);
      break;
    }

    case Const.FSTORE_0:
    case Const.FSTORE_1:
    case Const.FSTORE_2:
    case Const.FSTORE_3: {
      int flocal = opcode - Const.FSTORE_0;
      Var v = operandStack.pop();
      bcUpdate.addInput(v);
      bcUpdate.addOutput(localVars[flocal]);
      break;
    }

    case Const.LSTORE_0:
    case Const.LSTORE_1:
    case Const.LSTORE_2:
    case Const.LSTORE_3: {
      int llocal = opcode - Const.ASTORE_0;
      Var v = operandStack.pop();
      bcUpdate.addInput(v);
      bcUpdate.addOutput(localVars[llocal]);
      break;
    }

    case Const.ASTORE:
    case Const.FSTORE:
    case Const.DSTORE:
    case Const.ISTORE:
    case Const.LSTORE: {
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
    }

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
     * Binary operations 
     */
    case Const.ISUB:
    case Const.IUSHR:
    case Const.IXOR:
    case Const.IMUL:
    case Const.IADD:
    case Const.IOR:
    case Const.IREM:
    case Const.ISHR:
    case Const.IDIV: {
      Var v1 = operandStack.pop();
      Var v2 = operandStack.pop();

      IntermediateVar r = new IntermediateVar(Type.INT, bci);
      operandStack.push(r);

      bcUpdate.addInput(v1);
      bcUpdate.addInput(v2);
      bcUpdate.addOutput(r);
      break;
    }

    case Const.LSUB:
    case Const.LUSHR:
    case Const.LXOR:
    case Const.LMUL:
    case Const.LADD:
    case Const.LOR:
    case Const.LREM:
    case Const.LSHR:
    case Const.LDIV: {
      Var v1 = operandStack.pop();
      Var v2 = operandStack.pop();

      IntermediateVar r = new IntermediateVar(Type.LONG, bci);
      operandStack.push(r);

      bcUpdate.addInput(v1);
      bcUpdate.addInput(v2);
      bcUpdate.addOutput(r);
      break;
    }

    case Const.FSUB:
    case Const.FMUL:
    case Const.FADD:
    case Const.FREM:
    case Const.FDIV: {
      Var v1 = operandStack.pop();
      Var v2 = operandStack.pop();

      IntermediateVar r = new IntermediateVar(Type.FLOAT, bci);
      operandStack.push(r);

      bcUpdate.addInput(v1);
      bcUpdate.addInput(v2);
      bcUpdate.addOutput(r);
      break;
    }

    case Const.DSUB:
    case Const.DMUL:
    case Const.DADD:
    case Const.DREM:
    case Const.DDIV: {
      Var v1 = operandStack.pop();
      Var v2 = operandStack.pop();

      IntermediateVar r = new IntermediateVar(Type.DOUBLE, bci);
      operandStack.push(r);

      bcUpdate.addInput(v1);
      bcUpdate.addInput(v2);
      bcUpdate.addOutput(r);
      break;
    }

    /*
     * Array of basic type.
     */
    case Const.NEWARRAY: {
      String elem = Const.getTypeName(bytes.readByte());
      JavaClass elemClass = classCollection.getClassForString(elem);
      Var count = operandStack.pop();
      Var arr = new IntermediateVar(new JavaArrayType(elemClass, 1), bci);
      bcUpdate.addInput(count);
      bcUpdate.addOutput(arr);
      break;
    }
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
      Type fieldType = classCollection.javaTypeForSignature(fieldTypeSig);
      
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
    case Const.NEW: {
      index = bytes.readUnsignedShort();
      String klass = constantPool.constantToString(index, Const.CONSTANT_Class);
      Type c = classCollection.javaTypeForSignature(klass);
      assert (c != null);
      IntermediateVar obj = new IntermediateVar(c, bci);
      bcUpdate.addOutput(obj);
      operandStack.push(obj);
      break;
    }

    case Const.CHECKCAST: {
      index = bytes.readUnsignedShort();
      Var v = operandStack.peek();
      bcUpdate.addInput(v);
      break;
    }

    case Const.INSTANCEOF: {
      index = bytes.readUnsignedShort();
      String klass = constantPool.constantToString(index, Const.CONSTANT_Class);
      if (opcode == Const.INSTANCEOF) {
        //TODO: Bool class
        IntermediateVar b = new IntermediateVar(Type.BOOLEAN, bci);
        Var obj = operandStack.pop();
        bcUpdate.addInput(obj);
        bcUpdate.addOutput(b);
        operandStack.push(b);
      } else {
        unhandledBytecode(opcode);
      }
      //TODO: 
      break;
    }

    case Const.MONITORENTER: 
    case Const.MONITOREXIT: {
      Var v = operandStack.pop();
      bcUpdate.addInput(v);
      break;
    }
    /*
     * Operands are references to methods in constant pool
     */
    case Const.INVOKESPECIAL:
    case Const.INVOKESTATIC: 
    case Const.INVOKEINTERFACE: 
    case Const.INVOKEVIRTUAL: {
      index = bytes.readUnsignedShort();
      String methodStr = "";
      
      if (opcode == Const.INVOKEDYNAMIC || opcode == Const.INVOKESPECIAL) {
        methodStr = constantPool.constantToString(index, constantPool.getConstant(index).getTag()).replace(" ", "");
      } else if (opcode == Const.INVOKEVIRTUAL) {
        methodStr = constantPool.constantToString(index, Const.CONSTANT_Methodref).replace(" ", "");
      } else if (opcode == Const.INVOKEINTERFACE) {
        final int nargs = bytes.readUnsignedByte(); // historical, redundant
        methodStr = constantPool.constantToString(index, Const.CONSTANT_InterfaceMethodref).replace(" ", "");
        bytes.readUnsignedByte();
      } else if (opcode == Const.INVOKESTATIC) {
        methodStr = constantPool.constantToString(index, constantPool.getConstant(index).getTag()).replace(" ", "");
      } else {
        unhandledBytecode(opcode);
      }
      JavaMethod method = classCollection.getMethod(methodStr);
      for (Type t : method.getMethod().getArgumentTypes()) {
        bcUpdate.addInput(operandStack.pop());  
      }
      if (method.getMethod().getReturnType() != Type.VOID) {
        //TODO: 
        //method.getMethod().getReturnType()
        IntermediateVar ret = new IntermediateVar(null, bci);
        operandStack.push(ret);
        bcUpdate.addOutput(ret);
      }
      break;
    }
    case Const.INVOKEDYNAMIC:
      index = bytes.readUnsignedShort();
      bytes.readUnsignedByte(); // Thrid byte is a reserved space
      bytes.readUnsignedByte(); // Last byte is a reserved space
      constantPool.constantToString(index, Const.CONSTANT_InvokeDynamic).replace(" ", "");
      unhandledBytecode(opcode);
      break;
    /*
     * Operands are references to items in constant pool
     */
    case Const.LDC_W:
    case Const.LDC2_W: {
      index = bytes.readUnsignedShort();
      Constant c = constantPool.getConstant(index);
      System.out.println("385: " + c.toString());
      String s = constantPool.constantToString(index, constantPool.getConstant(index).getTag());
      System.out.println("391: " + s);
      unhandledBytecode(opcode);
      break;
    }
    case Const.LDC: {
      index = bytes.readUnsignedByte();
      Constant con = constantPool.getConstant(index);
      ConstantVal c = getOrSetConstantVal(index, con, classCollection, constantVals);
      operandStack.push(c);
      break;
    }
    /*
     * Array of references.
     */
    case Const.ANEWARRAY: {
        index = bytes.readUnsignedShort();
        String klassSig = constantPool.getConstantString(index, Const.CONSTANT_Class);
        Type fieldType = classCollection.javaTypeForSignature(klassSig);
        Var count1 = operandStack.pop();
        bcUpdate.addInput(count1);
        IntermediateVar arr1 = new IntermediateVar(new JavaArrayType(fieldType, 1), bci);
        operandStack.push(arr1);
        bcUpdate.addOutput(arr1);
        break;
      }
    /*
     * Multidimensional array of references.
     */
    case Const.MULTIANEWARRAY: {
      index = bytes.readUnsignedShort();
      final int dimensions = bytes.readUnsignedByte();
      Type elemType = classCollection.javaTypeForSignature(constantPool.getConstantString(index, Const.CONSTANT_Class));
      IntermediateVar arr1 = new IntermediateVar(new JavaArrayType(elemType, dimensions), bci);
      for (int i = 0; i < dimensions; i++) {
        Var c = operandStack.pop();
        bcUpdate.addInput(c);
      }
      bcUpdate.addOutput(arr1);
      operandStack.push(arr1);
      // Utility.compactClassName(, false);
      break;
    }

    case Const.SIPUSH: {
      int c = bytes.readUnsignedShort();
      operandStack.push(new ConstantVal(Type.INT, new ConstantInteger(c)));
      break;
    }
    case Const.BIPUSH: {
      byte c = bytes.readByte();
      operandStack.push(new ConstantVal(Type.INT, new ConstantInteger(c)));
      break;
    }
    case Const.DUP: {
      operandStack.push(operandStack.peek());
      break;
    }

    case Const.ATHROW: {
      Var v = operandStack.pop();
      bcUpdate.addInput(v);
      break;
    }

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
      unhandledBytecode(opcode);
      break;
    
    case Const.NOP:
      break;
    default:
        unhandledBytecode(opcode);
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
      localVars[v.getIndex()] = new LocalVar(classCollection.javaTypeForSignature(v.getSignature()), v.getIndex());
    }
    ConstantVal[] constants = new ConstantVal[constPool.getLength()];
    // for (int c = 0; c < constPool.getLength(); c++) {
    //   Constant co = constPool.getConstant(c);
    //   System.out.println("484: " + co.toString());
    // }
    System.out.println(code.toString(true));
    try (ByteSequence stream = new ByteSequence(code.getCode())) {
        for (int bci = 0; bci < stream.available(); bci++) { //stream.available() > 0
          // if (i == event.bci_) 
          //   System.out.println(Const.getOpcodeName(code.getCode()[i]));
          createThreeAddressCode(stream, bci, stream.getIndex(), constPool,
                                 operandStack, localVars, constants, classCollection);
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
