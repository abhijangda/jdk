import java.util.jar.*;

import javax.print.attribute.IntegerSyntax;
import javax.swing.plaf.basic.BasicInternalFrameTitlePane.SystemMenuBar;
import java.nio.file.*;
import java.util.*;
import java.io.*;
import java.util.zip.ZipInputStream;

import org.apache.bcel.classfile.*;
import org.apache.bcel.*;
import org.apache.bcel.generic.Type;
import org.apache.bcel.util.*;

public class HeapEvent {
  //TODO: Use constant table indices to represent class and method?
  JavaMethod method_;
  int bci_;
  long srcPtr_;
  JavaClass srcClass_;
  long dstPtr_;
  JavaClass dstClass_;

  public HeapEvent(JavaMethod method, int bci, long src, JavaClass srcClass, long dst, JavaClass dstClass) {
    this.method_ = method;
    this.bci_ = bci;
    this.srcPtr_ = src;
    this.srcClass_ = srcClass;
    this.dstPtr_ = dst;
    this.dstClass_ = dstClass;
  }

  public static HeapEvent fromString(String repr, JavaClassCollection classes) {
    assert(repr.charAt(0) == '[' && repr.charAt(-1) == ']');
    // System.out.println(": " + repr);
    String[] split = repr.split(",");
    String method = split[0].substring(1).strip();
    JavaMethod m = classes.getMethod(method);
    if (JavaClassCollection.methodToCare(method))
      assert (m != null);
    int bci = Integer.parseInt(split[1].strip());
    String[] src = split[2].split(":");
    JavaClass srcClass = classes.getClassForString(src[1].strip());
    if (JavaClassCollection.classToCare(src[1].strip()))
      assert(srcClass != null);

    String[] dst = split[3].substring(0, split[3].length() - 1).split(":");

    JavaClass dstClass = classes.getClassForString(dst[1].strip());
    if (JavaClassCollection.classToCare(dst[1].strip()))
      assert(dstClass != null);

    return new HeapEvent(m, bci,
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
        if (line.charAt(0) == '[' && line.charAt(line.length() - 1) == ']') {
          assert(currEvents != null);
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
    return "[" + method_ + "," + Integer.toString(bci_) + "," + Long.toString(srcPtr_) + ":" + srcClass_ + "," + Long.toString(dstPtr_) + ":" + dstClass_ + "]";
  }
}