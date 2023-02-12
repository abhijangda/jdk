package callgraphanalysis;
import java.util.*;

import org.slf4j.helpers.Util;

import com.google.common.hash.HashCode;

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
  static class MultipleNextBlockPath extends ArrayList<Pair<ShimpleMethod, Block>> {
    private boolean loaded;
    
    public boolean isLoaded() {
      return loaded;
    }

    MultipleNextBlockPath() {
      super();
      loaded = false;
    }

    public String toString() {
      StringBuilder builder = new StringBuilder();
      for (Pair<ShimpleMethod, Block> pair : this) {
        builder.append(pair.first.fullname());
        builder.append(":");
        builder.append(pair.second.getIndexInMethod() + "\n");
      }

      return builder.toString();
    }

    public void load(String str) {
      for (String line : str.split("\n")) {
        loaded = true;
        String[] split = line.split(":");
        String methodStr = split[0];
        ShimpleMethod sm = ParsedMethodMap.v().getOrParseToShimple(methodStr);
        int blockIndex = Integer.parseInt(split[1]);
        Block block = sm.getBlock(blockIndex);
        this.add(Pair.v(sm, block));
      }
    }
  }

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

    CallStack callStack = new CallStack();
    HeapEvent currEvent = startEvent;
    StaticInitializers staticInits = new StaticInitializers();
    CallFrame rootFrame = new CallFrame(javaHeap, staticInits, startEvent, null, null, null);
    CallGraphNode rootNode = new CallGraphNode(rootFrame, null);
    HashMap<CallFrame, CallGraphNode> frameToGraphNode = new HashMap<>();
    MultipleNextBlockPath multipleNextBlockPath = new MultipleNextBlockPath();
    if (true) {
      String nextBlockPath = "./multipleblockpath";
      multipleNextBlockPath.load(Utils.readFileAsString(nextBlockPath));
    }
    callStack.push(rootFrame);
    frameToGraphNode.put(rootFrame, rootNode);
    
    traverseCallStack(multipleNextBlockPath, rootFrame, callStack, new CallEdges(), eventIterator, 0);
  }
  
  private static void traverseCallStack(MultipleNextBlockPath multipleNextBlockPath, CallFrame startFrame, CallStack callStack, CallEdges edges, ArrayListIterator<HeapEvent> eventIterator, int iterations) {
    HashMap<CallFrame, CallGraphNode> frameToGraphNode = new HashMap<>();
    Utils.infoPrintln("new call frame " + startFrame.method.fullname() + " " + startFrame.getPC());
    Utils.infoPrintln("new callStack " + callStack.getId());
    for (CallFrame f : callStack) {
      Utils.infoPrintln(f.method.fullname() + " hashcode: " + f.getId() + " heap: " + f.heap.getId());
    }
    while (!callStack.isEmpty() && iterations++ < 30000) {        
      HeapEvent currEvent;
      CallFrame frame = callStack.peek();
      Utils.infoPrintln("callStack " + callStack.getId() + " frame hascode: " + frame.getId());
      if (eventIterator.index() > 3400)
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
      // if (frame.method.fullname().contains("org.dacapo.lusearch.Search$QueryProcessor.run") && eventIterator.index() == 647) {
      //   return;
      // }k
      if (!frame.hasNextInvokeStmt()) {
        // if (frame.canPrint) return;
        callStack.pop();
        continue;
      }
      
      while (!Utils.methodToCare(currEvent.method) ||
             currEvent.methodStr.contains("org.apache.lucene.util.UnicodeUtil.<clinit>()V") ||
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
        break;
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
        break;
      } catch (MultipleNextBlocksException e) {
        Utils.infoPrintf("Create new frames %s %d at %s for frame %d\n", frame.method.fullname(), e.nextBlocks.size(), frame.getPC(), frame.getId());
        String o = "";
        for (Block block : e.nextBlocks) {
          o += block.getIndexInMethod() + ", ";
        }
        Utils.debugPrintln(o);
        if (e.nextBlocks.size() == 1) {
          frame.setPC(e.nextBlocks.iterator().next());
          continue;
        } else if (e.nextBlocks.size() == 0) {
          break;
        } else {
          boolean nextBlockNotFound = false;
          boolean nextBlockFromPath = false;
          for (Block block : e.nextBlocks) {            
            boolean gotoBlock = false;
            if (!multipleNextBlockPath.loaded) {
              gotoBlock = true;
              multipleNextBlockPath.add(Pair.v(frame.method, block));
              // Utils.debugPrintln("cloning staticinit " + frame.staticInits.hashCode() + " to " + newStaticInits.hashCode());
            } else if (multipleNextBlockPath.size() > 0) {
              Pair<ShimpleMethod, Block> pair = multipleNextBlockPath.get(0);
              // if (eventIterator.index() >= 3575 && pair.first != frame.method) {
              //   Utils.infoPrintln("Edges:");

              //   Utils.infoPrintln(edges.toString());
              // }
              Utils.debugAssert(pair.first == frame.method, "%s != %s\n%s", pair.first.fullname(), frame.method.fullname(), frame.method.basicBlockStr());
              if (pair.second == block) {
                multipleNextBlockPath.remove(0);
                gotoBlock = true;
                nextBlockFromPath = true;
                Utils.infoPrintln("going to block " + block.getIndexInMethod());
              }
            } else if (multipleNextBlockPath.loaded && multipleNextBlockPath.size() == 0) {
              multipleNextBlockPath.loaded = false;
              // nextBlockNotFound = true;
              // Utils.infoPrintln("Next block not found");
              // Utils.infoPrintln(frame.method.fullname());
              // Utils.infoPrintln(frame.method.basicBlockStr());
              // break;
              gotoBlock = true;
              multipleNextBlockPath.add(Pair.v(frame.method, block));
            } 

            if (nextBlockFromPath) {
              frame.setPC(block);
              break;
            } else if (gotoBlock) {
              JavaHeap newHeap = (JavaHeap)frame.heap.clone();
              Utils.debugPrintln(newHeap.hashCode());
              StaticFieldValues newStaticVals = frame.heap.getStaticFieldValues().clone(newHeap);
              newHeap.setStaticFieldValues(newStaticVals);
              StaticInitializers newStaticInits = frame.staticInits.clone();
              CallStack newCallStack = new CallStack();
              Utils.infoPrintln("newCallStack: " + newCallStack.getId() + " parent: " + callStack.getId());
              for (CallFrame sourceStackFrame : callStack) {
                CallFrame copiedParentFrame = newCallStack.isEmpty() ? null : newCallStack.peek();
                CallFrame copyFrame = sourceStackFrame.clone(newHeap, newStaticInits, copiedParentFrame);
                newCallStack.push(copyFrame);
              }
              CallFrame newFrame = newCallStack.peek();
              newFrame.setPC(block);
              traverseCallStack(multipleNextBlockPath, newFrame, newCallStack, edges.clone(),
                                eventIterator.clone(), iterations);
            }
          }

          if (nextBlockNotFound) break;
          if (nextBlockFromPath) continue;
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
            // if (frame.isQueryParserClause && nextFrame.isQueryParserQuery) return;
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

    if (eventIterator.index() >= 3584) {

      Utils.debugPrintln(multipleNextBlockPath.toString());
      // Utils.infoPrintln("Edges:");

      // Utils.infoPrintln(edges.toString());

      System.exit(0);
    }
    if (!multipleNextBlockPath.loaded)
      multipleNextBlockPath.remove(multipleNextBlockPath.size() - 1);
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