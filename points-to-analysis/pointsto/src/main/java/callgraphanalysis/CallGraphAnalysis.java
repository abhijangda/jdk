package callgraphanalysis;
import java.util.*;

import org.slf4j.helpers.Util;

import callstack.StaticFieldValues;
import classcollections.*;
import javaheap.HeapEvent;
import javaheap.JavaHeap;
import soot.SootMethod;
import utils.ArrayListIterator;
import utils.Pair;
import utils.Utils;
import parsedmethod.*;
import callstack.*;

public class CallGraphAnalysis {
  public static boolean methodToCare(String name) {
    return !name.equals("NULL") && !name.contains("java.") && !name.contains("jdk.") && !name.contains("sun.") && !name.contains("<clinit>");
  }
  
  public static boolean methodToCare(SootMethod m) {
    return methodToCare(m.getClass().getName());
  }

  public static void callGraph(HashMap<String, ArrayList<HeapEvent>> heapEvents, JavaClassCollection classCollection, BCELClassCollection bcelClassCollection) {
    String mainThread = "";
    String threadWithMaxEvents = "";
    JavaHeap javaHeap = JavaHeap.v();

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

    ArrayListIterator<HeapEvent> eventIterator = new ArrayListIterator<>(mainThreadEvents);
    HeapEvent startEvent = null;
    while(eventIterator.hasNext()) {
      startEvent = eventIterator.get();
      javaHeap.update(startEvent);
      if (startEvent.method != null && startEvent.method.getDeclaringClass().getName().contains("lusearch"))
        break;
      eventIterator.moveNext();
    }

    System.out.printf("Starting heap event from with method %s\n", 
    startEvent, Utils.methodFullName(startEvent.method));

    Stack<CallFrame> callStack = new Stack<>();
    HeapEvent currEvent = startEvent;
    Stack<Pair<CallFrame, Integer>> remainingFrames = new Stack<>();
    CallFrame rootFrame = new CallFrame(startEvent, null, null, null);
    CallGraphNode rootNode = new CallGraphNode(rootFrame, null);
    HashMap<CallFrame, CallGraphNode> frameToGraphNode = new HashMap<>();

    remainingFrames.push(Pair.v(rootFrame, 0));
    callStack.push(rootFrame);
    frameToGraphNode.put(rootFrame, rootNode);
    int iterations = 0;
    while (!callStack.isEmpty() && iterations++ < 1000) {
      CallFrame frame = callStack.peek();
      if (frame.parent != null)
        Utils.debugPrintln("parent frame " + frame.parent.toString());
      Utils.debugPrintln("current frame " + frame + " iterations " + iterations);
      currEvent = eventIterator.get();
      Utils.debugPrintln("currevent " + currEvent.toString());
      // if (frame.canPrint) {
      //   Utils.debugPrintln(frame.method.basicBlockStr());
      //   return;
      // }
      if (!frame.hasNextInvokeStmt()) {
        if (frame.canPrint) return;
        callStack.pop();
        continue;
      }
      
      while (!Utils.methodToCare(currEvent.method) ||
            //  currEvent.methodStr.contains("org.apache.lucene.store.FSDirectory") ||
             currEvent.methodStr.contains("org.apache.lucene.analysis.CharArraySet.add")) {
        javaHeap.update(currEvent);
        eventIterator.moveNext();
        currEvent = eventIterator.get();
      }
      
      Utils.debugPrintln("new curr event" + currEvent.toString());
      // while(mainThreadEvents.get(heapEventIdx).method == frame.method.sootMethod) {
      //   javaHeap.updateWithHeapEvent(mainThreadEvents.get(heapEventIdx));
      //   Utils.debugPrintln(mainThreadEvents.get(heapEventIdx).toString());
      //   frame.updateValuesWithHeapEvent(mainThreadEvents.get(heapEventIdx));
      //   heapEventIdx++;
      // }
      CallGraphNode parentNode = frameToGraphNode.get(frame);
      CallFrame nextFrame = frame.nextInvokeMethod(eventIterator);
      // if (frame.method.fullname().contains("QueryProcessor.<init>")) {
      //   while (!Utils.methodFullName(mainThreadEvents.get(heapEventIdx).method).contains("QueryProcessor.run")) {
      //     Utils.debugPrintln("currevent " + mainThreadEvents.get(heapEventIdx));
      //     if (mainThreadEvents.get(heapEventIdx).methodStr.contains("QueryProcessor.<init>"))
      //       frame.updateValuesWithHeapEvent(mainThreadEvents.get(heapEventIdx));
      //     javaHeap.updateWithHeapEvent(mainThreadEvents.get(heapEventIdx));
      //     heapEventIdx++;
      //   } 
      //   callStack.pop();
      //   continue;
      // }
      if (nextFrame != null && nextFrame.method != null && nextFrame.method != frame.method &&
          ((frame.parent != null && nextFrame.method != frame.parent.method) || frame.parent == null) &&
          !Utils.methodFullName(nextFrame.method.sootMethod).contains("java.lang.SecurityManager.checkPermission") &&
          Utils.methodToCare(frame.method.sootMethod)) {
        //Skip recursion
        Utils.debugPrintln("next frame: " + utils.Utils.methodFullName(nextFrame.method.sootMethod) + " parent " + ((frame == null) ?  "" : frame.method.fullname()));
        if (nextFrame.method.sootMethod.getDeclaringClass().getName().contains("QueryProcessor") &&
          nextFrame.method.sootMethod.getName().contains("run")) {
          // Utils.debugPrintln(nextFrame.method.shimpleBody.toString());
        }
        callStack.push(nextFrame);
        CallGraphNode childNode = new CallGraphNode(nextFrame, parentNode);
        parentNode.addChild(childNode);
        frameToGraphNode.put(nextFrame, childNode);
      } else {
      }
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