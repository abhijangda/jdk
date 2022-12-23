import java.util.*;

import jas.Method;
import soot.SootMethod;
import soot.shimple.ShimpleBody;

public class CallGraphAnalysis {
  public static boolean methodToCare(String name) {
    return !name.equals("NULL") && !name.contains("java.") && !name.contains("jdk.") && !name.contains("sun.") && !name.contains("<clinit>");
  }
  
  public static boolean methodToCare(SootMethod m) {
    return methodToCare(m.getClass().getName());
  }

  public static void callGraph(HashMap<String, ArrayList<HeapEvent>> heapEvents, JavaClassCollection classCollection, BCELClassCollection bcelClassCollection) {
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
      if (he.method_ != null && he.method_.getDeclaringClass().getName().contains("lusearch"))
        break;
      heapEventIdx++;
    }

    System.out.printf("Starting heap event from %d with method %s\n", 
    heapEventIdx, Main.methodFullName(mainThreadEvents.get(heapEventIdx).method_));

    Stack<CallFrame> callStack = new Stack<>();
    StaticValue staticValues = new StaticValue();
    HeapEvent currEvent = mainThreadEvents.get(heapEventIdx);
    
    for (int iterations = 0; heapEventIdx < mainThreadEvents.size(); heapEventIdx++,iterations++) {
      for (int idx2 = heapEventIdx + 1; idx2 < mainThreadEvents.size(); idx2++) {
        SootMethod nextMethod = mainThreadEvents.get(idx2).method_;
        if (nextMethod != null && methodToCare(nextMethod)) {
          heapEventIdx = idx2;
          break;
        }
      }

      if (!ParsedMethodMap.v().containsKey(currEvent.method_)) {
        ShimpleMethod shimpleBody = ParsedMethodMap.v().getOrParseToShimple(currEvent.method_);

      }

      HeapEvent nextEvent = mainThreadEvents.get(heapEventIdx);
      currEvent = nextEvent;
    }
   }
}
