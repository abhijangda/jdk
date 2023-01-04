import java.util.*;

import classcollections.*;
import javaheap.HeapEvent;
import javaheap.JavaHeap;
import soot.SootMethod;
import utils.Utils;

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
    JavaHeap javaHeap = new JavaHeap();

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
      javaHeap.updateWithHeapEvent(he);
      if (he.method != null && he.method.getDeclaringClass().getName().contains("lusearch"))
        break;

      heapEventIdx++;
    }

    System.out.printf("Starting heap event from %d with method %s\n", 
    heapEventIdx, Utils.methodFullName(mainThreadEvents.get(heapEventIdx).method));

    Stack<CallFrame> callStack = new Stack<>();
    StaticValue staticValues = new StaticValue();
    HeapEvent currEvent = mainThreadEvents.get(heapEventIdx);
    Stack<Pair<CallFrame, Integer>> remainingFrames = new Stack<>();
    CallFrame rootFrame = new CallFrame(mainThreadEvents.get(heapEventIdx), null, null);
    CallGraphNode rootNode = new CallGraphNode(rootFrame, null);
    HashMap<CallFrame, CallGraphNode> frameToGraphNode = new HashMap<>();

    remainingFrames.push(Pair.v(rootFrame, 0));
    callStack.push(rootFrame);
    frameToGraphNode.put(rootFrame, rootNode);
    int iterations = 0;
    while (!callStack.isEmpty()) {
      CallFrame frame = callStack.peek();
      if (!frame.hasNextInvokeStmt()) {
        callStack.pop();
        continue;
      }
      
      // while (mainThreadEvents.get(heapEventIdx).method != null && !Utils.methodToCare(mainThreadEvents.get(heapEventIdx).method)) {
      //   heapEventIdx++;
      // }

      while(mainThreadEvents.get(heapEventIdx).method == frame.method.sootMethod) {
        javaHeap.updateWithHeapEvent(mainThreadEvents.get(heapEventIdx));
        // frame.updateValuesWithHeapEvent(mainThreadEvents.get(heapEventIdx));
        heapEventIdx++;
      }
      
      // CallGraphNode parentNode = frameToGraphNode.get(frame);
      // CallFrame nextFrame = frame.nextInvokeMethod();
      // if (nextFrame != null && nextFrame.method != null && nextFrame.method != frame.method &&
      //     ((frame.root != null && nextFrame.method != frame.root.method) || frame.root == null) &&
      //     !Utils.methodFullName(nextFrame.method.sootMethod).contains("java.lang.SecurityManager.checkPermission") &&
      //     Utils.methodToCare(frame.method.sootMethod)) {
      //   //Skip recursion
      //   callStack.push(nextFrame);
      //   CallGraphNode childNode = new CallGraphNode(nextFrame, parentNode);
      //   parentNode.addChild(childNode);
      //   frameToGraphNode.put(nextFrame, childNode);
      // } else {
      // }
    }
    
    System.out.println("Edges:");
    System.out.println(rootNode.edgesToString()); 
    //String callGraphTxt = rootNode.toString();

    // System.out.println(callGraphTxt);
    // for (int iterations = 0; heapEventIdx < mainThreadEvents.size(); heapEventIdx++,iterations++) {
    //   for (int idx2 = heapEventIdx + 1; idx2 < mainThreadEvents.size(); idx2++) {
    //     SootMethod nextMethod = mainThreadEvents.get(idx2).method;
    //     if (nextMethod != null && methodToCare(nextMethod)) {
    //       heapEventIdx = idx2;
    //       break;
    //     }
    //   }

    //   ShimpleMethod shimpleMethod = ParsedMethodMap.v().getOrParseToShimple(currEvent.method);
      
    //   // shimpleMethod.updateValuesWithHeapEvent(currEvent);
      
    //   // HeapEvent nextEvent = mainThreadEvents.get(heapEventIdx);
    //   // currEvent = nextEvent;
    // }
  }
}