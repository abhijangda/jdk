import java.util.*;
import java.io.*;

import soot.SootMethod;
import soot.Type;

public class HeapEvent {
  //TODO: Use constant table indices to represent class and method?
  public final SootMethod method_;
  public final String methodStr_;
  public final int bci_;
  public final long srcPtr_;
  public final Type srcClass_;
  public final long dstPtr_;
  public final Type dstClass_;

  public HeapEvent(SootMethod method, String methodStr, int bci, long src, Type srcClass, long dst, Type dstClass) {
    this.method_ = method;
    this.methodStr_ = methodStr;
    this.bci_ = bci;
    this.srcPtr_ = src;
    this.srcClass_ = srcClass;
    this.dstPtr_ = dst;
    this.dstClass_ = dstClass;
  }

  public static HeapEvent fromString(String repr, JavaClassCollection classes) {
    Main.debugAssert(repr.charAt(0) == '[' && repr.charAt(repr.length() - 1) == ']', 
                    "Invalid " + repr);
    String[] split = repr.split(",");
    String method = split[0].substring(1).strip();
    SootMethod m = classes.getMethod(method);
    if (JavaClassCollection.methodToCare(method))
      Main.debugAssert(m != null, "Method not found " + method);

    int bci = Integer.parseInt(split[1].strip());
    String[] src = split[2].split(":");
    Type srcClass = classes.javaTypeForSignature(Main.pathToPackage(src[1].strip()));
    if (JavaClassCollection.classToCare(Main.pathToPackage(src[1].strip())))
      Main.debugAssert(srcClass != null, "class not found " + src[1]);

    String[] dst = split[3].substring(0, split[3].length() - 1).split(":");

    Type dstClass = classes.javaTypeForSignature(Main.pathToPackage(dst[1].strip()));
    if (JavaClassCollection.classToCare(Main.pathToPackage(dst[1].strip())))
      Main.debugAssert(dstClass != null, "class not found " + dst[1]);

    return new HeapEvent(m, method, bci,
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
        if (line.contains(": {") || line.contains("org.dacapo") || line.contains("apache")) {
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
    return "[" + Main.methodFullName(method_) + "," + Integer.toString(bci_) + "," + 
            Long.toString(srcPtr_) + ":" + ((srcClass_ != null) ? srcClass_.toString() : "NULL") + "," + 
            Long.toString(dstPtr_) + ":" + ((dstClass_ != null) ? dstClass_.toString() : "NULL") + "]";
  }
}