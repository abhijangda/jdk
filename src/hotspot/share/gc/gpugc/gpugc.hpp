#ifndef __GPU_GC_HPP__
#define __GPU_GC_HPP__

namespace GPUGC {

enum HeapEventType {
  FieldSet = 0,
  NewObject,
  NewArray,
  ArrayElemSet,
  CopyObject,
  CopyArray,
  CopyArrayOffsets,
  CopyArrayLength,
  MoveObject,
  ClearContiguousSpace,
  NewPrimitiveArray,
  CopySameArray,
  NewObjectSizeInBits,
  FieldSetWithNewObject,
  CopyNewObject,
  CopyNewArray,
  CopyNewArrayOfSameLength,
  None,
  Dummy,
  LARGE_VALUE = 0x1000000000000000ULL //To use 64-bit enums
};


  #define PROCESS_EVENT_TYPE(p) case(p): return #p;
  static const char* heapEventTypeString(HeapEventType value) {
    switch (value) {
        PROCESS_EVENT_TYPE(None)
        PROCESS_EVENT_TYPE(FieldSet)
        PROCESS_EVENT_TYPE(NewObject)
        PROCESS_EVENT_TYPE(NewArray)
        PROCESS_EVENT_TYPE(ArrayElemSet)
        PROCESS_EVENT_TYPE(CopyObject)
        PROCESS_EVENT_TYPE(CopyArray)
        PROCESS_EVENT_TYPE(CopyArrayOffsets)
        PROCESS_EVENT_TYPE(CopyArrayLength)
        PROCESS_EVENT_TYPE(MoveObject)
        PROCESS_EVENT_TYPE(ClearContiguousSpace)
        PROCESS_EVENT_TYPE(Dummy)
        PROCESS_EVENT_TYPE(NewPrimitiveArray)
        PROCESS_EVENT_TYPE(CopySameArray)
        PROCESS_EVENT_TYPE(NewObjectSizeInBits)
        PROCESS_EVENT_TYPE(FieldSetWithNewObject)
        PROCESS_EVENT_TYPE(LARGE_VALUE)

        default:
          ShouldNotReachHere();
          return NULL;
    }
  }
  
struct HeapEvent {
  uint64_t src;
  uint64_t dst;
  uint64_t method;
  uint64_t bci;

  HeapEvent(uint64_t s, uint64_t d) : src(s), dst(d)
  , method(0), bci(0) 
  {}
  HeapEvent() : src(0), dst(0)
  , method(0), bci(0)
  {}

  uint64_t getbci() {return bci;}
  uint64_t getmethod() {return method;}
};
  
int gpugc();
};

#endif