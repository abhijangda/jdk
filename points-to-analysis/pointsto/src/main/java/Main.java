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

  public static HashMap<Method, HashMap<Integer, String>> invokeBCInMethod = new HashMap<>();
  public static HashMap<Integer, String> findInvokeBytecode(Method method) {
    if (invokeBCInMethod.containsKey(method))
      return invokeBCInMethod.get(method);
    Code code = method.getCode();
    HashMap<Integer, String> invokeMethods = new HashMap<>();
    BytecodeAnalyzer.getInvokes(code.getCode(), code.getConstantPool(), invokeMethods);
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
