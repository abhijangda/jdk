package callgraphanalysis;
import java.util.*;

import org.slf4j.helpers.Util;

import callstack.StaticFieldValues;
import classcollections.*;
import classhierarchyanalysis.ClassHierarchyAnalysis;
import classhierarchyanalysis.ClassHierarchyGraph;
import javaheap.HeapEvent;
import javaheap.JavaHeap;
import soot.SootMethod;
import soot.toolkits.graph.Block;
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
    JavaHeap javaHeap = new JavaHeap();
    StaticFieldValues staticFieldValues = new StaticFieldValues(javaHeap);

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
    StaticInitializers staticInits = new StaticInitializers();
    CallFrame rootFrame = new CallFrame(javaHeap, staticInits, startEvent, null, null, null);
    CallGraphNode rootNode = new CallGraphNode(rootFrame, null);
    HashMap<CallFrame, CallGraphNode> frameToGraphNode = new HashMap<>();

    callStack.push(rootFrame);
    frameToGraphNode.put(rootFrame, rootNode);
    traverseCallStack(rootFrame, callStack, new CallEdges(), eventIterator, 0);
    System.out.println("Edges:");
    System.out.println(rootNode.edgesToString());
  }
  
  private static void traverseCallStack(CallFrame startFrame, Stack<CallFrame> callStack, CallEdges edges, ArrayListIterator<HeapEvent> eventIterator, int iterations) {
    HashMap<CallFrame, CallGraphNode> frameToGraphNode = new HashMap<>();
    Utils.infoPrintln("new call frame " + startFrame.method.fullname() + " " + startFrame.getPC());
    while (!callStack.isEmpty() && iterations++ < 4000) {
      if (iterations >= 3180 && eventIterator.index() < 668)
        return;
        
      HeapEvent currEvent;
      CallFrame frame = callStack.peek();
      if (eventIterator.index() >= 646) // && frame.method.fullname().contains("QueryProcessor.run"))
        Utils.DEBUG_PRINT = true;
      if (frame.parent != null) {
        Utils.infoPrintln("parent frame " + frame.parent.toString());
      }
      
      Utils.infoPrintln("current frame " + frame + " iterations " + iterations);
      currEvent = eventIterator.get();
      Utils.infoPrintln("currevent " + currEvent.toString() + " at " + eventIterator.index());
      // if (frame.canPrint) {
      //   Utils.debugPrintln(frame.method.basicBlockStr());
      //   System.exit(0);;
      // }
      if (!frame.hasNextInvokeStmt()) {
        // if (frame.canPrint) return;
        callStack.pop();
        continue;
      }
      
      while (!Utils.methodToCare(currEvent.method) ||
            //  currEvent.methodStr.contains("org.apache.lucene.store.FSDirectory") ||
             currEvent.methodStr.contains("org.apache.lucene.analysis.CharArraySet.add")) {
        startFrame.heap.update(currEvent);
        eventIterator.moveNext();
        currEvent = eventIterator.get();
      }
      
      // while(mainThreadEvents.get(heapEventIdx).method == frame.method.sootMethod) {
      //   javaHeap.updateWithHeapEvent(mainThreadEvents.get(heapEventIdx));
      //   Utils.debugPrintln(mainThreadEvents.get(heapEventIdx).toString());
      //   frame.updateValuesWithHeapEvent(mainThreadEvents.get(heapEventIdx));
      //   heapEventIdx++;
      // }
      if (iterations >= 3900 && eventIterator.index() >= 654 && frame.method.fullname().contains("org.apache.lucene.queryParser.QueryParser.Term")) {
        // Utils.infoPrintln(frame.method.fullname());
        // Utils.infoPrintln(eventIterator.index());
        return;
      }
      CallGraphNode parentNode = frameToGraphNode.get(frame);
      CallFrame nextFrame = null;
      try {
        nextFrame = frame.nextInvokeMethod(eventIterator);
      } catch (InvalidCallStackException e) {
        Utils.debugPrintln("");
        e.printStackTrace();
        Utils.infoPrintln(eventIterator.index());
        Utils.debugPrintln(frame.method.fullname());
        if (frame.method.fullname().contains("org.apache.lucene.search.Query.createWeight") && eventIterator.index() >= 669) {
          System.exit(0);
        }
        break;
      } catch (MultipleNextBlocksException e) {
        Utils.infoPrintf("Create new frames %s %d at %s\n", frame.method.fullname(), e.nextBlocks.size(), frame.getPC());
        if (e.nextBlocks.size() == 1) {
          frame.setPC(e.nextBlocks.iterator().next());
          continue;
        } else {
          for (Block block : e.nextBlocks) {
            JavaHeap newHeap = (JavaHeap)frame.heap.clone();
            StaticFieldValues newStaticVals = frame.heap.getStaticFieldValues().clone(newHeap);
            newHeap.setStaticFieldValues(newStaticVals);
            StaticInitializers newStaticInits = frame.staticInits.clone();
            // Utils.debugPrintln("cloning staticinit " + frame.staticInits.hashCode() + " to " + newStaticInits.hashCode());
            CallFrame newFrame = frame.clone(newHeap, newStaticInits);
            Utils.infoPrintln("NewFrame.staticInits " + newFrame.staticInits.hashCode());
            newFrame.setPC(block);
            Stack<CallFrame> newCallStack = new Stack<CallFrame>();
            newCallStack.addAll(callStack);
            newCallStack.pop();
            newCallStack.push(newFrame);
            traverseCallStack(newFrame, newCallStack, edges.clone(),
                              eventIterator.clone(), iterations);
          }
        }
        // Utils.debugPrintln("");
        // System.exit(0);
      } catch (CallGraphException e) {
        e.printStackTrace();
        Utils.infoPrintln("");
        System.exit(0);
      }
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
      if (nextFrame != null && nextFrame.method != null &&
          ((frame.parent != null) || frame.parent == null) &&
          !Utils.methodFullName(nextFrame.method.sootMethod).contains("java.lang.SecurityManager.checkPermission") &&
          Utils.methodToCare(frame.method.sootMethod)) {
        //Skip recursion
        Utils.infoPrintln("next frame: " + utils.Utils.methodFullName(nextFrame.method.sootMethod) + " parent " + ((frame == null) ?  "" : frame.method.fullname()));
        if (nextFrame.method.sootMethod.getDeclaringClass().getName().contains("QueryProcessor") &&
          nextFrame.method.sootMethod.getName().contains("run")) {
          // Utils.debugPrintln(nextFrame.method.shimpleBody.toString());
        }
        callStack.push(nextFrame);
        // CallGraphNode childNode = new CallGraphNode(nextFrame, parentNode);
        // parentNode.addChild(childNode);
        // frameToGraphNode.put(nextFrame, childNode);
        edges.add(frame.method, nextFrame.method);
      } else {
      }
    }
    
    Utils.infoPrintln("DONE");
    if (eventIterator.index() >= 680) {
      Utils.infoPrintln("Edges:");

      Utils.infoPrintln(edges.toString());

      System.exit(0);
    }
    // System.exit(0);
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