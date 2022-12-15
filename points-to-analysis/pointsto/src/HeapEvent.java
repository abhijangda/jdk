package main;

public class HeapEvent {
  //TODO: Use constant table indices to represent class and method?
  String method_;
  int bci_;
  long srcPtr_;
  String srcClass_;
  long dstPtr_;
  String dstClass_;

  public HeapEvent(String method, int bci, long src, String srcClass, long dst, String dstClass) {
      this.method_ = method;
      this.bci_ = bci;
      this.srcPtr_ = src;
      this.srcClass_ = srcClass;
      this.dstPtr_ = dst;
      this.dstClass_ = dstClass;
  }

  public static HeapEvent fromString(String repr) {
      assert(repr.charAt(0) == '[' && repr.charAt(-1) == ']'); 
      // System.out.println(": " + repr); 
      String[] split = repr.split(",");
      String method = split[0].substring(1).strip();
      int bci = Integer.parseInt(split[1].strip());
      String[] src = split[2].split(":");
      String[] dst = split[3].substring(0, split[3].length() - 1).split(":");
      return new HeapEvent(method, bci,
                      Long.parseLong(src[0].strip()),
                      src[1].strip(),
                      Long.parseLong(dst[0].strip()),
                      dst[1].strip());
  }

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

  public String toString() {
      return "[" + method_ + "," + Integer.toString(bci_) + "," + Long.toString(srcPtr_) + ":" + srcClass_ + "," + Long.toString(dstPtr_) + ":" + dstClass_ + "]";
  }
}