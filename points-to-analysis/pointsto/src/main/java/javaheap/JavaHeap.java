package javaheap;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import callstack.StaticFieldValues;
import classcollections.JavaClassCollection;
import classhierarchyanalysis.ClassHierarchyGraph;
import soot.ArrayType;
import soot.RefType;
import soot.SootClass;
import soot.Type;
import soot.JastAddJ.StaticInitializer;
import utils.Utils;

public class JavaHeap extends HashMap<Long, JavaHeapElem> {
  private static int numHeaps = 0;
  private int id;
  public JavaHeap() {
    super();
    this.staticFieldValues = null;
    this.id = numHeaps;
    numHeaps++;
  }
  
  private StaticFieldValues staticFieldValues;
  public int getId() {
    return id;
  }
  public void setStaticFieldValues(StaticFieldValues staticFieldValues) {
    this.staticFieldValues = staticFieldValues;
  }
  public StaticFieldValues getStaticFieldValues() {
    return this.staticFieldValues;
  }

  public void update(HeapEvent event) {
    if (event.eventType == HeapEvent.EventType.NewObject) {
      put(event.dstPtr, new JavaObject((RefType)event.dstClass, event.dstPtr));
    } else if (event.eventType == HeapEvent.EventType.NewArray || 
               event.eventType == HeapEvent.EventType.NewPrimitiveArray) {
      put(event.dstPtr, new JavaArray((ArrayType)event.dstClass, (int)event.srcPtr, event.dstPtr));
    } else if (event.eventType == HeapEvent.EventType.ObjectFieldSet) {
      JavaHeapElem val = null;
      if (event.srcClass == null && event.srcPtr != 0) utils.Utils.infoPrintln("srcClass is null in " + event.toString());
      if (event.srcPtr == 0) val = null;
      else {
        if (!containsKey(event.srcPtr)) {
          utils.Utils.infoLog("Creating the object %d not found in heap\n", event.srcPtr);
          if (event.srcClass instanceof RefType) {
            val = new JavaObject((RefType)event.srcClass, event.srcPtr);
          } else if (event.srcClass instanceof ArrayType) {
            val = new JavaArray((ArrayType)event.srcClass,1000, event.srcPtr);
          }
          put(event.srcPtr, val);
        }
        utils.Utils.debugAssert(containsKey(event.srcPtr), 
                                "Heap does not contain object %d of class %s\n", 
                                event.srcPtr, 
                                (event.srcClass != null) ? event.srcClass.toString() : "");
        val = get(event.srcPtr);
      }
      if (!containsKey(event.dstPtr)) {
        utils.Utils.infoLog("Creating the object %d not found in heap\n", event.dstPtr);
        put(event.dstPtr, new JavaObject((RefType)event.dstClass, event.dstPtr));
      }
      JavaObject obj = (JavaObject)get(event.dstPtr);
      obj.addField(event.fieldName, val);
      if (event.dstPtr == 139941317268960L) {
        Utils.infoPrintln(obj.fieldValues.get(event.fieldName));
      }
    } else if (event.eventType == HeapEvent.EventType.ArrayElementSet) {
      JavaHeapElem val = null;
      if (event.srcPtr == 0) val = null;
      else {
        if (!containsKey(event.srcPtr)) {
          utils.Utils.infoLog("Creating the object %d not found in heap\n", event.srcPtr);
          if (event.srcClass instanceof RefType) {
            val = new JavaObject((RefType)event.srcClass, event.srcPtr);
          } else if (event.srcClass instanceof ArrayType) {
            val = new JavaArray((ArrayType)event.srcClass,1000, event.srcPtr);
          }
          put(event.srcPtr, val);
        }
        val = get(event.srcPtr);
      }

      if (!containsKey(event.dstPtr)) {
        utils.Utils.infoLog("Creating the object %d not found in heap\n", event.dstPtr);
        put(event.dstPtr, new JavaArray(event.dstClass, 1000, event.dstPtr));
      }

      ((JavaArray)get(event.dstPtr)).setElem(event.elemIndex, val);
    } else {
      utils.Utils.debugAssert(false, "not handled event type");
    }
  }

  public JavaObject createNewObject(RefType type) {
    long address = size();
    while(containsKey(address)) address++;

    JavaObject obj = new JavaObject(type, address);
    put(address, obj);

    return obj;
  }

  public JavaHeapElem get(long ptr) {
    if (ptr == 0) {
      return null;
    }

    return super.get(ptr);
  }

  public JavaHeapElem getLastObjOfClass(String klass) {
    TreeMap<Long, JavaHeapElem> objs = new TreeMap<>();
    SootClass klassToFind = JavaClassCollection.v().getClassForString(klass);
    Utils.debugAssert(klassToFind != null, klass + " is null");
    ClassHierarchyGraph chGraph = ClassHierarchyGraph.v();

    for (Map.Entry<Long, JavaHeapElem> entry : this.entrySet()) {
      Type type = entry.getValue().type;
      if (type instanceof RefType) {
        SootClass entryKlass = ((RefType)type).getSootClass();
        if (entryKlass == klassToFind || 
            (entryKlass != klassToFind && chGraph.isSubClass(entryKlass, klassToFind))) {
          objs.put(entry.getKey(), entry.getValue());
        }
      }
    }

    return objs.lastEntry().getValue();
  }

  public Object clone() {
    JavaHeap newHeap = new JavaHeap();

    for (Map.Entry<Long, JavaHeapElem> entry : this.entrySet()) {
      newHeap.put(entry.getKey(), entry.getValue().clone());
    }

    for (Map.Entry<Long, JavaHeapElem> entry : newHeap.entrySet()) {
      entry.getValue().deepClone(newHeap, this.get(entry.getKey()));
    }

    return newHeap;
  }
}
