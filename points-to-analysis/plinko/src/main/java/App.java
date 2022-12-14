import java.io.IOException;

import org.apache.bcel.classfile.ClassFormatException;
import org.apache.bcel.classfile.ClassParser;
import org.apache.bcel.classfile.JavaClass;
import org.apache.bcel.classfile.Method;
import org.apache.bcel.*;
import org.apache.bcel.generic.Type;

import java.util.jar.*;

import javax.print.attribute.IntegerSyntax;
import javax.swing.plaf.basic.BasicInternalFrameTitlePane.SystemMenuBar;
import java.nio.file.*;
import java.util.*;
import java.io.*;
import java.util.zip.ZipInputStream;

class HeapEvent {
  //TODO: Use constant table indices to represent class and method?
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

  public static ArrayList<Method> findMainMethods(String jarFile, JarFile jar) {
    ArrayList<Method> mainMethods = new ArrayList<>();
    try {
      Enumeration<JarEntry> entries = jar.entries();
      while (entries.hasMoreElements()) {
          JarEntry entry = entries.nextElement();
          if (!entry.getName().endsWith(".class")) {
              continue;
          }

          ClassParser parser = new ClassParser(jarFile, entry.getName());
          JavaClass javaClass = parser.parse();
          // System.out.println(javaClass.getClassName());
          for (Method m : javaClass.getMethods()) {
            // System.out.println(m.getName());
            if (m.getName().equals("main") && m.isStatic() && m.isPublic() && 
                m.getReturnType() == Type.VOID) {
                mainMethods.add(m);
            }
          }
      }
    } catch (Exception e) {

    }

    return mainMethods;
  }

      /**
     * Extracts a zip entry (file entry)
     * @param zipIn
     * @param filePath
     * @throws IOException
     */
    public static void extractFile(JarInputStream zipIn, String filePath) throws IOException {
          BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(filePath));
          byte[] bytesIn = new byte[1024*1024];
          int read = 0;
          while ((read = zipIn.read(bytesIn)) != -1) {
              bos.write(bytesIn, 0, read);
          }
          bos.close();
      }
  
  public static void createMethodNameMap(String jarFile, HashMap<String, Method> methodNameMap) {
    try {
      JarFile jar = new JarFile(jarFile);
      JarInputStream jarIn = new JarInputStream(new FileInputStream (jarFile));
      JarEntry entry = jarIn.getNextJarEntry();
      while (entry != null) {
          if (entry.getName().endsWith(".class")) {
            ClassParser parser = new ClassParser(jarIn, entry.getName());
            JavaClass javaClass = parser.parse();
            // System.out.println(javaClass.getClassName());
            for (Method m : javaClass.getMethods()) {
              String methodName = javaClass.getClassName() +
                                  "." + m.getName() + m.getSignature();
              // if (methodName.contains("org.dacapo.harness.Digest.toString([B)Ljava/lang/String;"))
              //   System.out.println("found " + methodName);
              methodNameMap.put(methodName, m);
            }
          } else if (entry.getName().endsWith(".jar")) {
            Path entryPath = Paths.get(entry.getName());
            String extractedJarFile = "/tmp/"+entryPath.getFileName();
            extractFile(jarIn, extractedJarFile);
            createMethodNameMap(extractedJarFile, methodNameMap);
          }
          jarIn.closeEntry();
          entry = jarIn.getNextJarEntry();
      }
      jarIn.close();
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  public static void callGraph(HashMap<String, Method> methodNameMap,
                               HashMap<String, ArrayList<HeapEvent>> heapEvents, 
                               String mainThread, int heapEventIdx) {
    //Check all methods of event in the main thread are in methodNameMap
    ArrayList<HeapEvent> mainThreadEvents = heapEvents.get(mainThread);

    for (int i = heapEventIdx; i < mainThreadEvents.size(); i++) {
      HeapEvent he = mainThreadEvents.get(i);
      if (!he.method_.equals("NULL") && !he.method_.contains("java.") && !he.method_.contains("jdk.") && 
          !he.method_.contains("sun.") && !methodNameMap.containsKey(he.method_)) {
        System.out.println("not found: " + he.method_);
      }
    }

    for (int idx = heapEventIdx; idx < mainThreadEvents.size(); idx++) {
      HeapEvent he = mainThreadEvents.get(idx);
      
    }
  }

  public static void main(String[] args) throws ClassFormatException, IOException {
    //Read and process heap events
    String heapEventsFile = "/mnt/homes/aabhinav/jdk/heap-events";
    HashMap<String, ArrayList<HeapEvent>> heapEvents = processHeapEventsFile(heapEventsFile);
    System.out.println("HeapEvents loaded");
    //Read the jarfile
    String jarFile = "/mnt/homes/aabhinav/jdk/dacapo-9.12-MR1-bach.jar";
    
    
    //Find all main methods in the jar and find that method in the heap events
    // ArrayList<Method> mainMethods = findMainMethods(jarFile, jar);
    HashMap<String, Method> methodNameMap = new HashMap<>();
    createMethodNameMap(jarFile, methodNameMap);
    String mainThread = "";
    int heapEventIdx = -1;

    // for (Method mainMethod : mainMethods) {
    //   String mainClassName = mainMethod.getClass().getName();
      // System.out.println(mainClassName);
      for (String thread : heapEvents.keySet()) {
        int i = 0;
        for (HeapEvent he : heapEvents.get(thread)) {
          //TODO: also check for the class obtained from above
          if (he.method_.contains(".main")) { //&& he.method_.contains(mainClassName)) {
            mainThread = thread;
            heapEventIdx = i;
            break;
          }
          i++;
        }

        if (heapEventIdx != -1) break;
      }
    // }

    assert(mainThread != "");
    assert(heapEventIdx != -1);

    System.out.println(mainThread + " " + heapEvents.get(mainThread).get(heapEventIdx).toString());

    callGraph(methodNameMap, heapEvents, mainThread, heapEventIdx);
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
