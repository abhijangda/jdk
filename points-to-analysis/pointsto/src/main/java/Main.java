import java.io.IOException;
import java.util.*;

import org.apache.bcel.classfile.*;
import soot.*;
import soot.options.Options;

public class Main {
  public static boolean DEBUG_PRINT = true;
  public static void debugLog(String fmt, Object... args) {
    if (DEBUG_PRINT) {
      System.err.printf(fmt, args);
    }
  }

  public static void debugAssert(boolean b, String fmt, Object... args) {
    if (DEBUG_PRINT) {
      if (!b) {
        System.err.printf(fmt, args);
        throw new AssertionError(b);
      }
    }
  }

  public static String pathToPackage(String path) {
    return path.replace("/", ".");
  }
  
  public static String packageToPath(String path) {
    return path.replace(".", "/");
  }

  public static String methodFullName(SootMethod meth) {
    return meth.getDeclaringClass().getName() + "." + meth.getName() + meth.getSignature();
  }
  // public static ArrayList<Method> findMainMethods(String jarFile, JarFile jar) {
  //   ArrayList<Method> mainMethods = new ArrayList<>();
  //   try {
  //     Enumeration<JarEntry> entries = jar.entries();
  //     while (entries.hasMoreElements()) {
  //         JarEntry entry = entries.nextElement();
  //         if (!entry.getName().endsWith(".class")) {
  //             continue;
  //         }

  //         ClassParser parser = new ClassParser(jarFile, entry.getName());
  //         JavaClass javaClass = parser.parse();
  //         // System.out.println(javaClass.getClassName());
  //         for (Method m : javaClass.getMethods()) {
  //           // System.out.println(m.getName());
  //           if (m.getName().equals("main") && m.isStatic() && m.isPublic() && 
  //               m.getReturnType() == Type.VOID) {
  //               mainMethods.add(m);
  //           }
  //         }
  //     }
  //   } catch (Exception e) {

  //   }

  //   return mainMethods;
  // }

  public static boolean methodToCare(String name) {
    return !name.equals("NULL") && !name.contains("java.") && !name.contains("jdk.") && !name.contains("sun.") && !name.contains("<clinit>");
  }

  public static void main(String[] args) throws ClassFormatException, IOException {
    //Read the jarfile
    String jarFile = "/mnt/homes/aabhinav/jdk/dacapo-9.12-MR1-bach.jar";
    Options.v().parse(args);
    Options.v().set_include(Arrays.asList("org.dacapo.harness."));
    JavaClassCollection javaClasses = JavaClassCollection.loadFromJar(jarFile);
    System.out.println("Loaded " + javaClasses.values().size() + " classes");
    //Read and process heap events
    String heapEventsFile = "/mnt/homes/aabhinav/jdk/heap-events";
    HashMap<String, ArrayList<HeapEvent>> heapEvents = HeapEvent.processHeapEventsFile(heapEventsFile, javaClasses);
    System.out.println("Loaded " + heapEvents.size() + " heapevents");
    
    // CallGraphAnalysis.callGraph(heapEvents, javaClasses);

    
    // //Find all main methods in the jar and also find those method in the heap events
    // ArrayList<Method> mainMethods = findMainMethods(jarFile, jar);
    // HashMap<String, Method> methodNameMap = new HashMap<>();
    // createMethodNameMap(jarFile, methodNameMap);
    // 

    // int ii = 0;
    // for (HeapEvent he : heapEvents.get(threadWithMaxEvents)) {
    //   if (methodToCare(he.method_)) {
    //     System.out.println(he.toString());
        
    //     mainThread = threadWithMaxEvents;
    //     heapEventIdx = ii;
    //     break;
    //   }
    //   ii++;
    // }
    // // for (Method mainMethod : mainMethods) {
    // //   String mainClassName = mainMethod.getClass().getName();
    //   // System.out.println(mainClassName);
    //   // for (String thread : heapEvents.keySet()) {
    //   //   int i = 0;
    //   //   for (HeapEvent he : heapEvents.get(thread)) {
    //   //     //TODO: also check for the class obtained from above
    //   //     if (he.method_.contains(".main") && he.method_.contains("org.dacapo.harness.TestHarness.main")) { //&& he.method_.contains(mainClassName)) {
    //   //       mainThread = thread;
    //   //       heapEventIdx = i;
    //   //       System.out.println(mainThread + " " + heapEventIdx + " " + he.toString());
    //   //     }
    //   //     i++;
    //   //   }

    //   //   // if (heapEventIdx != -1) break;
    //   // }
    // // }

    // assert(mainThread != "");
    // assert(heapEventIdx != -1);

    // System.out.println(mainThread + " " + heapEvents.get(mainThread).get(heapEventIdx).toString());

    // callGraph(methodNameMap, heapEvents, mainThread, heapEventIdx);
    // // System.out.println("17\n");
    // // /*An existing class can be parsed with ClassParser */
    // // ClassParser parser=new ClassParser(App.class.getResourceAsStream("App.class"), "App.class");
    // // JavaClass javaClass=parser.parse();
    
    // // System.out.println("*******Constant Pool*********");
    // // System.out.println(javaClass.getConstantPool());
    
    // // System.out.println("*******Fields*********");
    // // System.out.println(Arrays.toString(javaClass.getFields()));
    // // System.out.println();
    
    // // System.out.println("*******Methods*********");
    // // System.out.println(Arrays.toString(javaClass.getMethods()));
    
    // // for(Method method:javaClass.getMethods()){
    // //     System.out.println(method);
    // //     System.out.println(method.getCode());
    // // }
  }
}
