import java.util.*;

import org.apache.bcel.Const;

import soot.SootMethod;
import soot.Unit;
import soot.UnitBox;
import soot.Value;
import soot.ValueBox;
import soot.jimple.InvokeStmt;
import soot.jimple.internal.JAssignStmt;
import soot.shimple.ShimpleBody;
import soot.toolkits.graph.Block;
import soot.toolkits.graph.BlockGraph;
import soot.toolkits.graph.ExceptionalBlockGraph;

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
      if (he.method != null && he.method.getDeclaringClass().getName().contains("lusearch"))
        break;

      heapEventIdx++;
    }

    System.out.printf("Starting heap event from %d with method %s\n", 
    heapEventIdx, Main.methodFullName(mainThreadEvents.get(heapEventIdx).method));

    Stack<Pair<CallFrame, Iterator<InvokeStmt>>> callStack = new Stack<>();
    StaticValue staticValues = new StaticValue();
    Stack<Pair<CallFrame, Integer>> remainingFrames = new Stack<>();
    HeapEvent currEvent = mainThreadEvents.get(heapEventIdx);
    CallFrame rootFrame = new CallFrame(mainThreadEvents.get(heapEventIdx), null);
    
    remainingFrames.push(Pair.v(rootFrame, 0));
    callStack.push(Pair.v(rootFrame, rootFrame.iterateInvokeStmts()));
    int indent = 0;
    System.out.println(Main.methodFullName(currEvent.method));
    while (!callStack.isEmpty()) {
      Pair<CallFrame, Iterator<InvokeStmt>> frame = callStack.peek();
      if (!frame.second.hasNext()) {
        callStack.pop();
        indent -=1;
        continue;
      }
      // System.out.println(frame.first.method.sootMethod.toString());
      // for (InvokeStmt stmt : frame.first.method.getInvokeStmts()) {
      //   System.out.println("  " + stmt.toString());
      // }
      for (int i = 0; i < indent; i++) {
        System.out.print(" ");
      }
      InvokeStmt stmt = frame.second.next();
      ShimpleMethod invokeMethod = ParsedMethodMap.v().getOrParseToShimple(stmt.getInvokeExpr().getMethod());
      if (invokeMethod != null && invokeMethod != frame.first.method && 
          ((frame.first.root != null && invokeMethod != frame.first.root.method) || frame.first.root == null) &&
          !Main.methodFullName(invokeMethod.sootMethod).contains("java.lang.SecurityManager.checkPermission")) {
        //Skip recursion and abstract methods
        CallFrame nextFrame = new CallFrame(invokeMethod, frame.first);
        // System.out.print(Main.methodFullName(frame.first.method.sootMethod) + " -> ");
        System.out.println(Main.methodFullName(invokeMethod.sootMethod));
        indent+=1;
        callStack.push(Pair.v(nextFrame, nextFrame.iterateInvokeStmts()));
      } else {
        System.out.println(Main.methodFullName(stmt.getInvokeExpr().getMethod()) + " ABSTRACT");
      }
      // Pair<CallFrame, Integer> nextFrame = remainingFrames.pop();
      // for (int i = 0; i < nextFrame.first.invokeStmts.size(); i++) {
      //   InvokeStmt invoke = nextFrame.first.invokeStmts.get(i);
      //   ShimpleMethod invokeMethod = ParsedMethodMap.v().getOrParseToShimple(invoke.getInvokeExpr().getMethod());
      //   CallFrame childFrame = new CallFrame(invokeMethod, nextFrame.first);
      //   remainingFrames.push(Pair.v(childFrame, i));
      // }

      // if (nextFrame.first.root != null && nextFrame.second == nextFrame.first.root.invokeStmts.size() - 1) {
      //   callStack.pop();
      // }
    }
  
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