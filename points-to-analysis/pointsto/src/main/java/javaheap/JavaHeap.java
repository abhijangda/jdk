package javaheap;

import java.util.HashMap;

import soot.ArrayType;
import soot.RefType;
import utils.Utils;

public class JavaHeap extends HashMap<Long, JavaHeapElem> {
  private JavaHeap() {
    super();
  }

  private static JavaHeap javaHeap = null;

  public static JavaHeap v() {
    if (javaHeap == null) 
      javaHeap = new JavaHeap();
    return javaHeap;
  }
  
  public void update(HeapEvent event) {
    if (event.eventType == HeapEvent.EventType.NewObject) {
      put(event.dstPtr, new JavaObject((RefType)event.dstClass));
    } else if (event.eventType == HeapEvent.EventType.NewArray || 
               event.eventType == HeapEvent.EventType.NewPrimitiveArray) {
      put(event.dstPtr, new JavaArray((ArrayType)event.dstClass, (int)event.srcPtr));
    } else if (event.eventType == HeapEvent.EventType.ObjectFieldSet) {
      JavaHeapElem val = null;
      if (event.srcClass == null && event.srcPtr != 0) utils.Utils.debugPrintln("srcClass is null in " + event.toString());
      if (event.srcPtr == 0) val = null;
      else {
        if (!containsKey(event.srcPtr)) {
          utils.Utils.debugLog("Creating the object %d not found in heap\n", event.srcPtr);
          if (event.srcClass instanceof RefType) {
            val = new JavaObject((RefType)event.srcClass);
          } else if (event.srcClass instanceof ArrayType) {
            val = new JavaArray((ArrayType)event.srcClass,1000);
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
        utils.Utils.debugLog("Creating the object %d not found in heap\n", event.dstPtr);
        put(event.dstPtr, new JavaObject((RefType)event.dstClass));
      }
      ((JavaObject)get(event.dstPtr)).addField(event.fieldName, val);
    } else if (event.eventType == HeapEvent.EventType.ArrayElementSet) {
      JavaHeapElem val = null;
      if (event.srcPtr == 0) val = null;
      else {
        if (!containsKey(event.srcPtr)) {
          utils.Utils.debugLog("Creating the object %d not found in heap\n", event.srcPtr);
          if (event.srcClass instanceof RefType) {
            val = new JavaObject((RefType)event.srcClass);
          } else if (event.srcClass instanceof ArrayType) {
            val = new JavaArray((ArrayType)event.srcClass,1000);
          }
          put(event.srcPtr, val);
        }
        val = get(event.srcPtr);
      }

      if (!containsKey(event.dstPtr)) {
        utils.Utils.debugLog("Creating the object %d not found in heap\n", event.dstPtr);
        put(event.dstPtr, new JavaArray(event.dstClass, 1000));
      }

      ((JavaArray)get(event.dstPtr)).setElem(event.elemIndex, val);
    } else {
      utils.Utils.debugAssert(false, "not handled event type");
    }
  }

  public JavaObject createNewObject(RefType type) {
    long address = size();
    while(containsKey(address)) address++;

    JavaObject obj = new JavaObject(type);
    put(address, obj);

    return obj;
  }

  public JavaHeapElem get(long ptr) {
    if (ptr == 0) {
      return null;
    }

    return super.get(ptr);
  }
}
