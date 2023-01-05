package javaheap;

import java.util.*;
import java.io.*;

import soot.SootMethod;
import soot.Type;

import classcollections.*;
import utils.Utils;

public class HeapEvent {
  //TODO: Use constant table indices to represent class and method?
  public static enum EventType {
    NewObject,
    NewArray,
    NewPrimitiveArray,
    ObjectFieldSet,
    ArrayElementSet
  };

  public final EventType eventType;
  public final SootMethod method;
  public final String methodStr;
  public final int bci;
  public final long srcPtr;
  public final Type srcClass;
  public final long dstPtr;
  public final Type dstClass;
  public final int elemIndex;
  public final String fieldName;

  public HeapEvent(EventType eventType, SootMethod method, String methodStr, int bci, 
                   long src, Type srcClass, long dst, Type dstClass,
                   int elemIndex, String fieldName) {
    this.eventType = eventType;
    this.method = method;
    this.methodStr = methodStr;
    this.bci = bci;
    this.srcPtr = src;
    this.srcClass = srcClass;
    this.dstPtr = dst;
    this.dstClass = dstClass;
    this.elemIndex = elemIndex;
    this.fieldName = fieldName;
  }

  public static EventType typefromInt(int e) {
    switch (e) {
      case 0:
        return EventType.ObjectFieldSet;
      case 1:
        return EventType.NewObject;
      case 2:
        return EventType.NewArray;
      case 10:
        return EventType.NewPrimitiveArray;
      
      default:
        Utils.debugAssert(false, "unhandled event " + e);
        return null;
    }
  }

  public static HeapEvent fromString(String repr, JavaClassCollection classes) {
    Utils.debugAssert(repr.charAt(0) == '[' && repr.charAt(repr.length() - 1) == ']', 
                    "Invalid " + repr);
    String[] split = repr.split(",");
    int eventTypeInt = Integer.parseInt(split[0].substring(1));
    EventType eventType = typefromInt(eventTypeInt);
    String method = split[1].strip();
    SootMethod m = classes.getMethod(method);
    if (JavaClassCollection.methodToCare(method))
      Utils.debugAssert(m != null, "Method not found " + method);

    int bci = Integer.parseInt(split[2].strip());
    String[] src = split[3].split(":");
    Type srcClass = classes.javaTypeForSignature(Utils.pathToPackage(src[1].strip()));
    if (JavaClassCollection.classToCare(Utils.pathToPackage(src[1].strip())))
      Utils.debugAssert(srcClass != null, "class not found " + src[1]);

    String[] dst = split[4].substring(0, split[4].length() - 1).split(":");
    String dstKlassName = "";
    String fieldName = "";
    int elemIndex = -1;
    dst[1] = dst[1].strip();
    if (dst[1].endsWith("]")) {
      eventType = EventType.ArrayElementSet;
      int elemIndexStart = dst[1].lastIndexOf("[");
      dstKlassName = dst[1].substring(0, elemIndexStart).strip();
      String elemIndexStr = dst[1].substring(elemIndexStart + 1, dst[1].length() - 1);
      elemIndex = Integer.parseInt(elemIndexStr);
    } else if (dst[1].contains(".")) {
      eventType = EventType.ObjectFieldSet;
      int fieldNameStart = dst[1].lastIndexOf(".");
      dstKlassName = dst[1].substring(0, fieldNameStart).strip();
      fieldName = dst[1].substring(fieldNameStart + 1);
    } else {
      dstKlassName = dst[1].strip();
      elemIndex = -1;
      fieldName = "";
    }

    Type dstClass = classes.javaTypeForSignature(Utils.pathToPackage(dstKlassName));
    if (JavaClassCollection.classToCare(Utils.pathToPackage(dst[1].strip())))
      Utils.debugAssert(dstClass != null, "class not found " + dst[1]);

    return new HeapEvent(eventType, m, method, bci,
                         Long.parseLong(src[0].strip()),
                         srcClass,
                         Long.parseLong(dst[0].strip()),
                         dstClass, elemIndex, fieldName);
  }

  //Parse heapevents file to create a map of each thread to a list of heap events
  public static HashMap<String, ArrayList<HeapEvent>> processHeapEventsFile(String fileName, JavaClassCollection classes) {
    BufferedReader reader;
    HashMap<String, ArrayList<HeapEvent>> heapEvents = new HashMap<String, ArrayList<HeapEvent>>();
    
    try {
      reader = new BufferedReader(new FileReader(fileName));
      String line = reader.readLine();
      String currThread = "";
      ArrayList<HeapEvent> currEvents = null;

      while (line != null) {
        if (true || line.contains(": {") || line.contains("org.dacapo") || line.contains("apache")) {
          // if (currEvents != null) System.out.println(currEvents.size() + ": " + line);
        if (line.contains(": {[")) {
          //TODO: Fix this case
          currThread = line.substring(0, line.indexOf(":"));
          if (heapEvents.containsKey(currThread) == false) {
              currEvents = new ArrayList<>();
              heapEvents.put(currThread, currEvents);
          }

          currEvents = heapEvents.get(currThread);
          line = line.substring(line.indexOf("["));
          Utils.debugAssert(currEvents != null, "");
          // HeapEvent he = HeapEvent.fromString(line, classes);
          // currEvents.add(he);
        } else if (line.charAt(0) == '[' && line.charAt(line.length() - 1) == ']') {
          Utils.debugAssert(currEvents != null, "");
          HeapEvent he = HeapEvent.fromString(line, classes);
          currEvents.add(he);
        } else if (line.contains(":")) {
          currThread = line.substring(0, line.indexOf(":"));
          if (heapEvents.containsKey(currThread) == false) {
              currEvents = new ArrayList<>();
              heapEvents.put(currThread, currEvents);
          }

          currEvents = heapEvents.get(currThread);
        }
        }
        // read next line
        line = reader.readLine();
      }

      reader.close();
    } catch (IOException e) {
        e.printStackTrace();
    }

    return heapEvents;
  }

  public String toString() {
    StringBuilder builder = new StringBuilder();
    
    builder.append("[");
    builder.append(eventType);
    builder.append(",");
    if (method != null) {
      builder.append(Utils.methodFullName(method));
    } else {
      builder.append("NULL");
    }
    
    builder.append(",");
    builder.append(bci);
    builder.append(",");
    builder.append(srcPtr);
    builder.append(":");
    builder.append(((srcClass != null) ? srcClass.toString() : "NULL"));
    builder.append(",");
    builder.append(dstPtr);
    builder.append(":");

    if (dstClass != null) {
      builder.append(dstClass.toString());
      if (eventType == EventType.ObjectFieldSet) {
        builder.append(".");
        builder.append(fieldName);
      } else if (eventType == EventType.ArrayElementSet) {
        builder.append("[" + elemIndex + "]");
      } else {
      }
    } else {
      builder.append("NULL");
    }
    
    builder.append("]");

    return builder.toString();
  }
}