package main;

import main.HeapEvent;

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

class JavaStackElement {
  String method;
  int bci;

  JavaStackElement(String m, int b) {
    method = m;
    bci = b;
  }
}

public class Main {
  public static ArrayList<Method> findMainMethods(String jarFile, JarFile jar) {
    ArrayList<Method> mainMethods = new ArrayList<>();
    try {
      Enumeration<JarEntry> entries = jar.entries();
      while (entries.hasMoreElements()) {
          JarEntry entry = entries.nextElement();
          if (!entry.getName().endsWith(".class")) {
              continue;
          }

          ClassParser parser = new ClassParser(jarFile, entry.getName());
          JavaClass javaClass = parser.parse();
          // System.out.println(javaClass.getClassName());
          for (Method m : javaClass.getMethods()) {
            // System.out.println(m.getName());
            if (m.getName().equals("main") && m.isStatic() && m.isPublic() && 
                m.getReturnType() == Type.VOID) {
                mainMethods.add(m);
            }
          }
      }
    } catch (Exception e) {

    }

    return mainMethods;
  }

      /**
     * Extracts a zip entry (file entry)
     * @param zipIn
     * @param filePath
     * @throws IOException
     */
    public static void extractFile(JarInputStream zipIn, String filePath) throws IOException {
          BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(filePath));
          byte[] bytesIn = new byte[1024*1024];
          int read = 0;
          while ((read = zipIn.read(bytesIn)) != -1) {
              bos.write(bytesIn, 0, read);
          }
          bos.close();
      }
  
  public static void createMethodNameMap(String jarFile, HashMap<String, Method> methodNameMap) {
    try {
      JarFile jar = new JarFile(jarFile);
      JarInputStream jarIn = new JarInputStream(new FileInputStream (jarFile));
      JarEntry entry = jarIn.getNextJarEntry();
      while (entry != null) {
          if (entry.getName().endsWith(".class")) {
            ClassParser parser = new ClassParser(jarIn, entry.getName());
            JavaClass javaClass = parser.parse();
            // System.out.println(javaClass.getClassName());
            for (Method m : javaClass.getMethods()) {
              String methodName = javaClass.getClassName() +
                                  "." + m.getName() + m.getSignature();
              // if (methodName.contains("org.apache.lucene.store.FSDirectory."))
              //   System.out.println(methodName);
              methodNameMap.put(methodName, m);
            }
          } else if (entry.getName().endsWith(".jar")) {
            Path entryPath = Paths.get(entry.getName());
            String extractedJarFile = "/tmp/"+entryPath.getFileName();
            extractFile(jarIn, extractedJarFile);
            createMethodNameMap(extractedJarFile, methodNameMap);
          }
          jarIn.closeEntry();
          entry = jarIn.getNextJarEntry();
      }
      jarIn.close();
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  public static boolean wide;

  public static String getInvokes(final ByteSequence bytes, int bcIndex, final ConstantPool constantPool, 
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

  public static String getInvokes(final byte[] code, final ConstantPool constantPool, HashMap<Integer, String> invokeMethods) {
    final StringBuilder buf = new StringBuilder(code.length * 20); // Should be sufficient // CHECKSTYLE IGNORE MagicNumber
    try (ByteSequence stream = new ByteSequence(code)) {
        for (int i = 0; stream.available() > 0; i++) {
          getInvokes(stream, i, constantPool, invokeMethods);
        }
    } catch (final IOException e) {
        throw new ClassFormatException("Byte code error: " + buf.toString(), e);
    }
    return buf.toString();
  }

  public static HashMap<Method, HashMap<Integer, String>> invokeBCInMethod = new HashMap<>();
  public static HashMap<Integer, String> findInvokeBytecode(Method method) {
    if (invokeBCInMethod.containsKey(method))
      return invokeBCInMethod.get(method);
    Code code = method.getCode();
    HashMap<Integer, String> invokeMethods = new HashMap<>();
    getInvokes(code.getCode(), code.getConstantPool(), invokeMethods);
    invokeBCInMethod.put(method, invokeMethods);
    return invokeMethods;
  }

  public static boolean methodToCare(String name) {
    return !name.equals("NULL") && !name.contains("java.") && !name.contains("jdk.") && !name.contains("sun.") && !name.contains("<clinit>");
  }

  public static boolean isMethodReachable(HashMap<String, Method> methodNameMap, Stack<JavaStackElement> stack, String startMethod, int startBytecode, String endMethod) {
    Method m = methodNameMap.get(startMethod);
    if (m == null) {
      System.out.println("not found " + startMethod);
      return false;
    }
    if (m.isAbstract())
      return false;
    HashMap<Integer, String> invokeBC = findInvokeBytecode(m);
    assert(methodNameMap.containsKey(startMethod));
    assert(methodNameMap.containsKey(endMethod));

    for (Map.Entry<Integer, String> entry : invokeBC.entrySet()) {
      if (startBytecode <= entry.getKey() && methodToCare(entry.getValue())) {
        if (endMethod.equals(entry.getValue())) {
          stack.push(new JavaStackElement(startMethod, entry.getKey()));
          return true;
        } else {
          stack.push(new JavaStackElement(startMethod, entry.getKey()));
          if (isMethodReachable(methodNameMap, stack, entry.getValue(), 0, endMethod))
            return true;
          stack.pop();
        }
      }
    }

    return false;
  }

  public static void callGraph(HashMap<String, Method> methodNameMap,
                               HashMap<String, ArrayList<HeapEvent>> heapEvents, 
                               String mainThread, int heapEventIdx) {
    //Check all methods of event in the main thread are in methodNameMap
    ArrayList<HeapEvent> mainThreadEvents = heapEvents.get(mainThread);

    for (int i = heapEventIdx; i < mainThreadEvents.size(); i++) {
      HeapEvent he = mainThreadEvents.get(i);
      if (methodToCare(he.method_) && !methodNameMap.containsKey(he.method_)) {
        System.out.println("not found: " + he.method_);
      // } else if (methodNameMap.containsKey(he.method_)) {
        // HashMap<Integer, String> invokeMethods = findInvokeBytecode(methodNameMap.get(he.method_));
        // System.out.println(methodNameMap.get(he.method_).getCode().toString(true));
        // for (Map.Entry<Integer, String> e : invokeMethods.entrySet())
        //   System.out.println(e.getKey() + " " + e.getValue());
      // }
      }
    }

    Stack<JavaStackElement> callStack;
    HeapEvent prevHe = mainThreadEvents.get(heapEventIdx);
    Method prevMeth = methodNameMap.get(prevHe.method_);
    
    System.out.println("starting from " + prevHe.method_);
    for (int idx = heapEventIdx + 1; idx < mainThreadEvents.size(); idx++) {
      for (int idx2 = idx; idx2 < mainThreadEvents.size(); idx2++) {
        String nextMethod = mainThreadEvents.get(idx2).method_;
        if (!nextMethod.equals(prevHe.method_) && methodToCare(nextMethod)) {
          idx = idx2;
          break;
        }
      }
      
      HeapEvent he = mainThreadEvents.get(idx);
      HashMap<Integer, String> invokeBC = findInvokeBytecode(prevMeth);
      boolean found = false;
      Stack<JavaStackElement> newCallStack = new Stack<>();
      found = isMethodReachable(methodNameMap, newCallStack, prevHe.method_, 0, he.method_);

      if (!found) {
        System.out.println("Didn't find " + he.method_);
        System.out.println(prevMeth.getCode().toString(true));
        break;
      }

      prevHe = he;
      prevMeth = methodNameMap.get(he.method_);
      System.out.println("found: " + he.method_ + " " + newCallStack.size());
    }
  }

  public static void main(String[] args) throws ClassFormatException, IOException {
    //Read and process heap events
    String heapEventsFile = "/mnt/homes/aabhinav/jdk/heap-events";
    HashMap<String, ArrayList<HeapEvent>> heapEvents = HeapEvent.processHeapEventsFile(heapEventsFile);
    System.out.println("HeapEvents loaded");
    //Read the jarfile
    String jarFile = "/mnt/homes/aabhinav/jdk/dacapo-9.12-MR1-bach.jar";
    
    
    //Find all main methods in the jar and find that method in the heap events
    // ArrayList<Method> mainMethods = findMainMethods(jarFile, jar);
    HashMap<String, Method> methodNameMap = new HashMap<>();
    createMethodNameMap(jarFile, methodNameMap);
    String mainThread = "";
    int heapEventIdx = -1;
    String threadWithMaxEvents = "";

    for (String thread : heapEvents.keySet()) {
      if (threadWithMaxEvents == "") {
        threadWithMaxEvents = thread;
      }
      if (heapEvents.get(threadWithMaxEvents).size() < heapEvents.get(thread).size())
        threadWithMaxEvents = thread;

      System.out.println(thread + ": " + heapEvents.get(thread).size() + " events");
    }

    System.out.println("threadWithMaxEvents " + threadWithMaxEvents);

    int ii = 0;
    for (HeapEvent he : heapEvents.get(threadWithMaxEvents)) {
      if (methodToCare(he.method_)) {
        System.out.println(he.toString());
        
        mainThread = threadWithMaxEvents;
        heapEventIdx = ii;
        break;
      }
      ii++;
    }
    // for (Method mainMethod : mainMethods) {
    //   String mainClassName = mainMethod.getClass().getName();
      // System.out.println(mainClassName);
      // for (String thread : heapEvents.keySet()) {
      //   int i = 0;
      //   for (HeapEvent he : heapEvents.get(thread)) {
      //     //TODO: also check for the class obtained from above
      //     if (he.method_.contains(".main") && he.method_.contains("org.dacapo.harness.TestHarness.main")) { //&& he.method_.contains(mainClassName)) {
      //       mainThread = thread;
      //       heapEventIdx = i;
      //       System.out.println(mainThread + " " + heapEventIdx + " " + he.toString());
      //     }
      //     i++;
      //   }

      //   // if (heapEventIdx != -1) break;
      // }
    // }

    assert(mainThread != "");
    assert(heapEventIdx != -1);

    System.out.println(mainThread + " " + heapEvents.get(mainThread).get(heapEventIdx).toString());

    callGraph(methodNameMap, heapEvents, mainThread, heapEventIdx);
    // System.out.println("17\n");
    // /*An existing class can be parsed with ClassParser */
    // ClassParser parser=new ClassParser(App.class.getResourceAsStream("App.class"), "App.class");
    // JavaClass javaClass=parser.parse();
    
    // System.out.println("*******Constant Pool*********");
    // System.out.println(javaClass.getConstantPool());
    
    // System.out.println("*******Fields*********");
    // System.out.println(Arrays.toString(javaClass.getFields()));
    // System.out.println();
    
    // System.out.println("*******Methods*********");
    // System.out.println(Arrays.toString(javaClass.getMethods()));
    
    // for(Method method:javaClass.getMethods()){
    //     System.out.println(method);
    //     System.out.println(method.getCode());
    // }
  }
}
