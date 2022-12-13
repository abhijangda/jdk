import java.io.IOException;

import org.apache.bcel.classfile.ClassFormatException;
import org.apache.bcel.classfile.ClassParser;
import org.apache.bcel.classfile.JavaClass;
import org.apache.bcel.classfile.Method;
import org.apache.bcel.*;

import java.util.jar.*;

import javax.print.attribute.IntegerSyntax;

import java.util.*;
import java.io.*;

class HeapEvent {
  String method_;
  int bci_;
  long src_;
  long dst_;

  public HeapEvent(String method, int bci, long src, long dst) {
    this.method_ = method;
    this.bci_ = bci;
    this.src_ = src;
    this.dst_ = dst;
  }

  public static HeapEvent fromString(String repr) {
    assert(repr.charAt(0) == '[' && repr.charAt(-1) == ']');
    String[] split = repr.split(",");
    
    return new HeapEvent(split[0].substring(1).strip(),
                     Integer.parseInt(split[1].strip()),
                     Long.parseLong(split[2].strip()),
                     Long.parseLong(split[3].substring(0, split[3].length() - 1).strip()));
  }

  public String toString() {
    return "[" + method_ + "," + Integer.toString(bci_) + "," + Long.toString(src_) + "," + Long.toString(dst_) + "]";
  }
}

public class App {
  //Parse heapevents file to create a map of each thread to a list of heap events
  public static HashMap<String, ArrayList<HeapEvent>> processHeapEventsFile(String fileName) {
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
          HeapEvent he = HeapEvent.fromString(line);
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

  public static void processHeapEventsText(String heapEventsText) {

  }

  public static void main(String[] args) throws ClassFormatException, IOException {
    String heapEventsFile = "/mnt/homes/aabhinav/jdk/heap-events";
    HashMap<String, ArrayList<HeapEvent>> heapEvents = processHeapEventsFile(heapEventsFile);
    for (String th : heapEvents.keySet()) {
      System.out.println(th);
      for (HeapEvent he : heapEvents.get(th)) {
        System.out.println(he.toString());
      }
    }
    String jarFile = "/mnt/homes/aabhinav/jdk/dacapo-9.12-MR1-bach.jar";
    JarFile jar = new JarFile(jarFile);
    Enumeration<JarEntry> entries = jar.entries();
    while (entries.hasMoreElements()) {
        JarEntry entry = entries.nextElement();
        if (!entry.getName().endsWith(".class")) {
            continue;
        }

        ClassParser parser = new ClassParser(jarFile, entry.getName());
        JavaClass javaClass = parser.parse();
        System.out.println(javaClass.getClassName());
    }
    // System.out.println("17\n");
    // /*An existing class can be parsed with ClassParser */
    // ClassParser parser=new ClassParser(App.class.getResourceAsStream("App.class"), "App.class");
    // JavaClass javaClass=parser.parse();
    
    // System.out.println("*******Constant Pool*********");
    // System.out.println(javaClass.getConstantPool());
    
    // System.out.println("*******Fields*********");
    // System.out.println(Arrays.toString(javaClass.getFields()));
    // System.out.println();
    
    // System.out.println("*******Methods*********");
    // System.out.println(Arrays.toString(javaClass.getMethods()));
    
    // for(Method method:javaClass.getMethods()){
    //     System.out.println(method);
    //     System.out.println(method.getCode());
    // }
  }
}
