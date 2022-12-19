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
import java.util.function.IntToLongFunction;
import java.io.*;
import java.util.zip.ZipInputStream;

public class CallGraphAnalysis {
  // public static boolean isMethodReachable(HashMap<String, Method> methodNameMap, Stack<JavaStackElement> stack, String startMethod, int startBytecode, String endMethod) {
  //   Method m = methodNameMap.get(startMethod);
  //   if (m == null) {
  //     System.out.println("not found " + startMethod);
  //     return false;
  //   }
  //   if (m.isAbstract())
  //     return false;
  //   HashMap<Integer, String> invokeBC = findInvokeBytecode(m);
  //   assert(methodNameMap.containsKey(startMethod));
  //   assert(methodNameMap.containsKey(endMethod));

  //   for (Map.Entry<Integer, String> entry : invokeBC.entrySet()) {
  //     if (startBytecode <= entry.getKey() && methodToCare(entry.getValue())) {
  //       if (endMethod.equals(entry.getValue())) {
  //         stack.push(new JavaStackElement(startMethod, entry.getKey()));
  //         return true;
  //       } else {
  //         stack.push(new JavaStackElement(startMethod, entry.getKey()));
  //         if (isMethodReachable(methodNameMap, stack, entry.getValue(), 0, endMethod))
  //           return true;
  //         stack.pop();
  //       }
  //     }
  //   }

  //   return false;
  // }

  public static boolean methodToCare(String name) {
    return !name.equals("NULL") && !name.contains("java.") && !name.contains("jdk.") && !name.contains("sun.") && !name.contains("<clinit>");
  }
  
  public static boolean methodToCare(JavaMethod m) {
    return methodToCare(m.getKlass().getClassName());
  }

  public static void callGraph(HashMap<String, ArrayList<HeapEvent>> heapEvents, JavaClassCollection classCollection) {
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
    
    mainThread = threadWithMaxEvents;
    ArrayList<HeapEvent> mainThreadEvents = heapEvents.get(mainThread);

    heapEventIdx = 0;
    for (HeapEvent he : mainThreadEvents) {
      if (he.method_ != null && he.method_.getKlass().getClassName().contains("lusearch"))
        break;
      heapEventIdx++;
    }

    System.out.printf("Starting heap event from %d with method %s\n", heapEventIdx, mainThreadEvents.get(heapEventIdx).method_.getFullName());

    Stack<CallFrame> callStack = new Stack<>();
    StaticValue staticValues = new StaticValue();
    HeapEvent currEvent = mainThreadEvents.get(heapEventIdx);
    
    for (int iterations = 0; heapEventIdx < mainThreadEvents.size(); heapEventIdx++,iterations++) {
      for (int idx2 = heapEventIdx + 1; idx2 < mainThreadEvents.size(); idx2++) {
        JavaMethod nextMethod = mainThreadEvents.get(idx2).method_;
        if (nextMethod != null && methodToCare(nextMethod)) {
          heapEventIdx = idx2;
          break;
        }
      }

      System.out.printf("%d: %s\n", heapEventIdx, currEvent.toString());
      HeapEvent nextEvent = mainThreadEvents.get(heapEventIdx);
      BytecodeAnalyzer.analyzeMethod(nextEvent.method_, null, staticValues, classCollection);
      // BytecodeAnalyzer.analyzeEvent(nextEvent, null, staticValues);

      if (nextEvent.method_.getMethod() == currEvent.method_.getMethod()) {
        //Same method
        //TODO: Assuming no recursions
        // BytecodeAnalyzer.analyzeEvent(mainThreadEvents.get(heapEventIdx), null, staticValues);
      } else {
        // BytecodeAnalyzer.analyzeMethod(mainThreadEvents.get(heapEventIdx), );
      }

      currEvent = nextEvent;
      if (iterations > 1000) break;
    }
   }
}
