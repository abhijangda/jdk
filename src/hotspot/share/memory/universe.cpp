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

#include "precompiled.hpp"
#include "cds/dynamicArchive.hpp"
#include "cds/heapShared.hpp"
#include "cds/metaspaceShared.hpp"
#include "classfile/classLoader.hpp"
#include "classfile/classLoaderDataGraph.hpp"
#include "classfile/javaClasses.hpp"
#include "classfile/stringTable.hpp"
#include "classfile/symbolTable.hpp"
#include "classfile/systemDictionary.hpp"
#include "classfile/vmClasses.hpp"
#include "classfile/vmSymbols.hpp"
#include "code/codeBehaviours.hpp"
#include "code/codeCache.hpp"
#include "compiler/oopMap.hpp"
#include "gc/shared/collectedHeap.inline.hpp"
#include "gc/shared/gcArguments.hpp"
#include "gc/shared/gcConfig.hpp"
#include "gc/shared/gcLogPrecious.hpp"
#include "gc/shared/gcTraceTime.inline.hpp"
#include "gc/shared/oopStorageSet.hpp"
#include "gc/shared/stringdedup/stringDedup.hpp"
#include "gc/shared/tlab_globals.hpp"
#include "logging/log.hpp"
#include "logging/logStream.hpp"
#include "memory/metadataFactory.hpp"
#include "memory/metaspaceClosure.hpp"
#include "memory/metaspaceCounters.hpp"
#include "memory/metaspaceUtils.hpp"
#include "memory/oopFactory.hpp"
#include "memory/resourceArea.hpp"
#include "memory/universe.hpp"
#include "oops/compressedOops.hpp"
#include "oops/instanceKlass.hpp"
#include "oops/instanceMirrorKlass.hpp"
#include "oops/klass.inline.hpp"
#include "oops/objArrayOop.inline.hpp"
#include "oops/oop.inline.hpp"
#include "oops/oopHandle.inline.hpp"
#include "oops/typeArrayKlass.hpp"
#include "prims/resolvedMethodTable.hpp"
#include "runtime/arguments.hpp"
#include "runtime/atomic.hpp"
#include "runtime/flags/jvmFlagLimit.hpp"
#include "runtime/handles.inline.hpp"
#include "runtime/init.hpp"
#include "runtime/java.hpp"
#include "runtime/jniHandles.hpp"
#include "runtime/thread.inline.hpp"
#include "runtime/timerTrace.hpp"
#include "services/memoryService.hpp"
#include "utilities/align.hpp"
#include "utilities/autoRestore.hpp"
#include "utilities/debug.hpp"
#include "utilities/formatBuffer.hpp"
#include "utilities/macros.hpp"
#include "utilities/ostream.hpp"
#include "utilities/preserveException.hpp"

#include "utilities/hashtable.hpp"
#include "utilities/hashtable.inline.hpp"
#include "runtime/fieldDescriptor.inline.hpp"
#include "memory/allocation.hpp"
#include "utilities/quickSort.hpp"
#include "oops/fieldStreams.hpp"
#include "oops/fieldStreams.inline.hpp"
#include "oops/instanceMirrorKlass.inline.hpp"

pthread_mutex_t Universe::mutex_heap_event = PTHREAD_MUTEX_INITIALIZER;
Universe::HeapEvent Universe::heap_events[1+Universe::max_heap_events] = {};
uint64_t* Universe::heap_event_counter_ptr = (uint64_t*)&Universe::heap_events[0].heap_event_type;
bool Universe::enable_heap_event_logging = true;
bool Universe::enable_heap_graph_verify = true && Universe::enable_heap_event_logging;
bool Universe::heap_event_stub_in_C1_LIR = true && Universe::enable_heap_event_logging;
bool Universe::enable_heap_event_logging_in_interpreter = true && Universe::enable_heap_event_logging;
bool Universe::enable_transfer_events = false ;
sem_t Universe::cuda_semaphore;

#include<vector>
#include<limits>
#include<unordered_map>
#include<set>
#include<algorithm>

#include <sys/mman.h>
#include <stdlib.h>
#include <unistd.h>
#include <sys/syscall.h>
#include <string.h>
#define gettid() syscall(SYS_gettid)
#include "runtime/interfaceSupport.inline.hpp"

class MmapHeap {
  const int PAGE_SIZE;
  uint8_t* alloc_ptr;
  size_t curr_ptr;
  const size_t ALLOC_SIZE;
  const size_t LARGE_OBJ_SIZE;

  struct ListNode {
    ListNode* next;
    size_t size;
  };

  ListNode* free_list;

  public:
  MmapHeap() : PAGE_SIZE(getpagesize()), ALLOC_SIZE(256*1024*PAGE_SIZE), LARGE_OBJ_SIZE(1024*PAGE_SIZE) {
    alloc_ptr = (uint8_t*)mmap(NULL, ALLOC_SIZE, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS | MAP_NORESERVE, 0, 0);
    assert(alloc_ptr != NULL, "");
    curr_ptr = 0;
    free_list = NULL;
  };

  size_t remaining_size_in_curr_alloc() const {
    return ALLOC_SIZE - curr_ptr;
  }

  bool is_power_of_2(size_t x) const {
      return (x != 0) && ((x & (x - 1)) == 0);
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
    if (sz % PAGE_SIZE == 0) return sz;
    return ((sz + PAGE_SIZE - 1)/PAGE_SIZE)*PAGE_SIZE;
  }

  void* malloc(size_t sz) {
    sz = adjust_size(sz);

    if (sz >= LARGE_OBJ_SIZE) {
      sz = multiple_of_page_size(sz);
      void* ptr = mmap(NULL, sz, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, 0, 0);
      assert(ptr != NULL, "");
      if (ptr == MAP_FAILED) {
        perror("mmap failed");
      }
      return ptr;
    }

    if (free_list != NULL) {
      ListNode* ptr = (ListNode*)free_list;
      ListNode* prev = NULL;
      while (ptr != NULL) {
        if (sz <= ptr->size) {
          if (prev) {
            prev->next = ptr->next;
          } else {
            free_list = ptr->next;
          }

          break;
        }
        prev = ptr;
        ptr = ptr->next;
      }
      
      if (ptr != NULL) {
        if (ptr->size < sz)
          printf("ptr %p ptr->size %ld sz %ld\n", ptr, ptr->size, sz);
        return (void*)ptr;
      }
    }
    
    if (remaining_size_in_curr_alloc() >= sz) {
      void* ptr = (void*)(alloc_ptr + curr_ptr);
      curr_ptr += sz;
      assert(curr_ptr <= ALLOC_SIZE, "%ld <= %ld\n", curr_ptr, ALLOC_SIZE);
      return ptr;
    }
    
    alloc_ptr = (uint8_t*)mmap(NULL, ALLOC_SIZE, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, 0, 0);
    assert(alloc_ptr != NULL, "");
    curr_ptr = sz;
    if (curr_ptr > ALLOC_SIZE) {
      printf("%ld <= %ld\n", curr_ptr, ALLOC_SIZE);
    }
    return (void*)alloc_ptr;
  }

  void free(void* p, size_t sz) {
    sz = adjust_size(sz);
    if (sz >= LARGE_OBJ_SIZE) {
      sz = multiple_of_page_size(sz);
      munmap(p, sz);
      return;
    } 
    ListNode* new_head = (ListNode*)p;
    new_head->next = free_list;
    new_head->size = sz;
    free_list = new_head;
  }
};

static MmapHeap* mmap_heap = nullptr;

template <class T>
struct STLAllocator {
  typedef T value_type;

  STLAllocator() {}

  template <class U> constexpr STLAllocator (const STLAllocator <U>& src) noexcept {}

  T* allocate(size_t n) {
    //os::malloc in works in DEBUG but not in PRODUCT build.
    return (T*)mmap_heap->malloc(n*sizeof(T));
  }
 
  void deallocate(T* p, std::size_t n) noexcept {
    mmap_heap->free(p, n * sizeof(T));
  }
};

template <class T, class U>
bool operator==(const STLAllocator <T>&, const STLAllocator <U>&) { return true; }
template <class T, class U>
bool operator!=(const STLAllocator <T>&, const STLAllocator <U>&) { return false; }

template <typename K, typename V>
using unordered_map = std::unordered_map<K, V, std::hash<K>, std::equal_to<K>, STLAllocator<std::pair<const K, V>>>;
template <typename K>
using set = std::set<K, std::less<K>, STLAllocator<const K>>;

template<typename T, uint64_t MAX_SIZE>
class MmapArray {
  T* _buf;
  uint64_t _length;

  bool check_length(uint64_t l) const {
    if (l >= MAX_SIZE) {
      printf("check_length failed: %ld >= %ld\n", l, MAX_SIZE);
      return false;
    }
    return true;
  }
  
  bool check_bounds(uint64_t i) const {
    if (i >= _length || i < 0) {
      printf("%ld outside array bounds [0, %ld]\n", i, _length);
      return false;
    }

    return true;
  }
  
  public:
  MmapArray() {
    _buf = (T*)mmap(NULL, MAX_SIZE*sizeof(T), PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, 0, 0);
    _length = 0;
  }

  void append(T x) {
    check_length(_length+1);
    _buf[_length++] = x;
  }

  T& at(uint64_t i) {
    check_bounds(i);
    return _buf[i];
  }  
};

class ObjectNode {
  oop         _obj;
  size_t      _size;
  uint64_t    _id;
  set<ObjectNode> _fields;
  
  public:
    static unordered_map<oopDesc*, ObjectNode> oop_to_obj_node;
    ObjectNode() : _obj(NULL), _size(0), _id(0) {}
    ObjectNode(oop obj, size_t size, uint64_t id) : _obj(obj), _size(size), _id(id) {}
    ~ObjectNode() {}
    size_t size() const {return _size;}
};

unordered_map<oopDesc*, ObjectNode> ObjectNode::oop_to_obj_node;

Universe::HeapEvent* sorted_field_set_events = NULL;
size_t sorted_field_set_events_size = 0;
const size_t SORTED_FIELD_SET_EVENTS_MAX_SIZE = 1L << 30;

Universe::HeapEvent* sorted_new_object_events = NULL;
size_t sorted_new_object_events_size = 0;

const size_t SORTED_NEW_OBJECT_EVENTS_MAX_SIZE = 1L << 30;

static uint8_t* is_heap_event_in_heap = NULL;
static InstanceKlass** iks_static_fields_checked = NULL;
static size_t iks_static_fields_checked_size = 0;
uint32_t Universe::checking = 0;
uint64_t first_klass_addr = 1L << 63;
uint64_t last_klass_addr = 0;
uint64_t first_oop_addr = 1L << 63;
uint64_t last_oop_addr = 0;

template<typename T>
static T min(T a1, T a2) { return (a1 < a2) ? a1 : a2;}
template<typename T>
static T max(T a1, T a2) { return (a1 > a2) ? a1 : a2;}


int has_heap_event(Universe::HeapEvent* sorted_events, uint64_t dst_address, int start, int end, int* floor_idx = NULL) {
  int l = start;
  int r = end;
  int m;
  if (floor_idx) {
    if (dst_address <= sorted_events[end - 1].address.dst)
      *floor_idx = 0;
  }
  while (l <= r) {
      int m = l + (r - l) / 2;
      // printf("m %d l %d r %d _len %d\n", m, l, r, sorted_field_set_events.length());
      // Check if x is present at mid
      if (sorted_events[m].address.dst == dst_address) {
        if (floor_idx)
          *floor_idx = m;
        return m;
      }

      // If x greater, ignore left half
      if (sorted_events[m].address.dst < dst_address) {
        if (floor_idx)
          *floor_idx = m;
        l = m + 1;
      }
      // If x is smaller, ignore right half
      else
          r = m - 1;
  }

  // if we reach here, then element was
  // not present
  return -1;
}


int has_heap_event_with_id(Universe::HeapEvent* sorted_events, uint64_t id, int start, int end) {
  int l = start;
  int r = end;
  int m;
  
  while (l <= r) {
      int m = l + (r - l) / 2;
      // printf("m %d l %d r %d _len %d\n", m, l, r, sorted_field_set_events.length());
      // Check if x is present at mid
      if (sorted_events[m].id == id) {
        return m;
      }

      // If x greater, ignore left half
      if (sorted_events[m].id < id) {
        l = m + 1;
      }
      // If x is smaller, ignore right half
      else
          r = m - 1;
  }

  // if we reach here, then element was
  // not present
  return -1;
}

template<typename T>
int binary_search(T* arr, int start, int end, T x) {
  // for (int i = 0; i < sorted_field_set_events.length(); i++) {
  //   // printf("141: 0x%lx\n", sorted_field_set_events.at(i).address.dst);
  //   if (sorted_field_set_events.at(i).address.dst == dst_address) {
  //     return true;
  //   }
  // }

  // if (checking > 15)
  // for (int i = 0; i < sorted_field_set_events.length(); i++) {
  //   // printf("141: 0x%lx\n", sorted_field_set_events.at(i).address.dst);
  //   printf("0x%lx\n", sorted_field_set_events.at(i).address.dst);
  // }

  // return false;
  int l = start;
  int r = end;
  int m;
  while (l <= r) {
      int m = l + (r - l) / 2;
      // printf("m %d l %d r %d _len %d\n", m, l, r, sorted_field_set_events.length());
      // Check if x is present at mid
      if (arr[m] == x)
          return m;

      // If x greater, ignore left half
      if (arr[m] < x) {
        l = m + 1;
      }
      // If x is smaller, ignore right half
      else
          r = m - 1;
  }

  // if we reach here, then element was
  // not present
  return -1;
}

static char* get_oop_klass_name(oop obj_, char buf[]) 
{
  if (obj_->klass() != NULL && (uint64_t)obj_->klass() != 0xbaadbabebaadbabe && obj_->klass()->is_klass())
    obj_->klass()->name()->as_C_string(buf, 1024);
  return buf;
}

BasicType signature_to_field_type(char* signature) {
  if (signature[0] == 'L')
    return T_OBJECT;
  if (signature[0] == '[')
    return T_ARRAY;
  
  return T_BOOLEAN; //Does not matter for this purpose yet.
}

int InstanceKlassPointerComparer(const void* a, const void* b) {
  InstanceKlass* bik = *(InstanceKlass**)b;
  InstanceKlass* aik = *(InstanceKlass**)a;
  if (aik < bik) return -1;
  if (aik == bik) return 0;
  return 1;
}

#if 0
class AllFields : public FieldClosure {
  oop obj_;
  
public:
  bool valid;
  int num_found, num_not_found;
  AllFields(oop obj): obj_(obj), valid(true), num_found(0), num_not_found(0) {}
  virtual void do_field(fieldDescriptor* fd) {
    if (fd->field_type() == T_OBJECT && fd->field_type() != T_ARRAY) { // if (is_reference_type(fd->field_type())) {
      uint64_t fd_address = ((uint64_t)(void*)obj_) + fd->offset();
      oop val = obj_->obj_field(fd->offset());
      // printf("((uint64_t)(void*)val)  0x%lx\n", ((uint64_t)(void*)val));
      if (val == 0 || ((uint64_t)(void*)val) == 0xbaadbabebaadbabe) return;
      
      bool found = has_heap_event(fd_address);
      char buf[2048] = {0};
      char buf2[2048] = {0};
      get_oop_klass_name(obj_, buf);

      if (false && !found) {
        printf("185: %s, %p, klass: %d, %d:%p\n", buf, (void*)obj_, obj_->klass()->id(), fd->offset(), (void*)val);

        fd->name()->as_C_string(buf2, 2048);
      
        // if (strstr(buf, "ConcurrentHashMap$Node") && strstr(buf2, "key")) 
        if(true) {
          printf("185: %s, %p, klass: %d, %d:%p:%s\n", buf, (void*)obj_, obj_->klass()->id(), fd->offset(), (void*)val, buf2);
          // printf("is_synthetic() %d is_transient %d\n", fd->is_synthetic(), fd->is_transient());
          // if (fd->has_initial_value()) {
          //   Symbol* content = java_lang_String::as_symbol_or_null(fd->string_initial_value(NULL));
          //   content->as_C_string(buf, 1024);
          //   printf("init val %s\n", buf);
          // }
          oop key = obj_->obj_field(fd->offset());
          get_oop_klass_name(key, buf);
          printf("212: key: %s\n", buf);
          // abort();
        }

        // if (strstr(buf2, "name")) {
        //   Symbol* content = java_lang_String::as_symbol_or_null(obj_->obj_field(fd->offset()));
        //   content->as_C_string(buf, 1024);
        //   printf("content %s\n", buf);
        // }
        // if (num_not_found > 1)
        // abort();
      }
      valid = valid && found; // && heapHashTable[fd_address].dst == 
      if (found)
        num_found++;
      else
        num_not_found++;
    }
  }
};
#endif

class AllObjects : public ObjectClosure {
  public:
    const bool print_not_found = false;
    bool valid;
    bool foundPuppy;
    int num_found, num_not_found;
    int num_src_not_correct;
    int num_statics_checked;
    int num_oops;
    int num_fields;
    
    AllObjects() : valid(true), foundPuppy(false), num_found(0), num_not_found(0), num_src_not_correct(0), num_statics_checked(0), num_oops(0), num_fields(0) {}
    
    virtual void do_object(oop obj) {
      // printf("obj %p\n", (void*)obj);
      // printf("is_instance %d\n", obj->is_instance());
      if (((uint64_t)(void*)obj) == 0xbaadbabebaadbabe)
        return;
      
      Klass* klass = obj->klass_or_null();

      //        klass->id() != InstanceMirrorKlassID && 
          // klass->id() != InstanceRefKlassID && 
          // klass->id() != InstanceClassLoaderKlassID &&
      if (klass && ((uint64_t)(void*)klass) != 0xbaadbabebaadbabe && 
          klass->is_klass()) {
          
        first_klass_addr = min(first_klass_addr, (uint64_t)klass);
        last_klass_addr = max(last_klass_addr, (uint64_t)klass);
        
        first_oop_addr = min(first_oop_addr, (uint64_t)(void*)obj);
        last_oop_addr = max(last_oop_addr, (uint64_t)(void*)obj); 

        char buf[1024];
        obj->klass()->name()->as_C_string(buf, 1024);

        num_oops++;
        //has_heap_event(sorted_new_object_events, (uint64_t)(void*)obj, 0, sorted_new_object_events_size - 1);
        auto oop_obj_node_pair = ObjectNode::oop_to_obj_node.find(obj);
        if (oop_obj_node_pair != ObjectNode::oop_to_obj_node.end()) {
          num_found++;
        } else {
          num_not_found++;
          if (print_not_found) { 
            printf("%p not found for %s: ik->id() %d\n", (void*)obj, buf, klass->id());
            const char* java_lang_String_str = "java/lang/String";
            if (strstr(buf, java_lang_String_str) && strlen(buf) == strlen(java_lang_String_str)) {
              int len;
              char* str = java_lang_String::as_utf8_string(obj, len);
              printf("str %s\n", str);
            }
          }
        }

        if (oop_obj_node_pair != ObjectNode::oop_to_obj_node.end()) {
          if (klass->is_instance_klass()) {
            if (obj->size() != oop_obj_node_pair->second.size()) {
              printf("%s: obj->size() %ld != %ld\n", buf, obj->size(), oop_obj_node_pair->second.size());
              num_src_not_correct++;
            }
          } else if (klass->id() == ObjArrayKlassID) {
            ObjArrayKlass* oak = (ObjArrayKlass*)klass;
            objArrayOop array = (objArrayOop)obj;
            if ((size_t)array->length() != oop_obj_node_pair->second.size()) {
              printf("%s: array->length() %d != %ld\n", buf, array->length(), oop_obj_node_pair->second.size());
              num_src_not_correct++;
            }
          }
        }

        if (klass->is_instance_klass()) {
          InstanceKlass* ik = (InstanceKlass*)klass;
          bool iks_static_fields_checked_changed = false;
          if (ik->id() == InstanceMirrorKlassID) {
            oop ikoop = ik->java_mirror();
            InstanceMirrorKlass* imk = (InstanceMirrorKlass*)ikoop->klass();
            HeapWord* obj_static_start = imk->start_of_static_fields(obj);
            HeapWord* p = obj_static_start;
            int num_statics = java_lang_Class::static_oop_field_count(obj);
            HeapWord* end = p + num_statics;
            // printf("java_lang_Class::static_oop_field_count(obj) %d\n", java_lang_Class::static_oop_field_count(obj));
            bool found_p = binary_search(iks_static_fields_checked, 0, iks_static_fields_checked_size - 1, (InstanceKlass*)obj_static_start) != -1;
            // char hex_obj[1024];
            // sprintf(hex_obj, "%p", obj);
            // if (strstr(hex_obj, "018d8"))
            if (num_statics > 0  && num_statics < 100 && !found_p) {
              for (;p < end; p++) {
                num_statics_checked++;
                // uint64_t val = (uint64_t);
                uint64_t fd_address = (uint64_t)p;
                uint64_t val = *((uint64_t*)fd_address);
                
                int idx = has_heap_event(sorted_field_set_events, fd_address, 0, sorted_field_set_events_size - 1);
                bool found = idx != -1;
                num_fields++;
                if (found) {num_found++; is_heap_event_in_heap[idx] = 1;} else 
                {if (val != 0) num_not_found++;}
              }
              
              iks_static_fields_checked[iks_static_fields_checked_size++] = (InstanceKlass*)obj_static_start;
              iks_static_fields_checked_changed = true;
            }
          }
          // AllFields field_printer(obj);
          // printf("klassID %d buf %s oop %p java_fields_count %d\n", obj->klass()->id(), buf, (void*)obj, ((InstanceKlass*)obj->klass())->java_fields_count());
          
          do {
            char buf2[1024];
            char buf3[1024];

            for(int f = 0; f < ik->java_fields_count(); f++) {
              if (AccessFlags(ik->field_access_flags(f)).is_static()) continue;
              Symbol* name = ik->field_name(f);
              Symbol* signature = ik->field_signature(f);
              
              if (signature_to_field_type(signature->as_C_string(buf2,1024)) == T_OBJECT || 
                  signature_to_field_type(signature->as_C_string(buf2,1024)) == T_ARRAY) {
                uint64_t fd_address;
                oop val;
                fd_address = ((uint64_t)(void*)obj) + ik->field_offset(f);
                val = obj->obj_field(ik->field_offset(f));
                num_fields++;
                int idx_in_field_set_events = has_heap_event(sorted_field_set_events, fd_address, 0, sorted_field_set_events_size - 1);
                bool found = idx_in_field_set_events >= 0;
                get_oop_klass_name(obj, buf3);
                uint64_t field_src = 0;

                if (found) {
                  field_src = sorted_field_set_events[idx_in_field_set_events].address.src;
                  num_found++;
                }

                if (!found && !(val == 0 || ((uint64_t)(void*)val) == 0xbaadbabebaadbabe))
                {
                  if (num_not_found < 100) { 
                    // if (strstr(buf3, "MemberName"))
                    printf("468: Not found: (%p) %s.%s:%s : 0x%lx -> %p\n", (void*)obj, buf3, name->as_C_string(buf, 1024), signature->as_C_string(buf2,1024), fd_address, (void*)val);
                  }
                  num_not_found++;
                }
                if (found && field_src != (uint64_t)(void*)val &&
                  sorted_field_set_events[idx_in_field_set_events].id != Universe::checking*Universe::max_heap_events - 1) {
                  //Ignore the last event because elem value might not have been updated to address.src

                  num_src_not_correct++;
                  // printf("0x%lx != %p\n", sorted_field_set_events[idx].address.src, (void*)val);
                  if (num_src_not_correct < 100) {
                    printf("479: (%p) %s.%s:%s : [0x%lx] 0x%lx != %p\n", (void*)obj, buf3, name->as_C_string(buf, 1024), signature->as_C_string(buf2,1024), fd_address, field_src, (void*)val);
                    if (strstr(buf3, "MemberName")) {
                      if (strstr(buf, "name")) {
                        int len;
                        char* str = java_lang_String::as_utf8_string(val, len);
                        printf("str %s\n", str);
                        // arrayOop ao = (arrayOop)val;
                        // arrayOop srcoop = (arrayOop)(void*)sorted_field_set_events[idx].address.src;
                        // printf("srclength %d vallength %d\n", srcoop->length(), ao->length());
                      }
                      
                      // InstanceKlass* arrayListKlass = (InstanceKlass*)obj->klass();
                      // for (int sf = 0; sf < arrayListKlass->java_fields_count(); sf++) {
                      //   if (AccessFlags(arrayListKlass->field_access_flags(sf)).is_static()) {
                      //     Symbol* name = arrayListKlass->field_name(sf);
                      //     name->as_C_string(buf2, 1024);
                      //     if (strstr(buf2, "EMPTY_ELEMENTDATA")) {
                      //       // fieldDescriptor(arrayListKlass, sf) fd;
                      //       printf("%p\n");
                      //     }
                      //   }
                      // }
                    }
                  }
                }
              }
              // if (f <= 1)
              //   printf("%s:%s\n", name->as_C_string(buf,1024), signature->as_C_string(buf2,1024));
            }
            
            oop ikoop = ik->java_mirror();
            InstanceMirrorKlass* imk = (InstanceMirrorKlass*)ikoop->klass();
            // printf("imk->fields %d static_fields %d %d ik->fields %d %s\n", imk->java_fields_count(), java_lang_Class::static_oop_field_count(obj), java_lang_Class::static_oop_field_count(ikoop), ik->java_fields_count(), buf);

            if (java_lang_Class::static_oop_field_count(ikoop) > 0) {
              HeapWord* static_start = imk->start_of_static_fields(ikoop);
              HeapWord* p = static_start;
              bool has_p_checked = binary_search(iks_static_fields_checked, 0, iks_static_fields_checked_size - 1, (InstanceKlass*)static_start) != -1;
              if (!has_p_checked) {
                int static_field_to_field_index[ik->java_fields_count()];
                int static_field_number = 0;
                for(int f = 0; f < ik->java_fields_count(); f++) {
                  if (AccessFlags(ik->field_access_flags(f)).is_static()) {
                    Symbol* name = ik->field_name(f);
                    Symbol* signature = ik->field_signature(f);
                    char buf2[1024];

                    if (signature_to_field_type(signature->as_C_string(buf2,1024)) == T_OBJECT || 
                        signature_to_field_type(signature->as_C_string(buf2,1024)) == T_ARRAY) {
                      static_field_to_field_index[static_field_number++] = f;
                    }
                  }
                }

                HeapWord* end = p + java_lang_Class::static_oop_field_count(ikoop);
                // printf("%p -> %p %d %s\n", p, end, java_lang_Class::static_oop_field_count(obj), ik->name()->as_C_string(buf2, 1024));
                if (static_field_number != java_lang_Class::static_oop_field_count(ikoop)) {
                  printf("not eq static_field_number %d %d\n", static_field_number, java_lang_Class::static_oop_field_count(ikoop));
                }
                
                for (int i = 0; p < end; p++, i++) {
                  int f = static_field_to_field_index[i];
                  if (!AccessFlags(ik->field_access_flags(f)).is_static()) printf("not static %d\n", i);
                  // if (AccessFlags(ik->field_access_flags(f)).is_final()) {continue;}
                  // if (AccessFlags(imk->field_access_flags(i)).is_final()) continue;

                  num_statics_checked++;
                  // uint64_t val = (uint64_t);
                  uint64_t fd_address = (uint64_t)p;
                  uint64_t val = *((uint64_t*)fd_address);
                  // if (val == 0) continue;
                  int idx = has_heap_event(sorted_field_set_events, fd_address, 0, sorted_field_set_events_size - 1);
                  bool found = idx != -1;
                  num_fields++;
                  if (found) {num_found++; is_heap_event_in_heap[idx] = 1;} else if (val != 0)
                  {
                    if (num_not_found < 100) { 
                      Symbol* name = ik->field_name(f);
                      Symbol* signature = ik->field_signature(f);
                      char buf2[1024];
                      char buf[1024];
                      char buf3[1024];
                      
                      if (signature_to_field_type(signature->as_C_string(buf2,1024)) == T_OBJECT || 
                          signature_to_field_type(signature->as_C_string(buf2,1024)) == T_ARRAY) {
                        printf("%p: %s.%s : %s 0x%lx\n", p, ik->name()->as_C_string(buf3, 1024), name->as_C_string(buf, 1024), buf2, val);
                      }
                    }
                    num_not_found++;
                  }

                  //TODO: Lazy.... Static value will probably be right.
                }

                iks_static_fields_checked[iks_static_fields_checked_size++] = (InstanceKlass*)static_start; //Set ik not imk so that same array can serve purpose for statics from obj and static from imk
                iks_static_fields_checked_changed = true;
              }
            }
            ik = ik->superklass();
          } while (ik && ik->is_klass());
          // ik->do_nonstatic_fields(&field_printer);
          // valid = valid && field_printer.valid;
          if (iks_static_fields_checked_changed)
            qsort(iks_static_fields_checked, iks_static_fields_checked_size, sizeof(InstanceKlass*), InstanceKlassPointerComparer);
          
          if (false) {
            for (JavaFieldStream fs(((InstanceKlass*)obj->klass())); !fs.done(); fs.next()) {
              char buf2[1024];
              fieldDescriptor& fd = fs.field_descriptor();
              fd.name()->as_C_string(buf2, 1024);

              if (strstr(buf2, "name")) {
                oop name_oop = obj->obj_field(fd.offset());
                if (((void*)name_oop != nullptr)) {
                  Symbol* content = java_lang_String::as_symbol_or_null(name_oop);
                  if(content) {content->as_C_string(buf2, 1024);
                  printf("content %s\n", buf2);
                  }
                  break;
                }
              }
            }
          }
          // num_found += field_printer.num_found;
          // num_not_found += field_printer.num_not_found;
          // if (!field_printer.valid) {
          //   printf("%s\n", buf);
          // }
          // foundPuppy = true;
        } else if(klass->id() == ObjArrayKlassID) {
          ObjArrayKlass* oak = (ObjArrayKlass*)klass;
          objArrayOop array = (objArrayOop)obj; // length
          int num_not_found_in_klass = 0;
          for (int i = 0; i < array->length(); i++) {
            oop elem = array->obj_at(i);
            num_fields++;
            uint64_t elem_addr = ((uint64_t)array->base()) + i * sizeof(oop);
            int idx = has_heap_event(sorted_field_set_events, elem_addr, 0, sorted_field_set_events_size - 1);
            bool found = idx != -1;
            if (found) {num_found++; is_heap_event_in_heap[idx] = 1;} else if (!(elem == 0 || ((uint64_t)(void*)elem) == 0xbaadbabebaadbabe)) {
              
              num_not_found++;
              if (num_not_found < 100) {
                char buf2[1024];
                printf("length %d klass %s %p\n", array->length(), oak->name()->as_C_string(buf2,1024), (void*)array);
                printf("elem_addr 0x%lx i %d elem %p\n", elem_addr, i, (void*)elem); num_not_found_in_klass++;
              }
              // if (strstr(get_oop_klass_name(elem, buf2), "java/lang/String")) {
              //   int len;
              //   char* str = java_lang_String::as_utf8_string(elem, len);
              //   printf("str is '%s'\n", str);
              // }
            }
            if (found && sorted_field_set_events[idx].address.src != (uint64_t)(void*)elem &&
                sorted_field_set_events[idx].id != Universe::checking*Universe::max_heap_events - 1) {
                  //Ignore the last event because elem value might not have been updated to address.src
              char buf2[1024];
              num_src_not_correct++;
              if (num_src_not_correct < 100) {
                printf("(%p,%d) %s[%d] : 0x%lx != %p\n", (void*)obj, array->length(), oak->name()->as_C_string(buf2,1024), i, sorted_field_set_events[idx].address.src, (void*)elem);
                if (elem != 0)
                  printf("elem klass %s\n", get_oop_klass_name(elem, buf2));
                // printf("sorted_field_set_events[idx].id %ld\n", sorted_field_set_events[idx].id);
              }
            }
          }
        } else if (klass->id() == TypeArrayKlassID) {
          TypeArrayKlass* tak = (TypeArrayKlass*)klass;
          typeArrayOop array = (typeArrayOop)obj; // length
          if (is_reference_type(((TypeArrayKlass*)klass)->element_type()))
            printf("t %d\n", tak->element_type());
          // for (int i = 0; i < array->length(); i++) {
          //   oop elem = array->obj_at(i);
          //   if (elem == 0 || (uint64_t)elem == 0xbaadbabebaadbabe) continue;
          //   uint64_t elem_addr = ((uint64_t)array->base()) + i * sizeof(oop);
          //   int idx = has_heap_event((uint64_t)elem_addr);
          //   bool found = idx != -1;
          //   if (found) {num_found++; is_heap_event_in_heap[idx] = 1;} else {
              
          //     num_not_found++;
          //     // printf("length %d klass %s %p\n", array->length(), oak->name()->as_C_string(buf2,1024), (void*)array);
          //     // printf("elem_addr 0x%lx i %d elem %p\n", elem_addr, i, elem); num_not_found_in_klass++;
          //     // if (strstr(get_oop_klass_name(elem, buf2), "java/lang/String")) {
          //     //   int len;
          //     //   char* str = java_lang_String::as_utf8_string(elem, len);
          //     //   printf("str is '%s'\n", str);
          //     // }
          //   }
          //   if (found && sorted_field_set_events[idx].address.src != (uint64_t)(void*)elem &&
          //       sorted_field_set_events[idx].id != Universe::checking*Universe::max_heap_events - 1) {
          //         //Ignore the last event because elem value might not have been updated to address.src
          //     char buf2[1024];
          //     num_src_not_correct++;
          //     printf("(%p) %s[%d] : 0x%lx != %p\n", (void*)obj, tak->name()->as_C_string(buf2,1024), i, sorted_field_set_events[idx].address.src, (void*)elem);
          //     printf("sorted_field_set_events[idx].id %ld\n", sorted_field_set_events[idx].id);
          //   }
          //   valid = valid && found;
          // }
        } else {
          // printf("oop %p klass ID %d\n", (void*)obj, klass->id());
        }
        
        // objects.push_back(obj);

        valid = valid && num_not_found == 0;
      }
    }
};

int HeapEventComparer(Universe::HeapEvent* a, Universe::HeapEvent* b) {
    if (a->address.dst < b->address.dst) return -1;
    if (a->address.dst == b->address.dst) return 0;
    return 1;
}

int HeapEventComparerV(const void* a, const void* b) {
    return HeapEventComparer((Universe::HeapEvent*)a, (Universe::HeapEvent*)b);
}

int HeapEventComparerWithID(const void* a, const void* b) {
  Universe::HeapEvent* event1 = (Universe::HeapEvent*)a;
  Universe::HeapEvent* event2 = (Universe::HeapEvent*)b;

  if (event1->id < event2->id) return -1;
  if (event1->id == event2->id) return 0;
  return 1;
}

pthread_spinlock_t spin_lock_heap_event;

__attribute__((constructor))
void init_lock () {
  static char mmap_heap_buf[sizeof(MmapHeap)];
  mmap_heap = new (mmap_heap_buf) MmapHeap();

  if ( pthread_spin_init ( &spin_lock_heap_event, 0 ) != 0 ) {
    exit ( 1 );
  }
}

void Universe::transfer_events_to_gpu() {
  printf("Transferring Events to GPU *Universe::heap_event_counter_ptr %ld\n", *Universe::heap_event_counter_ptr);
  sem_post(&cuda_semaphore);
  *Universe::heap_event_counter_ptr = 0;
}

void Universe::verify_heap_graph_for_copy_array() {
  if ((*Universe::heap_event_counter_ptr) + 3 > Universe::max_heap_events) {
    Universe::verify_heap_graph();
  }
}

void Universe::lock_mutex_heap_event() {
  pthread_mutex_lock(&Universe::mutex_heap_event);
}

void Universe::unlock_mutex_heap_event() {
  pthread_mutex_unlock(&Universe::mutex_heap_event);
}

void Universe::print_heap_event_counter() {
  *Universe::heap_event_counter_ptr = 0; 
}

void Universe::verify_heap_graph() {
  if (*Universe::heap_event_counter_ptr < Universe::max_heap_events)
    return;

  size_t heap_events_size = *Universe::heap_event_counter_ptr;
  *Universe::heap_event_counter_ptr = 0;
  if (Universe::enable_transfer_events)
    Universe::transfer_events_to_gpu();

  if (!Universe::enable_heap_graph_verify)
    return;

  printf("heap_events_size %ld\n", heap_events_size);

  static HeapEvent* flattened_heap_events;
  size_t flattened_heap_events_size = 0;
  static int num_events_created = 0;
  static HeapEvent* copy_object_events;
  size_t copy_object_events_size = 0;
  static HeapEvent* copy_array_events;
  size_t copy_array_events_size = 0;
  static HeapEvent* heap_events_sorted_with_id;
  if (sorted_field_set_events == NULL) {
    sorted_field_set_events = (Universe::HeapEvent*)mmap ( NULL, SORTED_FIELD_SET_EVENTS_MAX_SIZE*sizeof(HeapEvent), PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, 0, 0 );
    is_heap_event_in_heap = (uint8_t*)mmap ( NULL, SORTED_FIELD_SET_EVENTS_MAX_SIZE*sizeof(uint8_t), PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, 0, 0 );
    iks_static_fields_checked = (InstanceKlass**)mmap ( NULL, SORTED_FIELD_SET_EVENTS_MAX_SIZE*sizeof(InstanceKlass*), PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, 0, 0 );
    sorted_new_object_events = (Universe::HeapEvent*)mmap (NULL, SORTED_NEW_OBJECT_EVENTS_MAX_SIZE*sizeof(HeapEvent), PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, 0, 0 );
    flattened_heap_events = (Universe::HeapEvent*)mmap (NULL, SORTED_NEW_OBJECT_EVENTS_MAX_SIZE*sizeof(HeapEvent), PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, 0, 0 );
    copy_object_events = (Universe::HeapEvent*)mmap (NULL, Universe::max_heap_events*sizeof(HeapEvent), PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, 0, 0 );
    copy_array_events = (Universe::HeapEvent*)mmap (NULL, Universe::max_heap_events*sizeof(HeapEvent), PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, 0, 0 );
    heap_events_sorted_with_id = (Universe::HeapEvent*)mmap (NULL, Universe::max_heap_events*sizeof(HeapEvent), PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, 0, 0 );
  }
  
  // if (sorted_field_set_events == NULL) {abort();}
  checking++;
  // printf("checking %d %ld tid %ld\n", checking++, Universe::heap_event_counter, gettid()); 
  iks_static_fields_checked_size = 0;
  HeapEvent* heap_events_start = &Universe::heap_events[1];
  for (auto i = 0U; i < heap_events_size; i++) {
    heap_events_start[i].id = num_events_created++;
    heap_events_sorted_with_id[i] = heap_events_start[i];
  }

  qsort(heap_events_start, heap_events_size, sizeof(HeapEvent), HeapEventComparerV);

  for (auto i = 0U; i < heap_events_size; i++) {
    if (heap_events_start[i].heap_event_type == Universe::CopyObject || heap_events_start[i].heap_event_type == Universe::CopyArray) {
      copy_object_events[copy_object_events_size++] = heap_events_start[i];
    }

    if (heap_events_start[i].heap_event_type == Universe::CopyArray || heap_events_start[i].heap_event_type == Universe::CopyArrayLength || heap_events_start[i].heap_event_type == Universe::CopyArrayOffsets) {
      copy_array_events[copy_array_events_size++] = heap_events_start[i];
    }
  }

  qsort(copy_array_events, copy_array_events_size, sizeof(HeapEvent), HeapEventComparerWithID);
  

  for (auto i = 0U; i < heap_events_size; i++) {
    HeapEvent event = heap_events_sorted_with_id[i];
    if (event.heap_event_type == Universe::CopyObject) {
      oop obj_src = oop((oopDesc*)(void*)event.address.src);
      if (obj_src->klass()->is_instance_klass()) {
        InstanceKlass* ik = (InstanceKlass*)obj_src->klass();
        do {
          char buf2[1024];
          char buf3[1024];

          for(int f = 0; f < ik->java_fields_count(); f++) {
            if (AccessFlags(ik->field_access_flags(f)).is_static()) continue;
            Symbol* name = ik->field_name(f);
            Symbol* signature = ik->field_signature(f);
            
            if (signature_to_field_type(signature->as_C_string(buf2,1024)) == T_OBJECT || signature_to_field_type(signature->as_C_string(buf2,1024)) == T_ARRAY) {
              uint64_t dst_obj_field_offset = event.address.dst + ik->field_offset(f);
              uint64_t src_obj_field_offset = event.address.src + ik->field_offset(f);

              int idx = -1;
              HeapEvent final_event1 = {HeapEventType::None,0,0,0};
              uint64_t final_event1_src_id = 0;
              HeapEvent final_event2 = {HeapEventType::None,0,0,0};
              uint64_t final_event2_src_id = 0;
              
              if (idx == -1) {
                idx = has_heap_event(heap_events_start, src_obj_field_offset, 0, heap_events_size - 1);
                if (idx != -1) {  
                  HeapEvent src_field_set_event = heap_events_start[idx];
                  //Go to first event with same dst.
                  while (idx > 0 && src_obj_field_offset == heap_events_start[idx-1].address.dst) {
                    // printf("950: event {id %ld dst 0x%lx src 0x%lx} heap_events_start[idx-1] {id %ld dst 0x%lx src 0x%lx} i %d\n", event.id, event.address.dst, src_field_set_event.address.src, heap_events_start[idx-1].id, heap_events_start[idx-1].address.dst, heap_events_start[idx-1].address.src, i);
                    idx--;
                  }
                  
                  src_field_set_event = heap_events_start[idx];
                
                  //Find the event with id less than copy_object event id
                  for (; idx < (int)heap_events_size && src_obj_field_offset == heap_events_start[idx].address.dst; idx++) {
                    if (heap_events_start[idx].id < event.id && src_field_set_event.id < heap_events_start[idx].id)
                      src_field_set_event = heap_events_start[idx];
                  }
                  if (src_field_set_event.id < event.id) {
                    // if (offsets.address.src == 0 && offsets.address.dst == 0 && length_event.address.src == 10)
                    // if (can_print) printf("850: event.id %ld dst 0x%lx src 0x%lx} src_field_set_event {id %ld dst 0x%lx src 0x%lx} i %d\n", event.id, event.address.dst, event.address.src, src_field_set_event.id, src_field_set_event.address.dst, src_field_set_event.address.src, i);
                    final_event1 = Universe::HeapEvent{Universe::FieldSet, src_field_set_event.address.src, dst_obj_field_offset, event.id};
                    final_event1_src_id = src_field_set_event.id;
                  } else {
                    idx = -1;
                  }
                }
              }

              if (true || idx == -1) {
                int ii = has_heap_event(copy_object_events, (uint64_t)(void*)obj_src, 0, copy_object_events_size - 1);
                if (ii != -1) {
                  //Go to first event with same dst.
                  while (ii > 0 && copy_object_events[ii-1].address.dst == (uint64_t)(void*)obj_src) {
                    ii--;
                  }
                  HeapEvent src_copy_event = copy_object_events[ii];
                  bool found = false;
                  for (; copy_object_events[ii].address.dst == (uint64_t)(void*)obj_src; ii++) {
                    if (copy_object_events[ii].id < event.id) {
                      src_copy_event = copy_object_events[ii];
                      found = true;
                    }
                  }
                  if (found) {
                    idx = has_heap_event_with_id(flattened_heap_events, src_copy_event.id, 0, flattened_heap_events_size - 1);
                    // if (can_print) printf("1008: event {id %ld dst 0x%lx src 0x%lx} dst_elem_addr 0x%lx src_copy_event {id %ld src 0x%lx dst 0x%lx} obj_src %p idx %d i %d\n", event.id, event.address.dst, event.address.src, dst_elem_addr, src_copy_event.id, src_copy_event.address.src, src_copy_event.address.dst, (void*)obj_src, idx, i);
                    if (idx != -1) {
                      while (idx > 0 && src_copy_event.id == flattened_heap_events[idx-1].id) {
                        idx--;
                      }
                      HeapEvent src_field_set_event = flattened_heap_events[idx];
                      for (; idx < (int)flattened_heap_events_size && src_copy_event.id == flattened_heap_events[idx].id; idx++) {
                        if (flattened_heap_events[idx].address.dst == src_obj_field_offset) {
                          src_field_set_event = flattened_heap_events[idx];
                        }
                      }
                      // if (can_print) printf("1022: event {id %ld dst 0x%lx src 0x%lx} dst_elem_addr 0x%lx src_field_set_event {id %ld src 0x%lx dst 0x%lx} src_elem_addr 0x%lx i %d\n", event.id, event.address.dst, event.address.src, dst_elem_addr, src_field_set_event.id, src_field_set_event.address.src, src_field_set_event.address.dst, src_elem_addr, i);
                      if (src_field_set_event.address.dst == src_obj_field_offset) {
                        // if (can_print) printf("1024: event {id %ld dst 0x%lx src 0x%lx} src_field_set_event {id %ld dst 0x%lx src 0x%lx} i %d\n", event.id, event.address.dst, event.address.src, src_field_set_event.id, src_field_set_event.address.dst, src_field_set_event.address.src, i);
                        final_event2 = Universe::HeapEvent{Universe::FieldSet, src_field_set_event.address.src, dst_obj_field_offset, event.id};
                        final_event2_src_id = src_field_set_event.id;
                      } else {
                        idx = -1;
                      }
                    }
                  } else {
                    idx = -1;
                  }
                }
              }
              
              if (final_event2.heap_event_type != HeapEventType::None || final_event1.heap_event_type != HeapEventType::None) {
                if (final_event2_src_id > final_event1_src_id)
                  flattened_heap_events[flattened_heap_events_size++] = final_event2;
                else if (final_event1.heap_event_type != HeapEventType::None)
                  flattened_heap_events[flattened_heap_events_size++] = final_event1;
              }

              if (final_event2.heap_event_type == HeapEventType::None && final_event1.heap_event_type == HeapEventType::None) {
                idx = has_heap_event(sorted_field_set_events, src_obj_field_offset, 0, sorted_field_set_events_size - 1);
                if (idx != -1) {
                  HeapEvent src_field_set_event = sorted_field_set_events[idx];
                  // if (offsets.address.src == 0 && offsets.address.dst == 0 && length_event.address.src == 10)
                  // if (can_print) printf("813: dst 0x%lx src 0x%lx dst_obj_field_offset 0x%lx i %d\n", event.address.dst, src_field_set_event.address.src, dst_elem_addr, i);
                  flattened_heap_events[flattened_heap_events_size++] = Universe::HeapEvent{Universe::FieldSet, src_field_set_event.address.src, dst_obj_field_offset, event.id};
                }
              }
            }
          }
          ik = ik->superklass();
        } while(ik && ik->is_klass());
      }
    } else if (event.heap_event_type == Universe::CopyArray) {
      objArrayOop obj_src = objArrayOop((objArrayOopDesc*)event.address.src);
      objArrayOop obj_dst = objArrayOop((objArrayOopDesc*)event.address.dst);
      if (!obj_src->klass()->is_objArray_klass()) continue;
      int idx = has_heap_event_with_id(copy_array_events, event.id, 0, copy_array_events_size - 1);
      HeapEvent offsets = copy_array_events[idx+1];
      HeapEvent length_event = copy_array_events[idx+2];
      oop first_src_elem = obj_src->length() > 0? obj_src->obj_at(0) : NULL;
      char buf[1024];

      for (uint i = 0; i < length_event.address.src; i++) {
        bool can_print = true;// obj_dst->length() >= 3000 && obj_dst->length() <= 4000 && i < 100 && Universe::checking > 5 && first_src_elem != NULL && strstr(get_oop_klass_name(first_src_elem, buf), "PyInteger") != NULL;
        // if (can_print)
        //   printf("i %d offsets.type %ld offset_src %ld offset_dst %ld length_event.address.src %ld obj_src %p obj_dst %p\n", i, offsets.heap_event_type, offsets.address.src, offsets.address.dst, length_event.address.src, (void*)obj_src, (void*)obj_dst);
        int src_array_index = offsets.address.src + i;
        int dst_array_index = offsets.address.dst + i;
        uint64_t src_elem_addr = ((uint64_t)obj_src->base()) + src_array_index * sizeof(oop);
        uint64_t dst_elem_addr = ((uint64_t)obj_dst->base()) + dst_array_index * sizeof(oop);
        // if (can_print)
        //   printf("i %d dst_elem_addr 0x%lx src_elem_addr 0x%lx\n", i, dst_elem_addr, src_elem_addr);
        int idx;
        bool field_set_later = false;
        // idx = has_heap_event(heap_events_start, dst_elem_addr, 0, heap_events_size - 1);
        // if (idx != -1) {
        //   HeapEvent dst_field_set_event = heap_events_start[idx];
        //   //Go to first event with same dst.
        //   while (idx >= 0 && dst_field_set_event.address.dst == heap_events_start[idx-1].address.dst) {
        //     idx--;
        //   }
        //   //Find the event with id less than copy_object event id
        //   for (; idx < Universe::max_heap_events && dst_field_set_event.address.dst == heap_events_start[idx].address.dst; idx++) {
        //     if (heap_events_start[idx].id > event.id) {
        //       field_set_later = true;
        //       break;
        //     }
        //   }

        //   if (field_set_later) {
        //     // if (offsets.address.src == 0 && offsets.address.dst == 0 && length_event.address.src == 10)
        //     printf("field_set_later: heap_events_start[idx].id %ld dst_obj 0x%lx dst_field 0x%lx src 0x%lx idx %d\n", heap_events_start[idx].id, event.address.dst, dst_field_set_event.address.dst, dst_field_set_event.address.src, i);
        //   }
        // }

        int round = 0;
        // do 
        {
          idx = -1;
          HeapEvent final_event1 = {HeapEventType::None,0,0,0};
          uint64_t final_event1_src_id = 0;
          HeapEvent final_event2 = {HeapEventType::None,0,0,0};
          uint64_t final_event2_src_id = 0;
          
          if (idx == -1) {
            idx = has_heap_event(heap_events_start, src_elem_addr, 0, heap_events_size - 1);
            if (idx != -1) {
              HeapEvent src_field_set_event = heap_events_start[idx];
              //Go to first event with same dst.
              while (idx > 0 && src_elem_addr == heap_events_start[idx-1].address.dst) {
                // printf("950: event {id %ld dst 0x%lx src 0x%lx} heap_events_start[idx-1] {id %ld dst 0x%lx src 0x%lx} i %d\n", event.id, event.address.dst, src_field_set_event.address.src, heap_events_start[idx-1].id, heap_events_start[idx-1].address.dst, heap_events_start[idx-1].address.src, i);
                idx--;
              }
              
              src_field_set_event = heap_events_start[idx];
            
              //Find the event with id less than copy_object event id
              for (; idx < (int)heap_events_size && src_elem_addr == heap_events_start[idx].address.dst; idx++) {
                if (heap_events_start[idx].id < event.id && src_field_set_event.id < heap_events_start[idx].id)
                  src_field_set_event = heap_events_start[idx];
              }
              if (src_field_set_event.id < event.id) {
                // if (offsets.address.src == 0 && offsets.address.dst == 0 && length_event.address.src == 10)
                // if (can_print) printf("850: event.id %ld dst 0x%lx src 0x%lx} src_field_set_event {id %ld dst 0x%lx src 0x%lx} i %d\n", event.id, event.address.dst, event.address.src, src_field_set_event.id, src_field_set_event.address.dst, src_field_set_event.address.src, i);
                final_event1 = Universe::HeapEvent{Universe::FieldSet, src_field_set_event.address.src, dst_elem_addr, event.id};
                final_event1_src_id = src_field_set_event.id;
              } else {
                idx = -1;
              }
            }
          }

          if (true || idx == -1) {
            int ii = has_heap_event(copy_object_events, (uint64_t)(void*)obj_src, 0, copy_object_events_size - 1);
            if (ii != -1) {
              //Go to first event with same dst.
              while (ii > 0 && copy_object_events[ii-1].address.dst == (uint64_t)(void*)obj_src) {
                ii--;
              }
              HeapEvent src_copy_event = copy_object_events[ii];
              bool found = false;
              for (; copy_object_events[ii].address.dst == (uint64_t)(void*)obj_src; ii++) {
                int i3 = has_heap_event_with_id(copy_array_events, copy_object_events[ii].id, 0, copy_array_events_size - 1);
                uint64_t start = ((uint64_t)obj_src->base()) + copy_array_events[i3+1].address.dst * sizeof(oop);
                uint64_t end = start + copy_array_events[i3+2].address.src * sizeof(oop);
                // printf("1003: copy_object_events[%d].id {id %ld src 0x%lx dst 0x%lx start 0x%lx end 0x%lx} src_elem_addr 0x%lx\n", ii, copy_object_events[ii].id, 
                // copy_object_events[ii].address.src, copy_object_events[ii].address.dst, start, end, src_elem_addr);
                if (copy_object_events[ii].id < event.id && src_copy_event.id <= copy_object_events[ii].id && start <= src_elem_addr && src_elem_addr < end) {
                  src_copy_event = copy_object_events[ii];
                  found = true;
                }
              }
              if (found) {
                idx = has_heap_event_with_id(flattened_heap_events, src_copy_event.id, 0, flattened_heap_events_size - 1);
                // if (can_print) printf("1008: event {id %ld dst 0x%lx src 0x%lx} dst_elem_addr 0x%lx src_copy_event {id %ld src 0x%lx dst 0x%lx} obj_src %p idx %d i %d\n", event.id, event.address.dst, event.address.src, dst_elem_addr, src_copy_event.id, src_copy_event.address.src, src_copy_event.address.dst, (void*)obj_src, idx, i);
                if (idx != -1) {
                  while (idx > 0 && src_copy_event.id == flattened_heap_events[idx-1].id) {
                    idx--;
                  }
                  HeapEvent src_field_set_event = flattened_heap_events[idx];
                  for (; idx < (int)flattened_heap_events_size && src_copy_event.id == flattened_heap_events[idx].id; idx++) {
                    if (flattened_heap_events[idx].address.dst == src_elem_addr) {
                      src_field_set_event = flattened_heap_events[idx];
                    }
                  }
                  // if (can_print) printf("1022: event {id %ld dst 0x%lx src 0x%lx} dst_elem_addr 0x%lx src_field_set_event {id %ld src 0x%lx dst 0x%lx} src_elem_addr 0x%lx i %d\n", event.id, event.address.dst, event.address.src, dst_elem_addr, src_field_set_event.id, src_field_set_event.address.src, src_field_set_event.address.dst, src_elem_addr, i);
                  if (src_field_set_event.address.dst == src_elem_addr) {
                    // if (can_print) printf("1024: event {id %ld dst 0x%lx src 0x%lx} src_field_set_event {id %ld dst 0x%lx src 0x%lx} i %d\n", event.id, event.address.dst, event.address.src, src_field_set_event.id, src_field_set_event.address.dst, src_field_set_event.address.src, i);
                    final_event2 = Universe::HeapEvent{Universe::FieldSet, src_field_set_event.address.src, dst_elem_addr, event.id};
                    final_event2_src_id = src_field_set_event.id;
                  } else {
                    idx = -1;
                  }
                }
              } else {
                idx = -1;
              }
            }
          }
          
          if (final_event2.heap_event_type != HeapEventType::None || final_event1.heap_event_type != HeapEventType::None) {
            if (final_event2_src_id > final_event1_src_id)
              flattened_heap_events[flattened_heap_events_size++] = final_event2;
            else if (final_event1.heap_event_type != HeapEventType::None)
              flattened_heap_events[flattened_heap_events_size++] = final_event1;
          }

          if (final_event2.heap_event_type == HeapEventType::None && final_event1.heap_event_type == HeapEventType::None) {
            idx = has_heap_event(sorted_field_set_events, src_elem_addr, 0, sorted_field_set_events_size - 1);
            if (idx != -1) {
              HeapEvent src_field_set_event = sorted_field_set_events[idx];
              // if (offsets.address.src == 0 && offsets.address.dst == 0 && length_event.address.src == 10)
              // if (can_print) printf("813: dst 0x%lx src 0x%lx dst_obj_field_offset 0x%lx i %d\n", event.address.dst, src_field_set_event.address.src, dst_elem_addr, i);
              flattened_heap_events[flattened_heap_events_size++] = Universe::HeapEvent{Universe::FieldSet, src_field_set_event.address.src, dst_elem_addr, event.id};
            }
          }
          // b[b1:b2] = a[a1:a2]
          //   c[c1:c2] = b[b11:b12] //b11 > b1 and b12 < b2
          //   c1 -> b11 & c2 -> b12 (b12-b11 = a2 - a1)
          //   b11 > b1 & b12 < b2 
          //   b1 -> a1 & b2 -> a2 (a2-a1 = b2-b1)
          //   c1 -?-> a1?
          //   c[c1:c2] = a[a1+b11-b1:a2+b12-b2]

          // if (idx == -1) {
          //   int ii = has_heap_event(copy_object_events, (uint64_t)curr_src, 0, copy_object_events_size - 1);
          //   if (ii == -1) break;
          //   curr_src = objArrayOop((void*)copy_object_events[ii].address.src);
          //   // int i3 = has_heap_event_with_id(copy_array_events, (uint64_t)curr_src, 0, copy_array_events_size - 1);
          //   // if (i3 == -1) break;
          //   // src_array_index = copy_array_events[i3+1].address.src + src_array_index - copy_array_events[i3+1].address.dst;
          //   // if (offsets.address.src == 0 && offsets.address.dst == 0 && length_event.address.src == 10)
          //   printf("955: curr_src %p i %d ii %d\n", curr_src, i, ii);
          // }
        } //while (idx == -1 && round < 100);
      }
    } else {
      flattened_heap_events[flattened_heap_events_size++] = event;
    }
  }

  qsort(flattened_heap_events, flattened_heap_events_size, sizeof(HeapEvent), HeapEventComparerV);
  printf("flattened_heap_events_size %ld\n", flattened_heap_events_size);
  heap_events_start = flattened_heap_events;
  int orig_len = sorted_field_set_events_size - 1;
  int new_objects_orig_len = sorted_new_object_events_size - 1;

  int max_prints=0;
  int zero_event_types = 0;
  //Update heap hash table
  for (uint64_t event_iter = 0; event_iter < flattened_heap_events_size; event_iter++) {
    HeapEvent latest_event = heap_events_start[event_iter];
    while (event_iter < flattened_heap_events_size - 1 && heap_events_start[event_iter].address.dst == heap_events_start[event_iter+1].address.dst) {
      if (latest_event.id < heap_events_start[event_iter].id) {
        latest_event = heap_events_start[event_iter];
      }
      event_iter++;
    }
    if (latest_event.id < heap_events_start[event_iter].id && heap_events_start[event_iter].address.dst == latest_event.address.dst) {
      latest_event = heap_events_start[event_iter];
    }
    if (latest_event.heap_event_type == Universe::NewObject) {
      int idx = has_heap_event(sorted_new_object_events, latest_event.address.dst, 0, new_objects_orig_len);
      if (idx == -1) {
        // if((max_prints++) < 100) printf("Inserting: dst 0x%lx src 0x%lx\n", event.address.dst, event.address.src);
        sorted_new_object_events[sorted_new_object_events_size++] = latest_event;
      } else {
        if (latest_event.id > sorted_new_object_events[idx].id)
          sorted_new_object_events[idx] = latest_event;
      }

      oopDesc* obj = (oopDesc*)latest_event.address.dst;
      if (ObjectNode::oop_to_obj_node.find(obj) != ObjectNode::oop_to_obj_node.end()) {
        printf("Found %p in oop_to_obj_node\n", obj);
      }

      ObjectNode::oop_to_obj_node.emplace(obj, ObjectNode(obj, latest_event.address.src, latest_event.id));
      
    } else if (latest_event.heap_event_type == Universe::FieldSet || latest_event.heap_event_type == Universe::OopStoreAt) {
      int idx = has_heap_event(sorted_field_set_events, latest_event.address.dst, 0, orig_len);
      if (idx == -1) {
        // if((max_prints++) < 100) printf("Inserting: dst 0x%lx src 0x%lx\n", event.address.dst, event.address.src);
        sorted_field_set_events[sorted_field_set_events_size++] = latest_event;
      } else {
        if (latest_event.id > sorted_field_set_events[idx].id)
          sorted_field_set_events[idx] = latest_event;
      }
    } else if (false && latest_event.heap_event_type != CopyArrayLength && latest_event.heap_event_type != CopyArrayOffsets) {
      printf("invalid event type %ld at event_iter %ld\n", latest_event.heap_event_type, event_iter);
      // ShouldNotReachHere();
    }

    if (latest_event.heap_event_type == OopStoreAt)
      printf("latest_event.heap_event_type == OopStoreAt %ld\n", latest_event.heap_event_type);
  }

  printf("total events %ld zero_event_types %d\n", sorted_field_set_events_size, zero_event_types);
  
  // sorted_field_set_events.sort(HeapEventComparer);
  qsort(sorted_field_set_events, sorted_field_set_events_size, sizeof(HeapEvent), HeapEventComparerV);
  qsort(sorted_new_object_events, sorted_new_object_events_size, sizeof(HeapEvent), HeapEventComparerV);

  int dst_src_null = 0;
  for (uint32_t i = 0; i < sorted_field_set_events_size; i++) {
    HeapEvent event = sorted_field_set_events[i];
    if (event.address.src == 0x0 && event.address.dst == 0x0) {
      dst_src_null++;
    }
  }

  printf("dst_src_null %d\n", dst_src_null);
  // max_prints = 0;
  // for (int i = 0; i < Universe::max_heap_events && max_prints < 100; i++) {
  //     if((max_prints++) < 100) printf("After sort: dst 0x%lx src 0x%lx\n", event.address.dst, event.address.src);
  // }
  // sorted_new_heap_events.clear();
  AllObjects all_objects;
  Universe::heap()->object_iterate(&all_objects);
  
  printf("valid? %d num_heap_events %ld {NewObject: %ld, FieldSet: %ld} num_found %d {NewObject: %d, FieldSet:%d} num_not_found %d num_src_not_correct %d\n", (int)all_objects.valid, sorted_field_set_events_size + sorted_new_object_events_size, 
  sorted_new_object_events_size, sorted_field_set_events_size, all_objects.num_found, all_objects.num_oops, all_objects.num_fields, all_objects.num_not_found, all_objects.num_src_not_correct);

  // if (!all_objects.valid) abort();
  
  // if (all_objects.num_src_not_correct > 0) abort();
  // max_prints = 0;
  // if (checking > 5) abort();
  return;

  HeapWord* volatile* top_addrs = Universe::heap()->top_addr();
  printf("top_addrs %p %p\n", top_addrs[0], top_addrs[1]);
  printf("first_klass_addr 0x%lx last_klass_addr 0x%lx\n", first_klass_addr, last_klass_addr);
  printf("first_oop_addr 0x%lx last_oop_addr 0x%lx\n", first_oop_addr, last_oop_addr);
  // abort();
  
  int events_with_src_null = 0;
  int events_with_src_not_null = 0;
  int heap_event_2 = 0;
  int heap_event_1 = 0;
  int num_minus_2 = 0;

  for (uint32_t i = 0; i < sorted_field_set_events_size; i++) {
    if (is_heap_event_in_heap[i] == 0) {
      HeapEvent event = sorted_field_set_events[i];
      if (event.heap_event_type == Universe::NewObject || event.heap_event_type == Universe::FieldSet) continue;
      if (event.address.src == 0x0) {
        events_with_src_null++;
      } else {
        events_with_src_not_null++;
        if (event.heap_event_type == 2) {
          heap_event_2++;
          continue;
        }
        else {
          heap_event_1++;
        }

        if (event.address.src == 0xfffffffffffffffe) {
          num_minus_2++;
        }
        continue;
      }
      if (max_prints < 20) {
        // oop obj = oop((oopDesc*)(void*)event.address.src);
        char buf2[1024]={0};
        // get_oop_klass_name(obj, buf2);
        int obj_index = -1;
        has_heap_event(sorted_new_object_events, event.address.dst, 0, sorted_new_object_events_size - 1, &obj_index);
        printf("at %d: heap_event_type %ld dst 0x%lx src 0x%lx obj_index %d\n", i, (uint64_t)event.heap_event_type, event.address.dst, event.address.src, obj_index);
        if (obj_index != -1) {
          char buf[1024];
          oop obj = oop((oopDesc*)sorted_new_object_events[obj_index].address.dst);
          // if (obj->klass()->id() == ObjArrayKlassID) {
          get_oop_klass_name(obj, buf);
          HeapWord* static_start1 = ((InstanceMirrorKlass*)obj->klass()->java_mirror()->klass())->start_of_static_fields(obj);
          int count1 = java_lang_Class::static_oop_field_count(obj);
          HeapWord* static_start2 = ((InstanceMirrorKlass*)obj->klass()->java_mirror()->klass())->start_of_static_fields(obj->klass()->java_mirror());
          int count2 = java_lang_Class::static_oop_field_count(obj->klass()->java_mirror());
          printf("[%d] %p, size %ld: %s.xxx = %s obj->klass()->id() %d, static_start1 %p count1 %d static_start2 %p count2 %d\n", obj_index, (void*)obj, obj->size(), buf, buf2, obj->klass()->id(), static_start1, count1, static_start2, count2);
          // }
        }
        // if (event.address.dst != 0x0) {
        //   oop obj = (oop)(void*)(event.address.dst - 0xc0);
        //   if (oopDesc::is_oop(obj)) {
        //     char buf[1024];
        //     printf("dst: %s\n", get_oop_klass_name(obj, buf));
        //   }
        // }
        max_prints++;
      }
    }
  }

  printf("events_with_src_null %d events_with_src_not_null %d heap_event_2 %d heap_event_1 %d num_minus_2 %d\n", events_with_src_null, events_with_src_not_null, heap_event_2, heap_event_1, num_minus_2);
  //   //  else {
  //   //   HeapEvent event = sorted_field_set_events[i];
  //   //   printf("1 at %d: dst 0x%lx src 0x%lx\n", i, event.address.dst, event.address.src);
  //   // }
  // }
  // abort();
  // if (max_prints >= 100)  abort();
}

// Known objects
Klass* Universe::_typeArrayKlassObjs[T_LONG+1]        = { NULL /*, NULL...*/ };
Klass* Universe::_objectArrayKlassObj                 = NULL;
OopHandle Universe::_mirrors[T_VOID+1];

OopHandle Universe::_main_thread_group;
OopHandle Universe::_system_thread_group;
OopHandle Universe::_the_empty_class_array;
OopHandle Universe::_the_null_string;
OopHandle Universe::_the_min_jint_string;

OopHandle Universe::_the_null_sentinel;

// _out_of_memory_errors is an objArray
enum OutOfMemoryInstance { _oom_java_heap,
                           _oom_c_heap,
                           _oom_metaspace,
                           _oom_class_metaspace,
                           _oom_array_size,
                           _oom_gc_overhead_limit,
                           _oom_realloc_objects,
                           _oom_retry,
                           _oom_count };

OopHandle Universe::_out_of_memory_errors;
OopHandle Universe::_delayed_stack_overflow_error_message;
OopHandle Universe::_preallocated_out_of_memory_error_array;
volatile jint Universe::_preallocated_out_of_memory_error_avail_count = 0;

OopHandle Universe::_null_ptr_exception_instance;
OopHandle Universe::_arithmetic_exception_instance;
OopHandle Universe::_virtual_machine_error_instance;

OopHandle Universe::_reference_pending_list;

Array<Klass*>* Universe::_the_array_interfaces_array = NULL;
LatestMethodCache* Universe::_finalizer_register_cache = NULL;
LatestMethodCache* Universe::_loader_addClass_cache    = NULL;
LatestMethodCache* Universe::_throw_illegal_access_error_cache = NULL;
LatestMethodCache* Universe::_throw_no_such_method_error_cache = NULL;
LatestMethodCache* Universe::_do_stack_walk_cache     = NULL;

long Universe::verify_flags                           = Universe::Verify_All;

Array<int>* Universe::_the_empty_int_array            = NULL;
Array<u2>* Universe::_the_empty_short_array           = NULL;
Array<Klass*>* Universe::_the_empty_klass_array     = NULL;
Array<InstanceKlass*>* Universe::_the_empty_instance_klass_array  = NULL;
Array<Method*>* Universe::_the_empty_method_array   = NULL;

// These variables are guarded by FullGCALot_lock.
debug_only(OopHandle Universe::_fullgc_alot_dummy_array;)
debug_only(int Universe::_fullgc_alot_dummy_next = 0;)

// Heap
int             Universe::_verify_count = 0;

// Oop verification (see MacroAssembler::verify_oop)
uintptr_t       Universe::_verify_oop_mask = 0;
uintptr_t       Universe::_verify_oop_bits = (uintptr_t) -1;

int             Universe::_base_vtable_size = 0;
bool            Universe::_bootstrapping = false;
bool            Universe::_module_initialized = false;
bool            Universe::_fully_initialized = false;

OopStorage*     Universe::_vm_weak = NULL;
OopStorage*     Universe::_vm_global = NULL;

CollectedHeap*  Universe::_collectedHeap = NULL;

objArrayOop Universe::the_empty_class_array ()  {
  return (objArrayOop)_the_empty_class_array.resolve();
}

oop Universe::main_thread_group()                 { return _main_thread_group.resolve(); }
void Universe::set_main_thread_group(oop group)   { _main_thread_group = OopHandle(vm_global(), group); }

oop Universe::system_thread_group()               { return _system_thread_group.resolve(); }
void Universe::set_system_thread_group(oop group) { _system_thread_group = OopHandle(vm_global(), group); }

oop Universe::the_null_string()                   { return _the_null_string.resolve(); }
oop Universe::the_min_jint_string()               { return _the_min_jint_string.resolve(); }

oop Universe::null_ptr_exception_instance()       { return _null_ptr_exception_instance.resolve(); }
oop Universe::arithmetic_exception_instance()     { return _arithmetic_exception_instance.resolve(); }
oop Universe::virtual_machine_error_instance()    { return _virtual_machine_error_instance.resolve(); }

oop Universe::the_null_sentinel()                 { return _the_null_sentinel.resolve(); }

oop Universe::int_mirror()                        { return check_mirror(_mirrors[T_INT].resolve()); }
oop Universe::float_mirror()                      { return check_mirror(_mirrors[T_FLOAT].resolve()); }
oop Universe::double_mirror()                     { return check_mirror(_mirrors[T_DOUBLE].resolve()); }
oop Universe::byte_mirror()                       { return check_mirror(_mirrors[T_BYTE].resolve()); }
oop Universe::bool_mirror()                       { return check_mirror(_mirrors[T_BOOLEAN].resolve()); }
oop Universe::char_mirror()                       { return check_mirror(_mirrors[T_CHAR].resolve()); }
oop Universe::long_mirror()                       { return check_mirror(_mirrors[T_LONG].resolve()); }
oop Universe::short_mirror()                      { return check_mirror(_mirrors[T_SHORT].resolve()); }
oop Universe::void_mirror()                       { return check_mirror(_mirrors[T_VOID].resolve()); }

oop Universe::java_mirror(BasicType t) {
  assert((uint)t < T_VOID+1, "range check");
  return check_mirror(_mirrors[t].resolve());
}

// Used by CDS dumping
void Universe::replace_mirror(BasicType t, oop new_mirror) {
  Universe::_mirrors[t].replace(new_mirror);
}

void Universe::basic_type_classes_do(void f(Klass*)) {
  for (int i = T_BOOLEAN; i < T_LONG+1; i++) {
    f(_typeArrayKlassObjs[i]);
  }
}

void Universe::basic_type_classes_do(KlassClosure *closure) {
  for (int i = T_BOOLEAN; i < T_LONG+1; i++) {
    closure->do_klass(_typeArrayKlassObjs[i]);
  }
}

void LatestMethodCache::metaspace_pointers_do(MetaspaceClosure* it) {
  it->push(&_klass);
}

void Universe::metaspace_pointers_do(MetaspaceClosure* it) {
  for (int i = 0; i < T_LONG+1; i++) {
    it->push(&_typeArrayKlassObjs[i]);
  }
  it->push(&_objectArrayKlassObj);

  it->push(&_the_empty_int_array);
  it->push(&_the_empty_short_array);
  it->push(&_the_empty_klass_array);
  it->push(&_the_empty_instance_klass_array);
  it->push(&_the_empty_method_array);
  it->push(&_the_array_interfaces_array);

  _finalizer_register_cache->metaspace_pointers_do(it);
  _loader_addClass_cache->metaspace_pointers_do(it);
  _throw_illegal_access_error_cache->metaspace_pointers_do(it);
  _throw_no_such_method_error_cache->metaspace_pointers_do(it);
  _do_stack_walk_cache->metaspace_pointers_do(it);
}

// Serialize metadata and pointers to primitive type mirrors in and out of CDS archive
void Universe::serialize(SerializeClosure* f) {

#if INCLUDE_CDS_JAVA_HEAP
  {
    oop mirror_oop;
    for (int i = T_BOOLEAN; i < T_VOID+1; i++) {
      if (f->reading()) {
        f->do_oop(&mirror_oop); // read from archive
        assert(oopDesc::is_oop_or_null(mirror_oop), "is oop");
        // Only create an OopHandle for non-null mirrors
        if (mirror_oop != NULL) {
          _mirrors[i] = OopHandle(vm_global(), mirror_oop);
        }
      } else {
        if (HeapShared::can_write()) {
          mirror_oop = _mirrors[i].resolve();
        } else {
          mirror_oop = NULL;
        }
        f->do_oop(&mirror_oop); // write to archive
      }
      if (mirror_oop != NULL) { // may be null if archived heap is disabled
        java_lang_Class::update_archived_primitive_mirror_native_pointers(mirror_oop);
      }
    }
  }
#endif

  for (int i = 0; i < T_LONG+1; i++) {
    f->do_ptr((void**)&_typeArrayKlassObjs[i]);
  }

  f->do_ptr((void**)&_objectArrayKlassObj);
  f->do_ptr((void**)&_the_array_interfaces_array);
  f->do_ptr((void**)&_the_empty_int_array);
  f->do_ptr((void**)&_the_empty_short_array);
  f->do_ptr((void**)&_the_empty_method_array);
  f->do_ptr((void**)&_the_empty_klass_array);
  f->do_ptr((void**)&_the_empty_instance_klass_array);
  _finalizer_register_cache->serialize(f);
  _loader_addClass_cache->serialize(f);
  _throw_illegal_access_error_cache->serialize(f);
  _throw_no_such_method_error_cache->serialize(f);
  _do_stack_walk_cache->serialize(f);
}


void Universe::check_alignment(uintx size, uintx alignment, const char* name) {
  if (size < alignment || size % alignment != 0) {
    vm_exit_during_initialization(
      err_msg("Size of %s (" UINTX_FORMAT " bytes) must be aligned to " UINTX_FORMAT " bytes", name, size, alignment));
  }
}

void initialize_basic_type_klass(Klass* k, TRAPS) {
  Klass* ok = vmClasses::Object_klass();
#if INCLUDE_CDS
  if (UseSharedSpaces) {
    ClassLoaderData* loader_data = ClassLoaderData::the_null_class_loader_data();
    assert(k->super() == ok, "u3");
    if (k->is_instance_klass()) {
      InstanceKlass::cast(k)->restore_unshareable_info(loader_data, Handle(), NULL, CHECK);
    } else {
      ArrayKlass::cast(k)->restore_unshareable_info(loader_data, Handle(), CHECK);
    }
  } else
#endif
  {
    k->initialize_supers(ok, NULL, CHECK);
  }
  k->append_to_sibling_list();
}

void Universe::genesis(TRAPS) {
  ResourceMark rm(THREAD);
  HandleMark   hm(THREAD);

  { AutoModifyRestore<bool> temporarily(_bootstrapping, true);

    { MutexLocker mc(THREAD, Compile_lock);

      java_lang_Class::allocate_fixup_lists();

      // determine base vtable size; without that we cannot create the array klasses
      compute_base_vtable_size();

      if (!UseSharedSpaces) {
        for (int i = T_BOOLEAN; i < T_LONG+1; i++) {
          _typeArrayKlassObjs[i] = TypeArrayKlass::create_klass((BasicType)i, CHECK);
        }

        ClassLoaderData* null_cld = ClassLoaderData::the_null_class_loader_data();

        _the_array_interfaces_array     = MetadataFactory::new_array<Klass*>(null_cld, 2, NULL, CHECK);
        _the_empty_int_array            = MetadataFactory::new_array<int>(null_cld, 0, CHECK);
        _the_empty_short_array          = MetadataFactory::new_array<u2>(null_cld, 0, CHECK);
        _the_empty_method_array         = MetadataFactory::new_array<Method*>(null_cld, 0, CHECK);
        _the_empty_klass_array          = MetadataFactory::new_array<Klass*>(null_cld, 0, CHECK);
        _the_empty_instance_klass_array = MetadataFactory::new_array<InstanceKlass*>(null_cld, 0, CHECK);
      }
    }

    vmSymbols::initialize();

    SystemDictionary::initialize(CHECK);

    // Create string constants
    oop s = StringTable::intern("null", CHECK);
    _the_null_string = OopHandle(vm_global(), s);
    s = StringTable::intern("-2147483648", CHECK);
    _the_min_jint_string = OopHandle(vm_global(), s);


#if INCLUDE_CDS
    if (UseSharedSpaces) {
      // Verify shared interfaces array.
      assert(_the_array_interfaces_array->at(0) ==
             vmClasses::Cloneable_klass(), "u3");
      assert(_the_array_interfaces_array->at(1) ==
             vmClasses::Serializable_klass(), "u3");
    } else
#endif
    {
      // Set up shared interfaces array.  (Do this before supers are set up.)
      _the_array_interfaces_array->at_put(0, vmClasses::Cloneable_klass());
      _the_array_interfaces_array->at_put(1, vmClasses::Serializable_klass());
    }

    initialize_basic_type_klass(boolArrayKlassObj(), CHECK);
    initialize_basic_type_klass(charArrayKlassObj(), CHECK);
    initialize_basic_type_klass(floatArrayKlassObj(), CHECK);
    initialize_basic_type_klass(doubleArrayKlassObj(), CHECK);
    initialize_basic_type_klass(byteArrayKlassObj(), CHECK);
    initialize_basic_type_klass(shortArrayKlassObj(), CHECK);
    initialize_basic_type_klass(intArrayKlassObj(), CHECK);
    initialize_basic_type_klass(longArrayKlassObj(), CHECK);
  } // end of core bootstrapping

  {
    Handle tns = java_lang_String::create_from_str("<null_sentinel>", CHECK);
    _the_null_sentinel = OopHandle(vm_global(), tns());
  }

  // Create a handle for reference_pending_list
  _reference_pending_list = OopHandle(vm_global(), NULL);

  // Maybe this could be lifted up now that object array can be initialized
  // during the bootstrapping.

  // OLD
  // Initialize _objectArrayKlass after core bootstraping to make
  // sure the super class is set up properly for _objectArrayKlass.
  // ---
  // NEW
  // Since some of the old system object arrays have been converted to
  // ordinary object arrays, _objectArrayKlass will be loaded when
  // SystemDictionary::initialize(CHECK); is run. See the extra check
  // for Object_klass_loaded in objArrayKlassKlass::allocate_objArray_klass_impl.
  _objectArrayKlassObj = InstanceKlass::
    cast(vmClasses::Object_klass())->array_klass(1, CHECK);
  // OLD
  // Add the class to the class hierarchy manually to make sure that
  // its vtable is initialized after core bootstrapping is completed.
  // ---
  // New
  // Have already been initialized.
  _objectArrayKlassObj->append_to_sibling_list();

  #ifdef ASSERT
  if (FullGCALot) {
    // Allocate an array of dummy objects.
    // We'd like these to be at the bottom of the old generation,
    // so that when we free one and then collect,
    // (almost) the whole heap moves
    // and we find out if we actually update all the oops correctly.
    // But we can't allocate directly in the old generation,
    // so we allocate wherever, and hope that the first collection
    // moves these objects to the bottom of the old generation.
    int size = FullGCALotDummies * 2;

    objArrayOop    naked_array = oopFactory::new_objArray(vmClasses::Object_klass(), size, CHECK);
    objArrayHandle dummy_array(THREAD, naked_array);
    int i = 0;
    while (i < size) {
        // Allocate dummy in old generation
      oop dummy = vmClasses::Object_klass()->allocate_instance(CHECK);
      dummy_array->obj_at_put(i++, dummy);
    }
    {
      // Only modify the global variable inside the mutex.
      // If we had a race to here, the other dummy_array instances
      // and their elements just get dropped on the floor, which is fine.
      MutexLocker ml(THREAD, FullGCALot_lock);
      if (_fullgc_alot_dummy_array.is_empty()) {
        _fullgc_alot_dummy_array = OopHandle(vm_global(), dummy_array());
      }
    }
    assert(i == ((objArrayOop)_fullgc_alot_dummy_array.resolve())->length(), "just checking");
  }
  #endif
}

void Universe::initialize_basic_type_mirrors(TRAPS) {
#if INCLUDE_CDS_JAVA_HEAP
    if (UseSharedSpaces &&
        HeapShared::are_archived_mirrors_available() &&
        _mirrors[T_INT].resolve() != NULL) {
      assert(HeapShared::can_use(), "Sanity");

      // check that all mirrors are mapped also
      for (int i = T_BOOLEAN; i < T_VOID+1; i++) {
        if (!is_reference_type((BasicType)i)) {
          oop m = _mirrors[i].resolve();
          assert(m != NULL, "archived mirrors should not be NULL");
        }
      }
    } else
      // _mirror[T_INT} could be NULL if archived heap is not mapped.
#endif
    {
      for (int i = T_BOOLEAN; i < T_VOID+1; i++) {
        BasicType bt = (BasicType)i;
        if (!is_reference_type(bt)) {
          oop m = java_lang_Class::create_basic_type_mirror(type2name(bt), bt, CHECK);
          _mirrors[i] = OopHandle(vm_global(), m);
        }
      }
    }
}

void Universe::fixup_mirrors(TRAPS) {
  // Bootstrap problem: all classes gets a mirror (java.lang.Class instance) assigned eagerly,
  // but we cannot do that for classes created before java.lang.Class is loaded. Here we simply
  // walk over permanent objects created so far (mostly classes) and fixup their mirrors. Note
  // that the number of objects allocated at this point is very small.
  assert(vmClasses::Class_klass_loaded(), "java.lang.Class should be loaded");
  HandleMark hm(THREAD);

  if (!UseSharedSpaces) {
    // Cache the start of the static fields
    InstanceMirrorKlass::init_offset_of_static_fields();
  }

  GrowableArray <Klass*>* list = java_lang_Class::fixup_mirror_list();
  int list_length = list->length();
  for (int i = 0; i < list_length; i++) {
    Klass* k = list->at(i);
    assert(k->is_klass(), "List should only hold classes");
    java_lang_Class::fixup_mirror(k, CATCH);
  }
  delete java_lang_Class::fixup_mirror_list();
  java_lang_Class::set_fixup_mirror_list(NULL);
}

#define assert_pll_locked(test) \
  assert(Heap_lock->test(), "Reference pending list access requires lock")

#define assert_pll_ownership() assert_pll_locked(owned_by_self)

oop Universe::reference_pending_list() {
  if (Thread::current()->is_VM_thread()) {
    assert_pll_locked(is_locked);
  } else {
    assert_pll_ownership();
  }
  return _reference_pending_list.resolve();
}

void Universe::clear_reference_pending_list() {
  assert_pll_ownership();
  _reference_pending_list.replace(NULL);
}

bool Universe::has_reference_pending_list() {
  assert_pll_ownership();
  return _reference_pending_list.peek() != NULL;
}

oop Universe::swap_reference_pending_list(oop list) {
  assert_pll_locked(is_locked);
  return _reference_pending_list.xchg(list);
}

#undef assert_pll_locked
#undef assert_pll_ownership

static void reinitialize_vtables() {
  // The vtables are initialized by starting at java.lang.Object and
  // initializing through the subclass links, so that the super
  // classes are always initialized first.
  for (ClassHierarchyIterator iter(vmClasses::Object_klass()); !iter.done(); iter.next()) {
    Klass* sub = iter.klass();
    sub->vtable().initialize_vtable();
  }
}

static void reinitialize_itables() {

  class ReinitTableClosure : public KlassClosure {
   public:
    void do_klass(Klass* k) {
      if (k->is_instance_klass()) {
         InstanceKlass::cast(k)->itable().initialize_itable();
      }
    }
  };

  MutexLocker mcld(ClassLoaderDataGraph_lock);
  ReinitTableClosure cl;
  ClassLoaderDataGraph::classes_do(&cl);
}


bool Universe::on_page_boundary(void* addr) {
  return is_aligned(addr, os::vm_page_size());
}

// the array of preallocated errors with backtraces
objArrayOop Universe::preallocated_out_of_memory_errors() {
  return (objArrayOop)_preallocated_out_of_memory_error_array.resolve();
}

objArrayOop Universe::out_of_memory_errors() { return (objArrayOop)_out_of_memory_errors.resolve(); }

oop Universe::out_of_memory_error_java_heap() {
  return gen_out_of_memory_error(out_of_memory_errors()->obj_at(_oom_java_heap));
}

oop Universe::out_of_memory_error_c_heap() {
  return gen_out_of_memory_error(out_of_memory_errors()->obj_at(_oom_c_heap));
}

oop Universe::out_of_memory_error_metaspace() {
  return gen_out_of_memory_error(out_of_memory_errors()->obj_at(_oom_metaspace));
}

oop Universe::out_of_memory_error_class_metaspace() {
  return gen_out_of_memory_error(out_of_memory_errors()->obj_at(_oom_class_metaspace));
}

oop Universe::out_of_memory_error_array_size() {
  return gen_out_of_memory_error(out_of_memory_errors()->obj_at(_oom_array_size));
}

oop Universe::out_of_memory_error_gc_overhead_limit() {
  return gen_out_of_memory_error(out_of_memory_errors()->obj_at(_oom_gc_overhead_limit));
}

oop Universe::out_of_memory_error_realloc_objects() {
  return gen_out_of_memory_error(out_of_memory_errors()->obj_at(_oom_realloc_objects));
}

// Throw default _out_of_memory_error_retry object as it will never propagate out of the VM
oop Universe::out_of_memory_error_retry()              { return out_of_memory_errors()->obj_at(_oom_retry);  }
oop Universe::delayed_stack_overflow_error_message()   { return _delayed_stack_overflow_error_message.resolve(); }


bool Universe::should_fill_in_stack_trace(Handle throwable) {
  // never attempt to fill in the stack trace of preallocated errors that do not have
  // backtrace. These errors are kept alive forever and may be "re-used" when all
  // preallocated errors with backtrace have been consumed. Also need to avoid
  // a potential loop which could happen if an out of memory occurs when attempting
  // to allocate the backtrace.
  objArrayOop preallocated_oom = out_of_memory_errors();
  for (int i = 0; i < _oom_count; i++) {
    if (throwable() == preallocated_oom->obj_at(i)) {
      return false;
    }
  }
  return true;
}


oop Universe::gen_out_of_memory_error(oop default_err) {
  // generate an out of memory error:
  // - if there is a preallocated error and stack traces are available
  //   (j.l.Throwable is initialized), then return the preallocated
  //   error with a filled in stack trace, and with the message
  //   provided by the default error.
  // - otherwise, return the default error, without a stack trace.
  int next;
  if ((_preallocated_out_of_memory_error_avail_count > 0) &&
      vmClasses::Throwable_klass()->is_initialized()) {
    next = (int)Atomic::add(&_preallocated_out_of_memory_error_avail_count, -1);
    assert(next < (int)PreallocatedOutOfMemoryErrorCount, "avail count is corrupt");
  } else {
    next = -1;
  }
  if (next < 0) {
    // all preallocated errors have been used.
    // return default
    return default_err;
  } else {
    JavaThread* current = JavaThread::current();
    Handle default_err_h(current, default_err);
    // get the error object at the slot and set set it to NULL so that the
    // array isn't keeping it alive anymore.
    Handle exc(current, preallocated_out_of_memory_errors()->obj_at(next));
    assert(exc() != NULL, "slot has been used already");
    preallocated_out_of_memory_errors()->obj_at_put(next, NULL);

    // use the message from the default error
    oop msg = java_lang_Throwable::message(default_err_h());
    assert(msg != NULL, "no message");
    java_lang_Throwable::set_message(exc(), msg);

    // populate the stack trace and return it.
    java_lang_Throwable::fill_in_stack_trace_of_preallocated_backtrace(exc);
    return exc();
  }
}

// Setup preallocated OutOfMemoryError errors
void Universe::create_preallocated_out_of_memory_errors(TRAPS) {
  InstanceKlass* ik = vmClasses::OutOfMemoryError_klass();
  objArrayOop oa = oopFactory::new_objArray(ik, _oom_count, CHECK);
  objArrayHandle oom_array(THREAD, oa);

  for (int i = 0; i < _oom_count; i++) {
    oop oom_obj = ik->allocate_instance(CHECK);
    oom_array->obj_at_put(i, oom_obj);
  }
  _out_of_memory_errors = OopHandle(vm_global(), oom_array());

  Handle msg = java_lang_String::create_from_str("Java heap space", CHECK);
  java_lang_Throwable::set_message(oom_array->obj_at(_oom_java_heap), msg());
  
  msg = java_lang_String::create_from_str("C heap space", CHECK);
  java_lang_Throwable::set_message(oom_array->obj_at(_oom_c_heap), msg());

  msg = java_lang_String::create_from_str("Metaspace", CHECK);
  java_lang_Throwable::set_message(oom_array->obj_at(_oom_metaspace), msg());

  msg = java_lang_String::create_from_str("Compressed class space", CHECK);
  java_lang_Throwable::set_message(oom_array->obj_at(_oom_class_metaspace), msg());

  msg = java_lang_String::create_from_str("Requested array size exceeds VM limit", CHECK);
  java_lang_Throwable::set_message(oom_array->obj_at(_oom_array_size), msg());

  msg = java_lang_String::create_from_str("GC overhead limit exceeded", CHECK);
  java_lang_Throwable::set_message(oom_array->obj_at(_oom_gc_overhead_limit), msg());

  msg = java_lang_String::create_from_str("Java heap space: failed reallocation of scalar replaced objects", CHECK);
  java_lang_Throwable::set_message(oom_array->obj_at(_oom_realloc_objects), msg());

  msg = java_lang_String::create_from_str("Java heap space: failed retryable allocation", CHECK);
  java_lang_Throwable::set_message(oom_array->obj_at(_oom_retry), msg());

  // Setup the array of errors that have preallocated backtrace
  int len = (StackTraceInThrowable) ? (int)PreallocatedOutOfMemoryErrorCount : 0;
  objArrayOop instance = oopFactory::new_objArray(ik, len, CHECK);
  _preallocated_out_of_memory_error_array = OopHandle(vm_global(), instance);
  objArrayHandle preallocated_oom_array(THREAD, instance);

  for (int i=0; i<len; i++) {
    oop err = ik->allocate_instance(CHECK);
    Handle err_h(THREAD, err);
    java_lang_Throwable::allocate_backtrace(err_h, CHECK);
    preallocated_oom_array->obj_at_put(i, err_h());
  }
  _preallocated_out_of_memory_error_avail_count = (jint)len;
}

intptr_t Universe::_non_oop_bits = 0;

void* Universe::non_oop_word() {
  // Neither the high bits nor the low bits of this value is allowed
  // to look like (respectively) the high or low bits of a real oop.
  //
  // High and low are CPU-specific notions, but low always includes
  // the low-order bit.  Since oops are always aligned at least mod 4,
  // setting the low-order bit will ensure that the low half of the
  // word will never look like that of a real oop.
  //
  // Using the OS-supplied non-memory-address word (usually 0 or -1)
  // will take care of the high bits, however many there are.

  if (_non_oop_bits == 0) {
    _non_oop_bits = (intptr_t)os::non_memory_address_word() | 1;
  }

  return (void*)_non_oop_bits;
}

bool Universe::contains_non_oop_word(void* p) {
  return *(void**)p == non_oop_word();
}

static void initialize_global_behaviours() {
  CompiledICProtectionBehaviour::set_current(new DefaultICProtectionBehaviour());
}

jint universe_init() {
  assert(!Universe::_fully_initialized, "called after initialize_vtables");
  guarantee(1 << LogHeapWordSize == sizeof(HeapWord),
         "LogHeapWordSize is incorrect.");
  guarantee(sizeof(oop) >= sizeof(HeapWord), "HeapWord larger than oop?");
  guarantee(sizeof(oop) % sizeof(HeapWord) == 0,
            "oop size is not not a multiple of HeapWord size");

  TraceTime timer("Genesis", TRACETIME_LOG(Info, startuptime));

  initialize_global_behaviours();

  GCLogPrecious::initialize();

  GCConfig::arguments()->initialize_heap_sizes();

  jint status = Universe::initialize_heap();
  if (status != JNI_OK) {
    return status;
  }

  Universe::initialize_tlab();

  Metaspace::global_initialize();

  // Initialize performance counters for metaspaces
  MetaspaceCounters::initialize_performance_counters();

  // Checks 'AfterMemoryInit' constraints.
  if (!JVMFlagLimit::check_all_constraints(JVMFlagConstraintPhase::AfterMemoryInit)) {
    return JNI_EINVAL;
  }

  // Create memory for metadata.  Must be after initializing heap for
  // DumpSharedSpaces.
  ClassLoaderData::init_null_class_loader_data();

  // We have a heap so create the Method* caches before
  // Metaspace::initialize_shared_spaces() tries to populate them.
  Universe::_finalizer_register_cache = new LatestMethodCache();
  Universe::_loader_addClass_cache    = new LatestMethodCache();
  Universe::_throw_illegal_access_error_cache = new LatestMethodCache();
  Universe::_throw_no_such_method_error_cache = new LatestMethodCache();
  Universe::_do_stack_walk_cache = new LatestMethodCache();

#if INCLUDE_CDS
  DynamicArchive::check_for_dynamic_dump();
  if (UseSharedSpaces) {
    // Read the data structures supporting the shared spaces (shared
    // system dictionary, symbol table, etc.).  After that, access to
    // the file (other than the mapped regions) is no longer needed, and
    // the file is closed. Closing the file does not affect the
    // currently mapped regions.
    MetaspaceShared::initialize_shared_spaces();
    StringTable::create_table();
    if (HeapShared::is_loaded()) {
      StringTable::transfer_shared_strings_to_local_table();
    }
  } else
#endif
  {
    SymbolTable::create_table();
    StringTable::create_table();
  }

#if INCLUDE_CDS
  if (Arguments::is_dumping_archive()) {
    MetaspaceShared::prepare_for_dumping();
  }
#endif

  if (strlen(VerifySubSet) > 0) {
    Universe::initialize_verify_flags();
  }

  ResolvedMethodTable::create_table();

  return JNI_OK;
}

jint Universe::initialize_heap() {
  assert(_collectedHeap == NULL, "Heap already created");
  _collectedHeap = GCConfig::arguments()->create_heap();

  log_info(gc)("Using %s", _collectedHeap->name());
  return _collectedHeap->initialize();
}

void Universe::initialize_tlab() {
  ThreadLocalAllocBuffer::set_max_size(Universe::heap()->max_tlab_size());
  if (UseTLAB) {
    ThreadLocalAllocBuffer::startup_initialization();
  }
}

ReservedHeapSpace Universe::reserve_heap(size_t heap_size, size_t alignment) {

  assert(alignment <= Arguments::conservative_max_heap_alignment(),
         "actual alignment " SIZE_FORMAT " must be within maximum heap alignment " SIZE_FORMAT,
         alignment, Arguments::conservative_max_heap_alignment());

  size_t total_reserved = align_up(heap_size, alignment);
  assert(!UseCompressedOops || (total_reserved <= (OopEncodingHeapMax - os::vm_page_size())),
      "heap size is too big for compressed oops");

  size_t page_size = os::vm_page_size();
  if (UseLargePages && is_aligned(alignment, os::large_page_size())) {
    page_size = os::large_page_size();
  } else {
    // Parallel is the only collector that might opt out of using large pages
    // for the heap.
    assert(!UseLargePages || UseParallelGC , "Wrong alignment to use large pages");
  }

  // Now create the space.
  ReservedHeapSpace total_rs(total_reserved, alignment, page_size, AllocateHeapAt);

  if (total_rs.is_reserved()) {
    assert((total_reserved == total_rs.size()) && ((uintptr_t)total_rs.base() % alignment == 0),
           "must be exactly of required size and alignment");
    // We are good.

    if (AllocateHeapAt != NULL) {
      log_info(gc,heap)("Successfully allocated Java heap at location %s", AllocateHeapAt);
    }

    if (UseCompressedOops) {
      CompressedOops::initialize(total_rs);
    }

    Universe::calculate_verify_data((HeapWord*)total_rs.base(), (HeapWord*)total_rs.end());

    return total_rs;
  }

  vm_exit_during_initialization(
    err_msg("Could not reserve enough space for " SIZE_FORMAT "KB object heap",
            total_reserved/K));

  // satisfy compiler
  ShouldNotReachHere();
  return ReservedHeapSpace(0, 0, os::vm_page_size());
}

OopStorage* Universe::vm_weak() {
  return Universe::_vm_weak;
}

OopStorage* Universe::vm_global() {
  return Universe::_vm_global;
}

void Universe::oopstorage_init() {
  Universe::_vm_global = OopStorageSet::create_strong("VM Global", mtInternal);
  Universe::_vm_weak = OopStorageSet::create_weak("VM Weak", mtInternal);
}

void universe_oopstorage_init() {
  Universe::oopstorage_init();
}

void initialize_known_method(LatestMethodCache* method_cache,
                             InstanceKlass* ik,
                             const char* method,
                             Symbol* signature,
                             bool is_static, TRAPS)
{
  TempNewSymbol name = SymbolTable::new_symbol(method);
  Method* m = NULL;
  // The klass must be linked before looking up the method.
  if (!ik->link_class_or_fail(THREAD) ||
      ((m = ik->find_method(name, signature)) == NULL) ||
      is_static != m->is_static()) {
    ResourceMark rm(THREAD);
    // NoSuchMethodException doesn't actually work because it tries to run the
    // <init> function before java_lang_Class is linked. Print error and exit.
    vm_exit_during_initialization(err_msg("Unable to link/verify %s.%s method",
                                 ik->name()->as_C_string(), method));
  }
  method_cache->init(ik, m);
}

void Universe::initialize_known_methods(TRAPS) {
  // Set up static method for registering finalizers
  initialize_known_method(_finalizer_register_cache,
                          vmClasses::Finalizer_klass(),
                          "register",
                          vmSymbols::object_void_signature(), true, CHECK);

  initialize_known_method(_throw_illegal_access_error_cache,
                          vmClasses::internal_Unsafe_klass(),
                          "throwIllegalAccessError",
                          vmSymbols::void_method_signature(), true, CHECK);

  initialize_known_method(_throw_no_such_method_error_cache,
                          vmClasses::internal_Unsafe_klass(),
                          "throwNoSuchMethodError",
                          vmSymbols::void_method_signature(), true, CHECK);

  // Set up method for registering loaded classes in class loader vector
  initialize_known_method(_loader_addClass_cache,
                          vmClasses::ClassLoader_klass(),
                          "addClass",
                          vmSymbols::class_void_signature(), false, CHECK);

  // Set up method for stack walking
  initialize_known_method(_do_stack_walk_cache,
                          vmClasses::AbstractStackWalker_klass(),
                          "doStackWalk",
                          vmSymbols::doStackWalk_signature(), false, CHECK);
}

void universe2_init() {
  EXCEPTION_MARK;
  Universe::genesis(CATCH);
}

// Set after initialization of the module runtime, call_initModuleRuntime
void universe_post_module_init() {
  Universe::_module_initialized = true;
}

bool universe_post_init() {
  assert(!is_init_completed(), "Error: initialization not yet completed!");
  Universe::_fully_initialized = true;
  EXCEPTION_MARK;
  if (!UseSharedSpaces) {
    reinitialize_vtables();
    reinitialize_itables();
  }

  HandleMark hm(THREAD);
  // Setup preallocated empty java.lang.Class array for Method reflection.

  objArrayOop the_empty_class_array = oopFactory::new_objArray(vmClasses::Class_klass(), 0, CHECK_false);
  Universe::_the_empty_class_array = OopHandle(Universe::vm_global(), the_empty_class_array);

  // Setup preallocated OutOfMemoryError errors
  Universe::create_preallocated_out_of_memory_errors(CHECK_false);

  oop instance;
  // Setup preallocated cause message for delayed StackOverflowError
  if (StackReservedPages > 0) {
    instance = java_lang_String::create_oop_from_str("Delayed StackOverflowError due to ReservedStackAccess annotated method", CHECK_false);
    Universe::_delayed_stack_overflow_error_message = OopHandle(Universe::vm_global(), instance);
  }

  // Setup preallocated NullPointerException
  // (this is currently used for a cheap & dirty solution in compiler exception handling)
  Klass* k = SystemDictionary::resolve_or_fail(vmSymbols::java_lang_NullPointerException(), true, CHECK_false);
  instance = InstanceKlass::cast(k)->allocate_instance(CHECK_false);
  Universe::_null_ptr_exception_instance = OopHandle(Universe::vm_global(), instance);

  // Setup preallocated ArithmeticException
  // (this is currently used for a cheap & dirty solution in compiler exception handling)
  k = SystemDictionary::resolve_or_fail(vmSymbols::java_lang_ArithmeticException(), true, CHECK_false);
  instance = InstanceKlass::cast(k)->allocate_instance(CHECK_false);
  Universe::_arithmetic_exception_instance = OopHandle(Universe::vm_global(), instance);

  // Virtual Machine Error for when we get into a situation we can't resolve
  k = vmClasses::VirtualMachineError_klass();
  bool linked = InstanceKlass::cast(k)->link_class_or_fail(CHECK_false);
  if (!linked) {
     tty->print_cr("Unable to link/verify VirtualMachineError class");
     return false; // initialization failed
  }
  instance = InstanceKlass::cast(k)->allocate_instance(CHECK_false);
  Universe::_virtual_machine_error_instance = OopHandle(Universe::vm_global(), instance);

  Handle msg = java_lang_String::create_from_str("/ by zero", CHECK_false);
  java_lang_Throwable::set_message(Universe::arithmetic_exception_instance(), msg());

  Universe::initialize_known_methods(CHECK_false);

  // This needs to be done before the first scavenge/gc, since
  // it's an input to soft ref clearing policy.
  {
    MutexLocker x(THREAD, Heap_lock);
    Universe::heap()->update_capacity_and_used_at_gc();
  }

  // ("weak") refs processing infrastructure initialization
  Universe::heap()->post_initialize();

  MemoryService::add_metaspace_memory_pools();

  MemoryService::set_universe_heap(Universe::heap());
#if INCLUDE_CDS
  MetaspaceShared::post_initialize(CHECK_false);
#endif
  return true;
}


void Universe::compute_base_vtable_size() {
  _base_vtable_size = ClassLoader::compute_Object_vtable();
}

void Universe::print_on(outputStream* st) {
  GCMutexLocker hl(Heap_lock); // Heap_lock might be locked by caller thread.
  st->print_cr("Heap");
  heap()->print_on(st);
}

void Universe::print_heap_at_SIGBREAK() {
  if (PrintHeapAtSIGBREAK) {
    print_on(tty);
    tty->cr();
    tty->flush();
  }
}

void Universe::initialize_verify_flags() {
  verify_flags = 0;
  const char delimiter[] = " ,";

  size_t length = strlen(VerifySubSet);
  char* subset_list = NEW_C_HEAP_ARRAY(char, length + 1, mtInternal);
  strncpy(subset_list, VerifySubSet, length + 1);
  char* save_ptr;

  char* token = strtok_r(subset_list, delimiter, &save_ptr);
  while (token != NULL) {
    if (strcmp(token, "threads") == 0) {
      verify_flags |= Verify_Threads;
    } else if (strcmp(token, "heap") == 0) {
      verify_flags |= Verify_Heap;
    } else if (strcmp(token, "symbol_table") == 0) {
      verify_flags |= Verify_SymbolTable;
    } else if (strcmp(token, "string_table") == 0) {
      verify_flags |= Verify_StringTable;
    } else if (strcmp(token, "codecache") == 0) {
      verify_flags |= Verify_CodeCache;
    } else if (strcmp(token, "dictionary") == 0) {
      verify_flags |= Verify_SystemDictionary;
    } else if (strcmp(token, "classloader_data_graph") == 0) {
      verify_flags |= Verify_ClassLoaderDataGraph;
    } else if (strcmp(token, "metaspace") == 0) {
      verify_flags |= Verify_MetaspaceUtils;
    } else if (strcmp(token, "jni_handles") == 0) {
      verify_flags |= Verify_JNIHandles;
    } else if (strcmp(token, "codecache_oops") == 0) {
      verify_flags |= Verify_CodeCacheOops;
    } else if (strcmp(token, "resolved_method_table") == 0) {
      verify_flags |= Verify_ResolvedMethodTable;
    } else if (strcmp(token, "stringdedup") == 0) {
      verify_flags |= Verify_StringDedup;
    } else {
      vm_exit_during_initialization(err_msg("VerifySubSet: \'%s\' memory sub-system is unknown, please correct it", token));
    }
    token = strtok_r(NULL, delimiter, &save_ptr);
  }
  FREE_C_HEAP_ARRAY(char, subset_list);
}

bool Universe::should_verify_subset(uint subset) {
  if (verify_flags & subset) {
    return true;
  }
  return false;
}

void Universe::verify(VerifyOption option, const char* prefix) {
  COMPILER2_PRESENT(
    assert(!DerivedPointerTable::is_active(),
         "DPT should not be active during verification "
         "(of thread stacks below)");
  )

  Thread* thread = Thread::current();
  ResourceMark rm(thread);
  HandleMark hm(thread);  // Handles created during verification can be zapped
  _verify_count++;

  FormatBuffer<> title("Verifying %s", prefix);
  GCTraceTime(Info, gc, verify) tm(title.buffer());
  if (should_verify_subset(Verify_Threads)) {
    log_debug(gc, verify)("Threads");
    Threads::verify();
  }
  if (should_verify_subset(Verify_Heap)) {
    log_debug(gc, verify)("Heap");
    heap()->verify(option);
  }
  if (should_verify_subset(Verify_SymbolTable)) {
    log_debug(gc, verify)("SymbolTable");
    SymbolTable::verify();
  }
  if (should_verify_subset(Verify_StringTable)) {
    log_debug(gc, verify)("StringTable");
    StringTable::verify();
  }
  if (should_verify_subset(Verify_CodeCache)) {
    log_debug(gc, verify)("CodeCache");
    CodeCache::verify();
  }
  if (should_verify_subset(Verify_SystemDictionary)) {
    log_debug(gc, verify)("SystemDictionary");
    SystemDictionary::verify();
  }
  if (should_verify_subset(Verify_ClassLoaderDataGraph)) {
    log_debug(gc, verify)("ClassLoaderDataGraph");
    ClassLoaderDataGraph::verify();
  }
  if (should_verify_subset(Verify_MetaspaceUtils)) {
    log_debug(gc, verify)("MetaspaceUtils");
    DEBUG_ONLY(MetaspaceUtils::verify();)
  }
  if (should_verify_subset(Verify_JNIHandles)) {
    log_debug(gc, verify)("JNIHandles");
    JNIHandles::verify();
  }
  if (should_verify_subset(Verify_CodeCacheOops)) {
    log_debug(gc, verify)("CodeCache Oops");
    CodeCache::verify_oops();
  }
  if (should_verify_subset(Verify_ResolvedMethodTable)) {
    log_debug(gc, verify)("ResolvedMethodTable Oops");
    ResolvedMethodTable::verify();
  }
  if (should_verify_subset(Verify_StringDedup)) {
    log_debug(gc, verify)("String Deduplication");
    StringDedup::verify();
  }
}


#ifndef PRODUCT
void Universe::calculate_verify_data(HeapWord* low_boundary, HeapWord* high_boundary) {
  assert(low_boundary < high_boundary, "bad interval");

  // decide which low-order bits we require to be clear:
  size_t alignSize = MinObjAlignmentInBytes;
  size_t min_object_size = CollectedHeap::min_fill_size();

  // make an inclusive limit:
  uintptr_t max = (uintptr_t)high_boundary - min_object_size*wordSize;
  uintptr_t min = (uintptr_t)low_boundary;
  assert(min < max, "bad interval");
  uintptr_t diff = max ^ min;

  // throw away enough low-order bits to make the diff vanish
  uintptr_t mask = (uintptr_t)(-1);
  while ((mask & diff) != 0)
    mask <<= 1;
  uintptr_t bits = (min & mask);
  assert(bits == (max & mask), "correct mask");
  // check an intermediate value between min and max, just to make sure:
  assert(bits == ((min + (max-min)/2) & mask), "correct mask");

  // require address alignment, too:
  mask |= (alignSize - 1);

  if (!(_verify_oop_mask == 0 && _verify_oop_bits == (uintptr_t)-1)) {
    assert(_verify_oop_mask == mask && _verify_oop_bits == bits, "mask stability");
  }
  _verify_oop_mask = mask;
  _verify_oop_bits = bits;
}

// Oop verification (see MacroAssembler::verify_oop)

uintptr_t Universe::verify_oop_mask() {
  return _verify_oop_mask;
}

uintptr_t Universe::verify_oop_bits() {
  return _verify_oop_bits;
}

uintptr_t Universe::verify_mark_mask() {
  return markWord::lock_mask_in_place;
}

uintptr_t Universe::verify_mark_bits() {
  intptr_t mask = verify_mark_mask();
  intptr_t bits = (intptr_t)markWord::prototype().value();
  assert((bits & ~mask) == 0, "no stray header bits");
  return bits;
}
#endif // PRODUCT


void LatestMethodCache::init(Klass* k, Method* m) {
  if (!UseSharedSpaces) {
    _klass = k;
  }
#ifndef PRODUCT
  else {
    // sharing initilization should have already set up _klass
    assert(_klass != NULL, "just checking");
  }
#endif

  _method_idnum = m->method_idnum();
  assert(_method_idnum >= 0, "sanity check");
}


Method* LatestMethodCache::get_method() {
  if (klass() == NULL) return NULL;
  InstanceKlass* ik = InstanceKlass::cast(klass());
  Method* m = ik->method_with_idnum(method_idnum());
  assert(m != NULL, "sanity check");
  return m;
}

#ifdef ASSERT
// Release dummy object(s) at bottom of heap
bool Universe::release_fullgc_alot_dummy() {
  MutexLocker ml(FullGCALot_lock);
  objArrayOop fullgc_alot_dummy_array = (objArrayOop)_fullgc_alot_dummy_array.resolve();
  if (fullgc_alot_dummy_array != NULL) {
    if (_fullgc_alot_dummy_next >= fullgc_alot_dummy_array->length()) {
      // No more dummies to release, release entire array instead
      _fullgc_alot_dummy_array.release(Universe::vm_global());
      return false;
    }

    // Release dummy at bottom of old generation
    fullgc_alot_dummy_array->obj_at_put(_fullgc_alot_dummy_next++, NULL);
  }
  return true;
}

bool Universe::is_gc_active() {
  return heap()->is_gc_active();
}

bool Universe::is_in_heap(const void* p) {
  return heap()->is_in(p);
}

#endif // ASSERT
