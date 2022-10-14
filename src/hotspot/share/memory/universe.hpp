/*
 * Copyright (c) 1997, 2021, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 *
 */

#ifndef SHARE_MEMORY_UNIVERSE_HPP
#define SHARE_MEMORY_UNIVERSE_HPP

#include "gc/shared/verifyOption.hpp"
#include "oops/array.hpp"
#include "oops/oopHandle.hpp"
#include "runtime/handles.hpp"
#include "utilities/linkedlist.hpp"

#include <pthread.h>
#include <semaphore.h>
#include <sys/mman.h>
#include <unistd.h>

#include <vector>
#include <limits>
#include <unordered_map>
#include <map>
#include <set>
#include <unordered_set>
#include <algorithm>
#include <deque>

// Universe is a name space holding known system classes and objects in the VM.
//
// Loaded classes are accessible through the SystemDictionary.
//
// The object heap is allocated and accessed through Universe, and various allocation
// support is provided. Allocation by the interpreter and compiled code is done inline
// and bails out to Scavenge::invoke_and_allocate.

class CollectedHeap;
class DeferredObjAllocEvent;
class OopStorage;
class ReservedHeapSpace;

// A helper class for caching a Method* when the user of the cache
// only cares about the latest version of the Method*.  This cache safely
// interacts with the RedefineClasses API.

class LatestMethodCache : public CHeapObj<mtClass> {
  // We save the Klass* and the idnum of Method* in order to get
  // the current cached Method*.
 private:
  Klass*                _klass;
  int                   _method_idnum;

 public:
  LatestMethodCache()   { _klass = NULL; _method_idnum = -1; }
  ~LatestMethodCache()  { _klass = NULL; _method_idnum = -1; }

  void   init(Klass* k, Method* m);
  Klass* klass() const           { return _klass; }
  int    method_idnum() const    { return _method_idnum; }

  Method* get_method();

  // CDS support.  Replace the klass in this with the archive version
  // could use this for Enhanced Class Redefinition also.
  void serialize(SerializeClosure* f) {
    f->do_ptr((void**)&_klass);
  }
  void metaspace_pointers_do(MetaspaceClosure* it);
};

class Universe: AllStatic {
  // Ugh.  Universe is much too friendly.
  friend class MarkSweep;
  friend class oopDesc;
  friend class ClassLoader;
  friend class SystemDictionary;
  friend class ReservedHeapSpace;
  friend class VMStructs;
  friend class VM_PopulateDumpSharedSpace;
  friend class Metaspace;
  friend class MetaspaceShared;
  friend class vmClasses;

  friend jint  universe_init();
  friend void  universe2_init();
  friend bool  universe_post_init();
  friend void  universe_post_module_init();

 private:
  // Known classes in the VM
  static Klass* _typeArrayKlassObjs[T_LONG+1];
  static Klass* _objectArrayKlassObj;

  // Known objects in the VM
  static OopHandle    _main_thread_group;             // Reference to the main thread group object
  static OopHandle    _system_thread_group;           // Reference to the system thread group object

  static OopHandle    _the_empty_class_array;         // Canonicalized obj array of type java.lang.Class
  static OopHandle    _the_null_string;               // A cache of "null" as a Java string
  static OopHandle    _the_min_jint_string;           // A cache of "-2147483648" as a Java string

  static OopHandle    _the_null_sentinel;             // A unique object pointer unused except as a sentinel for null.

  // preallocated error objects (no backtrace)
  static OopHandle    _out_of_memory_errors;

  // preallocated cause message for delayed StackOverflowError
  static OopHandle    _delayed_stack_overflow_error_message;

  static LatestMethodCache* _finalizer_register_cache; // static method for registering finalizable objects
  static LatestMethodCache* _loader_addClass_cache;    // method for registering loaded classes in class loader vector
  static LatestMethodCache* _throw_illegal_access_error_cache; // Unsafe.throwIllegalAccessError() method
  static LatestMethodCache* _throw_no_such_method_error_cache; // Unsafe.throwNoSuchMethodError() method
  static LatestMethodCache* _do_stack_walk_cache;      // method for stack walker callback

  static Array<int>*            _the_empty_int_array;            // Canonicalized int array
  static Array<u2>*             _the_empty_short_array;          // Canonicalized short array
  static Array<Klass*>*         _the_empty_klass_array;          // Canonicalized klass array
  static Array<InstanceKlass*>* _the_empty_instance_klass_array; // Canonicalized instance klass array
  static Array<Method*>*        _the_empty_method_array;         // Canonicalized method array

  static Array<Klass*>*  _the_array_interfaces_array;

  // array of preallocated error objects with backtrace
  static OopHandle     _preallocated_out_of_memory_error_array;

  // number of preallocated error objects available for use
  static volatile jint _preallocated_out_of_memory_error_avail_count;

  static OopHandle    _null_ptr_exception_instance;   // preallocated exception object
  static OopHandle    _arithmetic_exception_instance; // preallocated exception object
  static OopHandle    _virtual_machine_error_instance; // preallocated exception object

  // References waiting to be transferred to the ReferenceHandler
  static OopHandle    _reference_pending_list;

  // The particular choice of collected heap.
  static CollectedHeap* _collectedHeap;

  static intptr_t _non_oop_bits;

  // array of dummy objects used with +FullGCAlot
  debug_only(static OopHandle   _fullgc_alot_dummy_array;)
  debug_only(static int         _fullgc_alot_dummy_next;)

  // Compiler/dispatch support
  static int  _base_vtable_size;                      // Java vtbl size of klass Object (in words)

  // Initialization
  static bool _bootstrapping;                         // true during genesis
  static bool _module_initialized;                    // true after call_initPhase2 called
  static bool _fully_initialized;                     // true after universe_init and initialize_vtables called

  // the array of preallocated errors with backtraces
  static objArrayOop  preallocated_out_of_memory_errors();

  static objArrayOop out_of_memory_errors();
  // generate an out of memory error; if possible using an error with preallocated backtrace;
  // otherwise return the given default error.
  static oop        gen_out_of_memory_error(oop default_err);

  static OopStorage* _vm_weak;
  static OopStorage* _vm_global;

  static jint initialize_heap();
  static void initialize_tlab();
  static void initialize_basic_type_mirrors(TRAPS);
  static void fixup_mirrors(TRAPS);

  static void compute_base_vtable_size();             // compute vtable size of class Object

  static void genesis(TRAPS);                         // Create the initial world

  // Mirrors for primitive classes (created eagerly)
  static oop check_mirror(oop m) {
    assert(m != NULL, "mirror not initialized");
    return m;
  }

  // Debugging
  static int _verify_count;                           // number of verifies done
  static long verify_flags;

  static uintptr_t _verify_oop_mask;
  static uintptr_t _verify_oop_bits;

 public:
  
 template <class T>
  struct STLAllocator {
    typedef T value_type;

    STLAllocator() {}

    template <class U> constexpr STLAllocator (const STLAllocator <U>& src) noexcept {}
    template <class U>
    bool operator==(const STLAllocator <U>& u) { return true; }
    template <class U>
    bool operator!=(const STLAllocator <U>& u) { return false; }

    T* allocate(size_t n) {return (T*)mmap_heap.malloc(n*sizeof(T));}
    void deallocate(T* p, size_t n) noexcept {mmap_heap.free(p, n * sizeof(T));}
  };

  class MmapHeap {
    //PAGE_SIZE is 4096
    const int PAGE_SIZE;
    uint8_t* alloc_ptr;
    size_t curr_ptr;
    const size_t ALLOC_SIZE;
    const size_t LARGE_OBJ_SIZE;

    struct ListNode {
      ListNode* next;
      size_t size;
    };
    
    // 2^20 = 4*1024*1024
    static const int LOG_MAX_SMALL_OBJ_SIZE = 20;
    // free_list for small objects
    ListNode* free_lists[LOG_MAX_SMALL_OBJ_SIZE + 1];
    static pthread_mutex_t lock;

    public:
    MmapHeap() : PAGE_SIZE(getpagesize()), ALLOC_SIZE(256*1024*PAGE_SIZE), LARGE_OBJ_SIZE(1<<20) {
      alloc_ptr = NULL;
      curr_ptr = 0;
      for (int i = 0; i < LOG_MAX_SMALL_OBJ_SIZE + 1; i++)
        free_lists[i] = NULL;
    };

    size_t remaining_size_in_curr_alloc() const {
      if (alloc_ptr == NULL) return 0;
      return ALLOC_SIZE - curr_ptr;
    }

    bool is_power_of_2(size_t x) const {
      return (x != 0) && ((x & (x - 1)) == 0);
    }

    int32_t ilog2(uint64_t x) {
      int32_t log = sizeof(uint64_t) * 8 - __builtin_clzl(x) - 1;
      assert(1UL<<log == x, "sanity");
      return log;
    }

    uint32_t next_power_of_2(uint32_t v) const {
      v--;
      v |= v >> 1;
      v |= v >> 2;
      v |= v >> 4;
      v |= v >> 8;
      v |= v >> 16;
      v++;

      return v;
    }

    uint64_t next_power_of_2(uint64_t v) const {
      v--;
      v |= v >> 1;
      v |= v >> 2;
      v |= v >> 4;
      v |= v >> 8;
      v |= v >> 16;
      v |= v >> 32;
      v++;

      return v;
    }

    size_t adjust_size(size_t sz) const {
      sz = std::max(sz, sizeof(ListNode));
      if (!is_power_of_2(sz)) {
        sz = next_power_of_2(sz);
      }

      return sz;
    }

    size_t multiple_of_page_size(size_t sz) const {
      return ((sz + PAGE_SIZE - 1)/PAGE_SIZE)*PAGE_SIZE;
    }

    void* malloc(size_t sz) {
      // if (sz == 0) return NULL;
      pthread_mutex_lock(&lock);
      void* ptr = NULL;
      sz = adjust_size(sz);

      if (sz >= LARGE_OBJ_SIZE) {
        // printf("sz %ld multiple_of_page_size(sz) %ld\n", sz, multiple_of_page_size(sz));
        sz = multiple_of_page_size(sz);
        ptr = Universe::mmap(sz);
      } else {      
        if (alloc_ptr == NULL) {
          alloc_ptr = (uint8_t*)Universe::mmap(ALLOC_SIZE);
          curr_ptr = 0;
        }

        int log_size = ilog2(sz);
        assert(log_size < LOG_MAX_SMALL_OBJ_SIZE, "sanity '%d' '%ld' < '%d'", log_size, sz, LOG_MAX_SMALL_OBJ_SIZE);
        if (free_lists[log_size] != NULL) {
          ListNode* list_head = free_lists[log_size];
          free_lists[log_size] = list_head->next;

          if (list_head != NULL) {
            if (list_head->size < sz)
              printf("ptr %p ptr->size %ld sz %ld log_size %d\n", ptr, list_head->size, sz, log_size);
            ptr = list_head;
          }
        }
        
        if (ptr == NULL) {
          if (remaining_size_in_curr_alloc() >= sz) {
            ptr = (void*)(alloc_ptr + curr_ptr);
            curr_ptr += sz;
            assert(curr_ptr <= ALLOC_SIZE, "%ld <= %ld\n", curr_ptr, ALLOC_SIZE);
          } else {
            alloc_ptr = (uint8_t*)Universe::mmap(ALLOC_SIZE);
            curr_ptr = sz;
            if (curr_ptr > ALLOC_SIZE) {
              printf("%ld <= %ld\n", curr_ptr, ALLOC_SIZE);
            }
            ptr = alloc_ptr;
          }
        }
      }

      pthread_mutex_unlock(&lock);

      return ptr;
    }

    void free(void* p, size_t sz) {
      // if (sz == 0) return;
      if (p == NULL) return;
      
      sz = adjust_size(sz);
      pthread_mutex_lock(&lock);

      if (sz >= LARGE_OBJ_SIZE) {
        sz = multiple_of_page_size(sz);
        munmap(p, sz);
      } else {
        int log_size = ilog2(sz);
        if (log_size < LOG_MAX_SMALL_OBJ_SIZE) {
          ListNode* new_head = (ListNode*)p;
          new_head->next = free_lists[log_size];
          new_head->size = sz;
          free_lists[log_size] = new_head;
        }
      }
      pthread_mutex_unlock(&lock);
    }
  };

  static Universe::MmapHeap mmap_heap;
  template <typename K, typename V>
  using unordered_map = std::unordered_map<K, V, std::hash<K>, std::equal_to<K>, STLAllocator<std::pair<const K, V>>>;
  template <typename K>
  using set = std::set<K, std::less<K>, STLAllocator<K>>;
  template <typename K>
  using unordered_set = std::unordered_set<K, std::hash<K>, std::equal_to<K>, STLAllocator<K>>;
  template <typename K, typename V>
  using map = std::map<K, V, std::less<K>, STLAllocator<std::pair<const K, V>>>;
  template <typename V>
  using vector = std::vector<V, STLAllocator<V>>;
  template<class T> 
  using deque = std::deque<T, STLAllocator<T>>;

  static uint32_t checking;

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

  static bool is_verify_cause_full_gc;
  static bool is_verify_from_exit;
  struct HeapEvent {
    uint64_t src;
    uint64_t dst;
  };
  struct EventsToTransfer {
    HeapEvent* events;
    size_t length;
  };

  static EventsToTransfer events_to_transfer;

  static uint64_t heap_event_mask();
  static uint64_t encode_heap_event_dst(HeapEventType event_type, uint64_t src);
  static HeapEvent encode_heap_event(HeapEventType event_type, HeapEvent event);
  static HeapEventType decode_heap_event_type(HeapEvent event);
  static uint64_t decode_heap_event_dst(HeapEvent event);
  static HeapEvent decode_heap_event(Universe::HeapEvent event);
  static void add_heap_event_ptr(Universe::HeapEvent* ptr);
  static void remove_heap_event_ptr(Universe::HeapEvent* ptr);
  static void copy_heap_events(Universe::HeapEvent* ptr);
  static LinkedListImpl<HeapEvent*> all_heap_events;
  static pthread_mutex_t mutex_heap_event;
  static bool enable_transfer_events;
  static void transfer_events_to_gpu();
  static void transfer_events_to_gpu_no_zero(HeapEvent* events, size_t length);
  static void transfer_events_to_gpu_list_head();
  static sem_t cuda_semaphore;
  static void add_heap_events(Universe::HeapEventType event_type1, Universe::HeapEvent event1, Universe::HeapEventType event_type2, Universe::HeapEvent event2);
  static inline __attribute__((always_inline)) void add_heap_event(Universe::HeapEventType event_type, const Universe::HeapEvent event) {
    if (!InstrumentHeapEvents) return;
    
    if (CheckHeapEventGraphWithHeap)
      Universe::lock_mutex_heap_event();
    
    #ifndef PRODUCT
    // if (all_heap_events.find(heap_events) == NULL) {
    //   printf("835: heap_events %p\n", heap_events);
    //   abort();
    // }
    #endif
    
    // Thread* curr_thread = Thread::current();
    Universe::HeapEvent* heap_events = (CheckHeapEventGraphWithHeap) ? Universe::get_heap_events_ptr() : *all_heap_events.head()->data() ; //(Universe::HeapEvent*)(((char*)curr_thread) + 2048);
    uint64_t* heap_event_counter_ptr = (uint64_t*)heap_events;
    
    const uint64_t v = *heap_event_counter_ptr;
    *heap_event_counter_ptr = v + 1;
    // printf("449: v %ld\n", v);
    (&heap_events[1])[v] = encode_heap_event(event_type, event);

    // if ((&heap_events[1])[v-1].dst == event.dst) {
    //   printf("498: 0x%lx, 0x%lx, 0x%lx\n", (&heap_events[1])[v-1].src, event.src, event.dst);
    // }
    if (!UseMprotectForHeapGraphCheck) {
      if (v + 1 >= MaxHeapEvents) {
        if (CheckHeapEventGraphWithHeap)
          Universe::verify_heap_graph();
        else
          Universe::transfer_events_to_gpu_list_head();  
      }
    }

    if (CheckHeapEventGraphWithHeap)
      Universe::unlock_mutex_heap_event();
  }
  static void* cudaAllocHost(size_t size);
  static void lock_mutex_heap_event();
  static void unlock_mutex_heap_event();
  static void print_heap_event_counter();
  static HeapEvent* get_heap_events_ptr();
  static void verify_heap_graph();
  static void process_heap_event(Universe::HeapEvent event);
  static void verify_heap_graph_for_copy_array();
  static void* mmap(size_t sz) {
    void* ptr = ::mmap(NULL, sz, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, 0, 0);
    assert(ptr != NULL, "");
    if (ptr == MAP_FAILED) {
      perror("mmap failed");
      abort();
    }
    
    return ptr;
  }
  static void mprotect(void* ptr, size_t len, int prot) {
    if (((uint64_t)ptr) % 4096 != 0) {
      printf("mprotect failed: '%p' does not point to a page\n", ptr);
      abort();
    }

    int r = ::mprotect(ptr, len, prot);
    if (r == -1) {
      perror("mprotect failed:");
      abort();
    }
  }
  static size_t heap_events_buf_size();
  static bool handle_heap_events_sigsegv(int sig, siginfo_t* info);

  static void calculate_verify_data(HeapWord* low_boundary, HeapWord* high_boundary) PRODUCT_RETURN;

  // Known classes in the VM
  static Klass* boolArrayKlassObj()                 { return typeArrayKlassObj(T_BOOLEAN); }
  static Klass* byteArrayKlassObj()                 { return typeArrayKlassObj(T_BYTE); }
  static Klass* charArrayKlassObj()                 { return typeArrayKlassObj(T_CHAR); }
  static Klass* intArrayKlassObj()                  { return typeArrayKlassObj(T_INT); }
  static Klass* shortArrayKlassObj()                { return typeArrayKlassObj(T_SHORT); }
  static Klass* longArrayKlassObj()                 { return typeArrayKlassObj(T_LONG); }
  static Klass* floatArrayKlassObj()                { return typeArrayKlassObj(T_FLOAT); }
  static Klass* doubleArrayKlassObj()               { return typeArrayKlassObj(T_DOUBLE); }

  static Klass* objectArrayKlassObj()               { return _objectArrayKlassObj; }

  static Klass* typeArrayKlassObj(BasicType t) {
    assert((uint)t >= T_BOOLEAN, "range check for type: %s", type2name(t));
    assert((uint)t < T_LONG+1,   "range check for type: %s", type2name(t));
    assert(_typeArrayKlassObjs[t] != NULL, "domain check");
    return _typeArrayKlassObjs[t];
  }

  // Known objects in the VM
  static oop int_mirror();
  static oop float_mirror();
  static oop double_mirror();
  static oop byte_mirror();
  static oop bool_mirror();
  static oop char_mirror();
  static oop long_mirror();
  static oop short_mirror();
  static oop void_mirror();

  // Table of primitive type mirrors, excluding T_OBJECT and T_ARRAY
  // but including T_VOID, hence the index including T_VOID
  static OopHandle _mirrors[T_VOID+1];

  static oop java_mirror(BasicType t);
  static void replace_mirror(BasicType t, oop obj);

  static oop      main_thread_group();
  static void set_main_thread_group(oop group);

  static oop      system_thread_group();
  static void set_system_thread_group(oop group);

  static objArrayOop  the_empty_class_array ();

  static oop          the_null_string();
  static oop          the_min_jint_string();

  static oop          null_ptr_exception_instance();
  static oop          arithmetic_exception_instance();
  static oop          virtual_machine_error_instance();
  static oop          vm_exception()                  { return virtual_machine_error_instance(); }

  static Array<Klass*>* the_array_interfaces_array()  { return _the_array_interfaces_array;   }
  static Method*      finalizer_register_method()     { return _finalizer_register_cache->get_method(); }
  static Method*      loader_addClass_method()        { return _loader_addClass_cache->get_method(); }

  static Method*      throw_illegal_access_error()    { return _throw_illegal_access_error_cache->get_method(); }
  static Method*      throw_no_such_method_error()    { return _throw_no_such_method_error_cache->get_method(); }

  static Method*      do_stack_walk_method()          { return _do_stack_walk_cache->get_method(); }

  static oop          the_null_sentinel();
  static address      the_null_sentinel_addr()        { return (address) &_the_null_sentinel;  }

  // Function to initialize these
  static void initialize_known_methods(TRAPS);

  static void create_preallocated_out_of_memory_errors(TRAPS);

  // Reference pending list manipulation.  Access is protected by
  // Heap_lock.  The getter, setter and predicate require the caller
  // owns the lock.  Swap is used by parallel non-concurrent reference
  // processing threads, where some higher level controller owns
  // Heap_lock, so requires the lock is locked, but not necessarily by
  // the current thread.
  static oop          reference_pending_list();
  static void         clear_reference_pending_list();
  static bool         has_reference_pending_list();
  static oop          swap_reference_pending_list(oop list);

  static Array<int>*             the_empty_int_array()    { return _the_empty_int_array; }
  static Array<u2>*              the_empty_short_array()  { return _the_empty_short_array; }
  static Array<Method*>*         the_empty_method_array() { return _the_empty_method_array; }
  static Array<Klass*>*          the_empty_klass_array()  { return _the_empty_klass_array; }
  static Array<InstanceKlass*>*  the_empty_instance_klass_array() { return _the_empty_instance_klass_array; }

  // OutOfMemoryError support. Returns an error with the required message. The returned error
  // may or may not have a backtrace. If error has a backtrace then the stack trace is already
  // filled in.
  static oop out_of_memory_error_java_heap();
  static oop out_of_memory_error_c_heap();
  static oop out_of_memory_error_metaspace();
  static oop out_of_memory_error_class_metaspace();
  static oop out_of_memory_error_array_size();
  static oop out_of_memory_error_gc_overhead_limit();
  static oop out_of_memory_error_realloc_objects();

  // Throw default _out_of_memory_error_retry object as it will never propagate out of the VM
  static oop out_of_memory_error_retry();
  static oop delayed_stack_overflow_error_message();

  // The particular choice of collected heap.
  static CollectedHeap* heap() { return _collectedHeap; }

  DEBUG_ONLY(static bool is_gc_active();)
  DEBUG_ONLY(static bool is_in_heap(const void* p);)
  DEBUG_ONLY(static bool is_in_heap_or_null(const void* p) { return p == NULL || is_in_heap(p); })

  // Reserve Java heap and determine CompressedOops mode
  static ReservedHeapSpace reserve_heap(size_t heap_size, size_t alignment);

  // Global OopStorages
  static OopStorage* vm_weak();
  static OopStorage* vm_global();
  static void oopstorage_init();

  // Testers
  static bool is_bootstrapping()                      { return _bootstrapping; }
  static bool is_module_initialized()                 { return _module_initialized; }
  static bool is_fully_initialized()                  { return _fully_initialized; }

  static bool        on_page_boundary(void* addr);
  static bool        should_fill_in_stack_trace(Handle throwable);
  static void check_alignment(uintx size, uintx alignment, const char* name);

  // CDS support
  static void serialize(SerializeClosure* f);

  // Apply "f" to all klasses for basic types (classes not present in
  // SystemDictionary).
  static void basic_type_classes_do(void f(Klass*));
  static void basic_type_classes_do(KlassClosure* closure);
  static void metaspace_pointers_do(MetaspaceClosure* it);

  // Debugging
  enum VERIFY_FLAGS {
    Verify_Threads = 1,
    Verify_Heap = 2,
    Verify_SymbolTable = 4,
    Verify_StringTable = 8,
    Verify_CodeCache = 16,
    Verify_SystemDictionary = 32,
    Verify_ClassLoaderDataGraph = 64,
    Verify_MetaspaceUtils = 128,
    Verify_JNIHandles = 256,
    Verify_CodeCacheOops = 512,
    Verify_ResolvedMethodTable = 1024,
    Verify_StringDedup = 2048,
    Verify_All = -1
  };
  static void initialize_verify_flags();
  static bool should_verify_subset(uint subset);
  static void verify(VerifyOption option, const char* prefix);
  static void verify(const char* prefix) {
    verify(VerifyOption_Default, prefix);
  }
  static void verify() {
    verify("");
  }

  static int  verify_count()       { return _verify_count; }
  static void print_on(outputStream* st);
  static void print_heap_at_SIGBREAK();

  // Change the number of dummy objects kept reachable by the full gc dummy
  // array; this should trigger relocation in a sliding compaction collector.
  debug_only(static bool release_fullgc_alot_dummy();)
  // The non-oop pattern (see compiledIC.hpp, etc)
  static void*         non_oop_word();
  static bool contains_non_oop_word(void* p);

  // Oop verification (see MacroAssembler::verify_oop)
  static uintptr_t verify_oop_mask()          PRODUCT_RETURN0;
  static uintptr_t verify_oop_bits()          PRODUCT_RETURN0;
  static uintptr_t verify_mark_bits()         PRODUCT_RETURN0;
  static uintptr_t verify_mark_mask()         PRODUCT_RETURN0;

  // Compiler support
  static int base_vtable_size()               { return _base_vtable_size; }
};

#endif // SHARE_MEMORY_UNIVERSE_HPP
