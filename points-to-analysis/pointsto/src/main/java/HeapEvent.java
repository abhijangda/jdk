import java.util.*;
import java.io.*;

import soot.SootMethod;
import soot.Type;

public class HeapEvent {
  //TODO: Use constant table indices to represent class and method?
  public final int eventType;
  public final SootMethod method;
  public final String methodStr;
  public final int bci;
  public final long srcPtr;
  public final Type srcClass;
  public final long dstPtr;
  public final Type dstClass;

  public HeapEvent(int eventType, SootMethod method, String methodStr, int bci, long src, Type srcClass, long dst, Type dstClass) {
    this.eventType = eventType;
    this.method = method;
    this.methodStr = methodStr;
    this.bci = bci;
    this.srcPtr = src;
    this.srcClass = srcClass;
    this.dstPtr = dst;
    this.dstClass = dstClass;
  }

  public static HeapEvent fromString(String repr, JavaClassCollection classes) {
    Main.debugAssert(repr.charAt(0) == '[' && repr.charAt(repr.length() - 1) == ']', 
                    "Invalid " + repr);
    String[] split = repr.split(",");
    int eventType = Integer.parseInt(split[0].substring(1));
    String method = split[1].strip();
    SootMethod m = classes.getMethod(method);
    if (JavaClassCollection.methodToCare(method))
      Main.debugAssert(m != null, "Method not found " + method);

    int bci = Integer.parseInt(split[2].strip());
    String[] src = split[3].split(":");
    Type srcClass = classes.javaTypeForSignature(Main.pathToPackage(src[1].strip()));
    if (JavaClassCollection.classToCare(Main.pathToPackage(src[1].strip())))
      Main.debugAssert(srcClass != null, "class not found " + src[1]);

    String[] dst = split[4].substring(0, split[4].length() - 1).split(":");

    Type dstClass = classes.javaTypeForSignature(Main.pathToPackage(dst[1].strip()));
    if (JavaClassCollection.classToCare(Main.pathToPackage(dst[1].strip())))
      Main.debugAssert(dstClass != null, "class not found " + dst[1]);

    return new HeapEvent(eventType, m, method, bci,
                         Long.parseLong(src[0].strip()),
                         srcClass,
                         Long.parseLong(dst[0].strip()),
                         dstClass);
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
          Main.debugAssert(currEvents != null, "");
          // HeapEvent he = HeapEvent.fromString(line, classes);
          // currEvents.add(he);
        } else if (line.charAt(0) == '[' && line.charAt(line.length() - 1) == ']') {
          Main.debugAssert(currEvents != null, "");
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
    return "[" + Main.methodFullName(method) + "," + Integer.toString(bci) + "," + 
            Long.toString(srcPtr) + ":" + ((srcClass != null) ? srcClass.toString() : "NULL") + "," + 
            Long.toString(dstPtr) + ":" + ((dstClass != null) ? dstClass.toString() : "NULL") + "]";
  }
}