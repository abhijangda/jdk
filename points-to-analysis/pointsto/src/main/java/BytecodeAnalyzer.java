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
import javax.swing.plaf.basic.BasicArrowButton;
import javax.swing.plaf.basic.BasicInternalFrameTitlePane.SystemMenuBar;
import javax.swing.text.DefaultEditorKit.DefaultKeyTypedAction;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
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
                                                      Stack<Var> operandStack, LocalVars localVars, ConstantVal[] constantVals, JavaClassCollection classCollection, boolean print) throws IOException {
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
    if (print)
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
    case Const.GOTO: {
      int t = bytes.readUnsignedShort();
      break;
    }
    case Const.JSR: {
      int t = bytes.readUnsignedShort();
      // ConstantVal v = new ConstantVal(Type.INT, new ConstantInteger(t));
      // operandStack.push(v);
      // bcUpdate.addOutput(v);
      unhandledBytecode(opcode);
      break;
    }
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

      /**
       * Array operations
       */
    case Const.ARRAYLENGTH: {
      Var a = operandStack.pop();
      IntermediateVar v = new IntermediateVar(Type.INT, byteIndex);
      operandStack.push(v);
      bcUpdate.addInput(a);
      bcUpdate.addOutput(v);
      break;
    }

    case Const.IALOAD: {
      Var i = operandStack.pop();
      Var a = operandStack.pop();
      assert (a.type instanceof JavaArrayType);
      IntermediateVar e = new IntermediateVar(Type.INT, byteIndex);
      operandStack.push(e);
      bcUpdate.addInput(a);
      bcUpdate.addInput(i);
      bcUpdate.addOutput(e);
      break;
    }
    case Const.BALOAD: {
      Var i = operandStack.pop();
      Var a = operandStack.pop();
      assert (a.type instanceof JavaArrayType);
      IntermediateVar e = new IntermediateVar(Type.BYTE, byteIndex);
      operandStack.push(e);
      bcUpdate.addInput(a);
      bcUpdate.addInput(i);
      bcUpdate.addOutput(e);
      break;
    }
    case Const.CALOAD: {
      Var i = operandStack.pop();
      Var a = operandStack.pop();
      assert (a.type instanceof JavaArrayType);
      IntermediateVar e = new IntermediateVar(Type.CHAR, byteIndex);
      operandStack.push(e);
      bcUpdate.addInput(a);
      bcUpdate.addInput(i);
      bcUpdate.addOutput(e);
      break;
    }
    case Const.LALOAD: {
      Var i = operandStack.pop();
      Var a = operandStack.pop();
      assert (a.type instanceof JavaArrayType);
      IntermediateVar e = new IntermediateVar(Type.LONG, byteIndex);
      operandStack.push(e);
      bcUpdate.addInput(a);
      bcUpdate.addInput(i);
      bcUpdate.addOutput(e);
      break;
    }

    case Const.AALOAD: {
      Var i = operandStack.pop();
      Var a = operandStack.pop();
      assert (a.type instanceof JavaArrayType);
      // System.out.println(a.type);
      IntermediateVar e = new IntermediateVar(((JavaArrayType)a.type).getBasicType(), byteIndex);
      operandStack.push(e);
      bcUpdate.addInput(a);
      bcUpdate.addInput(i);
      bcUpdate.addOutput(e);
      break;
    }

    case Const.AASTORE: {
      Var v = operandStack.pop();
      Var i = operandStack.pop();
      Var a = operandStack.pop();
      assert (a.type instanceof JavaArrayType);
      bcUpdate.addInput(a);
      bcUpdate.addInput(i);
      bcUpdate.addInput(v);
      break;
    }

    case Const.IASTORE: {
      Var v = operandStack.pop();
      Var i = operandStack.pop();
      Var a = operandStack.pop();
      
      bcUpdate.addInput(a);
      bcUpdate.addInput(i);
      bcUpdate.addInput(v);
      break;
    }
    case Const.BASTORE: {
      Var v = operandStack.pop();
      Var i = operandStack.pop();
      Var a = operandStack.pop();
      
      bcUpdate.addInput(a);
      bcUpdate.addInput(i);
      bcUpdate.addInput(v);
      break;
    }
    case Const.CASTORE: {
      Var v = operandStack.pop();
      Var i = operandStack.pop();
      Var a = operandStack.pop();
      
      bcUpdate.addInput(a);
      bcUpdate.addInput(i);
      bcUpdate.addInput(v);
      break;
    }
    case Const.LASTORE: {
      Var v = operandStack.pop();
      Var i = operandStack.pop();
      Var a = operandStack.pop();
      
      bcUpdate.addInput(a);
      bcUpdate.addInput(i);
      bcUpdate.addInput(v);
      break;
    }
    case Const.POP: {
      operandStack.pop();
      break;
    }
    
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
      //TODO: In load it should always be findLocalVar and should not add
    case Const.ALOAD_0:
    case Const.ALOAD_1:
    case Const.ALOAD_2:
    case Const.ALOAD_3: {
      int alocal = opcode - Const.ALOAD_0;
      operandStack.push(localVars.findOrAddLocalVar(JavaObjectType.getInstance(classCollection.getObjectClass()), alocal, byteIndex));
      break;
    }

    case Const.ILOAD_0:
    case Const.ILOAD_1:
    case Const.ILOAD_2:
    case Const.ILOAD_3: {
      int ilocal = opcode - Const.ILOAD_0;
      operandStack.push(localVars.findOrAddLocalVar(Type.INT, ilocal, byteIndex));
      break;
    }

    case Const.LLOAD_0:
    case Const.LLOAD_1:
    case Const.LLOAD_2:
    case Const.LLOAD_3: {
      int llocal = opcode - Const.LLOAD_0;
      operandStack.push(localVars.findOrAddLocalVar(Type.LONG, llocal, byteIndex));
      break;
    }

    case Const.FLOAD_0:
    case Const.FLOAD_1:
    case Const.FLOAD_2:
    case Const.FLOAD_3: {
      int flocal = opcode - Const.FLOAD_0;
      operandStack.push(localVars.findOrAddLocalVar(Type.FLOAT, flocal, byteIndex));
      break;
    }

    case Const.DLOAD_0:
    case Const.DLOAD_1:
    case Const.DLOAD_2:
    case Const.DLOAD_3: {
      int dlocal = opcode - Const.DLOAD_0;
      operandStack.push(localVars.findOrAddLocalVar(Type.DOUBLE, dlocal, byteIndex));
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
      Type t = null;
      operandStack.push(localVars.findOrAddLocalVar(t, vindex, byteIndex));
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
      bcUpdate.addOutput(localVars.findOrAddLocalVar(v.type, alocal, byteIndex));
      break;
    }

    case Const.ISTORE_0:
    case Const.ISTORE_1:
    case Const.ISTORE_2:
    case Const.ISTORE_3: {
      int ilocal = opcode - Const.ISTORE_0;
      Var v = operandStack.pop();
      assert(v.type == Type.INT);
      bcUpdate.addInput(v);
      bcUpdate.addOutput(localVars.findOrAddLocalVar(Type.INT, ilocal, byteIndex));
      break;
    }

    case Const.FSTORE_0:
    case Const.FSTORE_1:
    case Const.FSTORE_2:
    case Const.FSTORE_3: {
      int flocal = opcode - Const.FSTORE_0;
      Var v = operandStack.pop();
      assert(v.type == Type.FLOAT);
      bcUpdate.addInput(v);
      bcUpdate.addOutput(localVars.findOrAddLocalVar(Type.FLOAT, flocal, byteIndex));
      break;
    }

    case Const.LSTORE_0:
    case Const.LSTORE_1:
    case Const.LSTORE_2:
    case Const.LSTORE_3: {
      int llocal = opcode - Const.LSTORE_0;
      Var v = operandStack.pop();
      assert(v.type == Type.LONG);
      bcUpdate.addInput(v);
      bcUpdate.addOutput(localVars.findOrAddLocalVar(Type.LONG, llocal, byteIndex));
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
      //TODO: add type checks
      bcUpdate.addInput(v);
      bcUpdate.addOutput(localVars.findOrAddLocalVar(v.type, vindex, byteIndex));
      break;
    }

    /**
     * Return instructions
     */
    case Const.RET:
      break;
    
    case Const.RETURN:
      break;
    
    case Const.LRETURN:
    case Const.IRETURN:
    case Const.DRETURN:
    case Const.ARETURN:
    case Const.FRETURN: {
      Var v = operandStack.pop();
      bcUpdate.addInput(v);
      break;
    }
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
    case Const.IAND:
    case Const.IOR:
    case Const.IREM:
    case Const.ISHR:
    case Const.IDIV: 
    case Const.ISHL: {
      Var v2 = operandStack.pop();
      Var v1 = operandStack.pop();

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
    case Const.LDIV: 
    case Const.LCMP: {
      Var v2 = operandStack.pop();
      Var v1 = operandStack.pop();

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
      Var v2 = operandStack.pop();
      Var v1 = operandStack.pop();

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
      Var v2 = operandStack.pop();
      Var v1 = operandStack.pop();

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
      operandStack.push(arr);
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
        if (print) {
          System.out.println(operandStack.size());
        }
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
        //.getReturnType()
        IntermediateVar ret = new IntermediateVar(classCollection.javaTypeForSignature(method.getMethod().getReturnType().getSignature()), bci);
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
      Constant con = constantPool.getConstant(index);
      ConstantVal c = getOrSetConstantVal(index, con, classCollection, constantVals);
      operandStack.push(c);
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
    case Const.DUP_X1: {
      Var v1 = operandStack.pop();
      Var v2 = operandStack.pop();
      operandStack.push(v1);
      operandStack.push(v2);
      operandStack.push(v1);
      bcUpdate.addInput(v1);
      bcUpdate.addInput(v2);
      bcUpdate.addOutput(v1);
      break;
    }
    case Const.DUP_X2: {
      Var v1 = operandStack.pop();
      Var v2 = operandStack.pop();
      Var v3 = operandStack.pop();
      operandStack.push(v1);
      operandStack.push(v3);
      operandStack.push(v2);
      operandStack.push(v1);
      bcUpdate.addInput(v1);
      bcUpdate.addInput(v2);
      bcUpdate.addInput(v3);
      bcUpdate.addOutput(v1);
      break;
    }

    case Const.ATHROW: {
      Var v = operandStack.pop();
      bcUpdate.addInput(v);
      break;
    }

    /**
     * Conversion instructions
     */
    case Const.I2L: {
      Var v = operandStack.pop();
      IntermediateVar r = new IntermediateVar(Type.LONG, bci);
      bcUpdate.addInput(v);
      bcUpdate.addOutput(r);
      operandStack.push(r);
      break;
    }

    case Const.L2I: {
      Var v = operandStack.pop();
      IntermediateVar r = new IntermediateVar(Type.INT, bci);
      bcUpdate.addInput(v);
      bcUpdate.addOutput(r);
      operandStack.push(r);
      break;
    }

    case Const.I2D: {
      Var v = operandStack.pop();
      IntermediateVar r = new IntermediateVar(Type.DOUBLE, bci);
      bcUpdate.addInput(v);
      bcUpdate.addOutput(r);
      operandStack.push(r);
      break;
    }
    case Const.D2I: {
      Var v = operandStack.pop();
      IntermediateVar r = new IntermediateVar(Type.INT, bci);
      bcUpdate.addInput(v);
      bcUpdate.addOutput(r);
      operandStack.push(r);
      break;
    }
    /*
     * Increment local variable.
     */
    case Const.IINC: {
      if (wide) {
          vindex = bytes.readUnsignedShort();
          constant = bytes.readShort();
          wide = false;
      } else {
          vindex = bytes.readUnsignedByte();
          constant = bytes.readByte();
      }
      //TODO: Typecheck here that local variable is present and its type is int
      bcUpdate.addInput(localVars.findOrAddLocalVar(Type.INT, vindex, byteIndex));
      bcUpdate.addOutput(localVars.findOrAddLocalVar(Type.INT, vindex, byteIndex));
      break;
    }
    
    case Const.IMPDEP1:
    case Const.IMPDEP2:
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

  static class BranchInfo {
    private int pc;
    private int codelen;
    private int target;
    private boolean isConditional;

    public BranchInfo(int pc, int codelen, int target, boolean isCond) {
      this.target = target;
      this.isConditional = isCond;
      this.pc = pc;
      this.codelen = codelen;
    }
  }
  public static BranchInfo isBranch(final ByteSequence bytes, int byteIndex, boolean print) throws IOException {
    final short opcode = (short) bytes.readUnsignedByte();
    int low;
    int high;
    int npairs;
    int index;
    int vindex;
    int[] match;
    int[] jumpTable;
    int noPadBytes = 0;
    int offset;
    int defaultOffset = 0;
    int constant;
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
    case Const.GOTO: {
      int t = bytes.getIndex() - 1 + bytes.readShort();
      return new BranchInfo(byteIndex, 1 + 2, t, false);
    }
    case Const.JSR: {
      int t = bytes.getIndex() - 1 + bytes.readShort();
      // ConstantVal v = new ConstantVal(Type.INT, new ConstantInteger(t));
      // operandStack.push(v);
      // bcUpdate.addOutput(v);
      return new BranchInfo(byteIndex, 1 + 2, t, false);
    }
    case Const.IFEQ:
    case Const.IFGE:
    case Const.IFGT:
    case Const.IFLE:
    case Const.IFLT:
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
      return new BranchInfo(byteIndex, 1 + 2, bytes.getIndex() - 1 + bytes.readShort(), true);
    /*
     * 32-bit wide jumps
     */
    case Const.GOTO_W:
    case Const.JSR_W:
      return new BranchInfo(byteIndex, 1 + 4, bytes.getIndex() - 1 + bytes.readInt(), false);
      /**
       * Array operations
       */
    case Const.ARRAYLENGTH: {
      break;
    }

    case Const.IALOAD:
    case Const.BALOAD:
    case Const.CALOAD:
    case Const.LALOAD:
    case Const.AALOAD:
    case Const.AASTORE:
    case Const.IASTORE:
    case Const.BASTORE:
    case Const.CASTORE:
    case Const.LASTORE:
    case Const.POP:
      break;
    
    /*
     * Const push instructions 
     */
    case Const.ACONST_NULL:
    case Const.ICONST_0:
    case Const.ICONST_1:
    case Const.ICONST_2:
    case Const.ICONST_3:
    case Const.ICONST_4:
    case Const.ICONST_5:
    case Const.ICONST_M1:
    case Const.LCONST_0:
    case Const.LCONST_1:
    case Const.DCONST_0:
    case Const.DCONST_1:
    case Const.FCONST_0:
    case Const.FCONST_1: 
    case Const.FCONST_2:
    /*
     * Local variable load instructions 
     */
      //TODO: In load it should always be findLocalVar and should not add
    case Const.ALOAD_0:
    case Const.ALOAD_1:
    case Const.ALOAD_2:
    case Const.ALOAD_3:
    case Const.ILOAD_0:
    case Const.ILOAD_1:
    case Const.ILOAD_2:
    case Const.ILOAD_3:
    case Const.LLOAD_0:
    case Const.LLOAD_1:
    case Const.LLOAD_2:
    case Const.LLOAD_3:
    case Const.FLOAD_0:
    case Const.FLOAD_1:
    case Const.FLOAD_2:
    case Const.FLOAD_3:
    case Const.DLOAD_0:
    case Const.DLOAD_1:
    case Const.DLOAD_2:
    case Const.DLOAD_3: {
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
      break;
    }
    
    /*
     * Local variable store instructions 
     */

    case Const.ASTORE_0:
    case Const.ASTORE_1:
    case Const.ASTORE_2:
    case Const.ASTORE_3:
    case Const.ISTORE_0:
    case Const.ISTORE_1:
    case Const.ISTORE_2:
    case Const.ISTORE_3:
    case Const.FSTORE_0:
    case Const.FSTORE_1:
    case Const.FSTORE_2:
    case Const.FSTORE_3:
    case Const.LSTORE_0:
    case Const.LSTORE_1:
    case Const.LSTORE_2:
    case Const.LSTORE_3:
      break;

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
      break;
    }

    /**
     * Return instructions
     */
    case Const.RET: //TODO: it requires an argument?
      break;
    
    case Const.RETURN:    
    case Const.LRETURN:
    case Const.IRETURN:
    case Const.DRETURN:
    case Const.ARETURN:
    case Const.FRETURN: {
      break;
    }
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
    case Const.IAND:
    case Const.IOR:
    case Const.IREM:
    case Const.ISHR:
    case Const.IDIV: 
    case Const.ISHL:
    case Const.LSUB:
    case Const.LUSHR:
    case Const.LXOR:
    case Const.LMUL:
    case Const.LADD:
    case Const.LOR:
    case Const.LREM:
    case Const.LSHR:
    case Const.LDIV: 
    case Const.LCMP:
    case Const.FSUB:
    case Const.FMUL:
    case Const.FADD:
    case Const.FREM:
    case Const.FDIV:
    case Const.DSUB:
    case Const.DMUL:
    case Const.DADD:
    case Const.DREM:
    case Const.DDIV: {
      break;
    }

    /*
     * Array of basic type.
     */
    case Const.NEWARRAY: {
      bytes.readByte();
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
      break;
    }
    /*
     * Operands are references to classes in constant pool
     */
    case Const.NEW: {
      index = bytes.readUnsignedShort();
      break;
    }

    case Const.CHECKCAST: {
      index = bytes.readUnsignedShort();
      break;
    }

    case Const.INSTANCEOF: {
      index = bytes.readUnsignedShort();
      //TODO: 
      break;
    }

    case Const.MONITORENTER: 
    case Const.MONITOREXIT: {
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
      break;
    }
    case Const.INVOKEDYNAMIC:
      index = bytes.readUnsignedShort();
      bytes.readUnsignedByte(); // Thrid byte is a reserved space
      bytes.readUnsignedByte(); // Last byte is a reserved space
      // constantPool.constantToString(index, Const.CONSTANT_InvokeDynamic).replace(" ", "");
      unhandledBytecode(opcode);
      break;
    /*
     * Operands are references to items in constant pool
     */
    case Const.LDC_W:
    case Const.LDC2_W: {
      index = bytes.readUnsignedShort();
      break;
    }
    case Const.LDC: {
      index = bytes.readUnsignedByte();
      break;
    }
    /*
     * Array of references.
     */
    case Const.ANEWARRAY: {
        index = bytes.readUnsignedShort();
        break;
      }
    /*
     * Multidimensional array of references.
     */
    case Const.MULTIANEWARRAY: {
      index = bytes.readUnsignedShort();
      final int dimensions = bytes.readUnsignedByte();
      // Utility.compactClassName(, false);
      break;
    }

    case Const.SIPUSH: {
      int c = bytes.readUnsignedShort();
      break;
    }
    case Const.BIPUSH: {
      byte c = bytes.readByte();
      break;
    }
    case Const.DUP: {
      break;
    }
    case Const.DUP_X1: {
      break;
    }
    case Const.DUP_X2: {
      break;
    }

    case Const.ATHROW: {
      break;
    }

    /**
     * Conversion instructions
     */
    case Const.I2L:
    case Const.L2I:
    case Const.I2D:
    case Const.D2I:
      break;
    /*
     * Increment local variable.
     */
    case Const.IINC: {
      if (wide) {
          vindex = bytes.readUnsignedShort();
          constant = bytes.readShort();
          wide = false;
      } else {
          vindex = bytes.readUnsignedByte();
          constant = bytes.readByte();
      }
      break;
    }
    
    case Const.IMPDEP1:
    case Const.IMPDEP2:
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

    return null;
  }

  public static void createBasicBlocks(byte[] code, int start, int end) {
    ArrayList<BranchInfo> branches = new ArrayList<>();
    //Get all the branches
    try (ByteSequence stream = new ByteSequence(code)) {
      int idx = 0;
      for (; idx < start && stream.available() > 0; idx++)
        stream.readByte();

      for (; idx < end && stream.available() > 0; idx++) {
        BranchInfo brInfo = isBranch(stream, stream.getIndex(), wide);
        if (brInfo != null)
          branches.add(brInfo);
      }
    } catch(Exception e) {
      e.printStackTrace();
    }

    HashMap<Integer, BasicBlock> startBciToBB = new HashMap<>();
    HashMap<Integer, BasicBlock> endBciToBB = new HashMap<>();
    int numBB = 0;
    ArrayList<Integer> leaders = new ArrayList<>();
    ArrayList<Boolean> isBranchOrTarget = new ArrayList<>();

    for (BranchInfo br : branches) {
      leaders.add(br.pc + br.codelen);
      isBranchOrTarget.add(true);
      int targetOpcode = (code[br.target] & 0xff);
      int targetSize = 0;
      System.out.printf("target %d pc %d len %d\n", br.target, br.pc, br.codelen);
      if (Const.getNoOfOperands(targetOpcode) > 0) {
        for (int i = 0; i < Const.getOperandTypeCount(targetOpcode); i++) {
            switch (Const.getOperandType(targetOpcode, i)) {
            case Const.T_BYTE:
                targetSize += 1;
                break;
            case Const.T_SHORT:
                targetSize += 2;
                break;
            case Const.T_INT:
                targetSize += 4;
                break;
            default: // Never reached
                throw new IllegalStateException("Unreachable default case reached!");
            }
        }
      }
      System.out.printf("target %d (%s) targetSize %d pc %d len %d\n", br.target, Const.getOpcodeName(targetOpcode), targetSize, br.pc, br.codelen);
      leaders.add(br.target);
      isBranchOrTarget.add(false);
    }
    leaders.sort(null);
    int startBci = 0;
    ArrayList<BasicBlock> basicBlocks = new ArrayList<>();
    BasicBlock currBasicBlock = null;
    for (int i = 0; i < leaders.size(); i++) {
      currBasicBlock = new BasicBlock(numBB, startBci);
      basicBlocks.add(currBasicBlock);
      int leader = leaders.get(i);
      int endBci = leader;
      System.out.println("leader " + leader + " #"+i);

      currBasicBlock.setEnd(endBci);

      System.out.printf("BB (%d): [%d, %d]\n", numBB, startBci, endBci);
      startBci = endBci;
      numBB++;
    }

    if (startBci < end) {
      System.out.printf("BB (%d): [%d, %d]\n", numBB, startBci, end);
      BasicBlock last = new BasicBlock(numBB, startBci);
      basicBlocks.add(last);
      last.setEnd(end);
    }

    ArrayList<BasicBlock> nonEmptyBlocks = new ArrayList<>();
    for (int bi = 0; bi < basicBlocks.size(); bi++) {
      if (basicBlocks.get(bi).size() != 0) {
        nonEmptyBlocks.add(basicBlocks.get(bi));
      }
    }

    basicBlocks = nonEmptyBlocks;

    //Check that basicBlocks covers full code
    int prevEnd = 0;
    for (BasicBlock b : basicBlocks) {
      if (b.start != prevEnd) {
        // assert false: "err";
        System.out.println("err");
        System.exit(0);
      }

      prevEnd = b.getEnd();
    }

    for (BasicBlock b : basicBlocks) {
      startBciToBB.put(b.start, b);
      endBciToBB.put(b.getEnd(), b);
      System.out.printf("1512: BB (%d): [%d, %d] size %d\n", b.number, b.start, b.getEnd(), b.size());
    }

    for (BranchInfo br : branches) {
      BasicBlock parent = endBciToBB.get(br.pc + br.codelen);
      BasicBlock child = startBciToBB.get(br.target);
      
      System.out.println("1519: " + (br.pc + br.codelen) + " " + br.target);
      parent.addOut(child);
      child.addIn(parent);

      if (br.isConditional) {
        BasicBlock child2 = startBciToBB.get(br.pc + br.codelen);
        parent.addOut(child2);
        child2.addIn(parent);
        System.out.println("1527: " + child.getEnd());
        if (child.getEnd() < end) {
          BasicBlock continuation = startBciToBB.get(child.getEnd());
          child.addOut(continuation);
          continuation.addIn(child);
        }
      }
    }

    for (BasicBlock bb : startBciToBB.values()) {
      System.out.print(bb.number + " -> ");
      for (BasicBlock out : bb.getOuts()) {
        System.out.print(out.number + ", ");
      }

      System.out.println("");
    }

    for (BasicBlock b : basicBlocks) {
      System.out.printf("1546: BB (%d): [%d, %d]\n", b.number, b.start, b.getEnd());
      //TODO: Fix for wide jumps
      int index = b.getEnd() - 3;
      int opcode = code[index] & 0xff;
      int target = -1;
      boolean conditional = false;
      switch(opcode) {
        case Const.GOTO:
        case Const.JSR: {
            ByteBuffer bb = ByteBuffer.allocate(2);
            bb.order(ByteOrder.BIG_ENDIAN);
            bb.put(code[index + 1]);
            bb.put(code[index + 2]);
            target = bb.getShort(0);
            target = index + target;
            conditional = false;
            break;
        }
        case Const.GOTO_W:
        case Const.JSR_W:
            target = ((code[index + 1] & 0xff) << 24) | ((code[index + 2] & 0xff) << 16) | ((code[index + 3] & 0xff) << 8) | (code[index + 4] & 0xff);
            target = index + target;
            conditional = false;
            break;
        case Const.IFEQ:
        case Const.IFGE:
        case Const.IFGT:
        case Const.IFLE:
        case Const.IFLT:
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
        case Const.IF_ICMPNE: {
          ByteBuffer bb = ByteBuffer.allocate(2);
          bb.order(ByteOrder.BIG_ENDIAN);
          bb.put(code[index + 1]);
          bb.put(code[index + 2]);
          target = bb.getShort(0);
            target = index + target;
          conditional = true;
          break;
        }
        /*
          * 32-bit wide jumps
          */
        default:
          //Not a conditional
          target = -1;
          break;
      }

      if (target >= code.length) {
        System.out.println("1603: err target " + target + " " + Const.getOpcodeName(opcode) + " at " + index);
        System.exit(0);
      }
      if (target != -1) {
        boolean found = false;
        for (BasicBlock out : b.getOuts()) {
          if (out.start == target) {
            found = true;
            break;
          }
        }
        if (!found) {
          System.out.println("1615: err not found " + target);
          System.exit(0);
        }     
      }
    }
  }

  public static void analyzeMethod(JavaMethod method, CallFrame frame, StaticValue staticValues, JavaClassCollection classCollection) {
    Code code = method.getMethod().getCode();

    //Create the basic block graph
    System.out.println(method.getFullName() + " " + code.toString(true));
    createBasicBlocks(code.getCode(), 0, code.getCode().length);
    // ConstantPool constPool = code.getConstantPool();
    // Stack<Var> operandStack = new Stack<>();
    
    // LocalVars localVars = new LocalVars(code.getMaxLocals());
    // if (code.getLocalVariableTable() != null) {
    //   //Following initialization should go inside LocalVars constructor
    //   for (LocalVariable v : code.getLocalVariableTable()) {
    //     if (localVars.get(v.getIndex()) == null) {
    //       localVars.set(v.getIndex(), new ArrayList<LocalVar>());
    //     }
    //     localVars.get(v.getIndex()).add(new LocalVar(classCollection.javaTypeForSignature(v.getSignature()), v.getIndex(), v.getStartPC(), v.getLength()));
    //   }
    // }
    // ConstantVal[] constants = new ConstantVal[constPool.getLength()];
    // // for (int c = 0; c < constPool.getLength(); c++) {
    // //   Constant co = constPool.getConstant(c);
    // //   System.out.println("484: " + co.toString());
    // // }

    // boolean print = method.getFullName().contains("org.apache.lucene.queryParser.QueryParser.jj_2_1");
    // if (print) System.out.println(method.getFullName() + " " + code.toString(true));
    
    // try (ByteSequence stream = new ByteSequence(code.getCode())) {
    //     for (int bci = 0; stream.available() > 0; bci++) { //stream.available() > 0
    //       // if (i == event.bci_) 
    //       //   System.out.println(Const.getOpcodeName(code.getCode()[i]));
    //       createThreeAddressCode(stream, bci, stream.getIndex(), constPool,
    //                              operandStack, localVars, constants, classCollection, print);
    //     }
    // } catch (final IOException e) {
    //    e.printStackTrace();
    // }
    // if (print)
    // System.out.println("\n");
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
