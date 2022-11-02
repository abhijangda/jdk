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
#include "gc/shared/genCollectedHeap.hpp"
#include "gc/shared/collectedHeap.inline.hpp"
#include "gc/shared/gcArguments.hpp"
#include "gc/shared/gcConfig.hpp"
#include "gc/shared/gcLogPrecious.hpp"
#include "gc/shared/gcTraceTime.inline.hpp"
#include "gc/shared/oopStorageSet.hpp"
#include "gc/shared/stringdedup/stringDedup.hpp"
#include "gc/shared/tlab_globals.hpp"
#include "gc/shared/oopStorageSet.inline.hpp"
#include "logging/log.hpp"
#include "logging/logStream.hpp"
#include "memory/metadataFactory.hpp"
#include "memory/metaspaceClosure.hpp"
#include "memory/metaspaceCounters.hpp"
#include "memory/metaspaceUtils.hpp"
#include "memory/oopFactory.hpp"
#include "memory/resourceArea.hpp"
#include "memory/universe.hpp"
#include "memory/iterator.hpp"
#include "memory/iterator.inline.hpp"
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
LinkedListImpl<Universe::HeapEvent*> Universe::all_heap_events;
sem_t Universe::cuda_semaphore;
sem_t Universe::cuda_thread_wait_semaphore;

#include <sys/mman.h>
#include <stdlib.h>
#include <sys/syscall.h>
#include <string.h>
#define gettid() syscall(SYS_gettid)
#include "runtime/interfaceSupport.inline.hpp"

pthread_spinlock_t spin_lock_heap_event;

__attribute__((constructor))
void init_lock () {
  if ( pthread_spin_init ( &spin_lock_heap_event, 0 ) != 0 ) {
    exit ( 1 );
  }
}

pthread_mutex_t Universe::MmapHeap::lock = PTHREAD_MUTEX_INITIALIZER;
Universe::MmapHeap Universe::mmap_heap;

// template <class T>
// T* Universe::STLAllocator<T>::allocate(size_t n) {
//   //os::malloc in works in DEBUG but not in PRODUCT build.
//   return (T*)mmap_heap.malloc(n*sizeof(T));
// }

// template <class T>
// void Universe::STLAllocator<T>::deallocate(T* p, size_t n) {
//   mmap_heap.free(p, n * sizeof(T));
// }

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

class FieldEdge {
  oop      _obj;
  uint64_t _offset;
  uint64_t _id;

  public:
    FieldEdge() : _obj(NULL), _offset(0), _id(0) {}
    FieldEdge(oop obj, uint64_t offset, uint64_t id) : _obj(obj), _offset(offset), _id(id) {}
    uint64_t id() const {return _id;}
    uint64_t offset() const {return _offset;}
    oop val() const {return _obj;}
    void set_val(oop val) {_obj = val;}
};

class ObjectNode {
  oop         _obj;
  size_t      _size;
  Universe::HeapEventType _type;
  uint64_t    _id;
  Universe::unordered_map<uint64_t, FieldEdge> _fields;
  
  public:
  static Universe::map<oopDesc*, ObjectNode> oop_to_obj_node;
  ObjectNode() : _obj(NULL), _size(0), _type(Universe::None), _id(0) {}
  ObjectNode(oop obj, size_t size, Universe::HeapEventType type, uint64_t id) : _obj(obj), _size(size), _type(type), _id(id) {
    assert(_type != Universe::None, "");
  }
  ~ObjectNode() {}
  void set_oop(oop obj) {_obj = obj;}
  //Size of all instance oops are multiple of heap words
  size_t size() const {return _size;}
  Universe::HeapEventType type() const {return _type;}
  uint64_t start_address() const {
    if (_type == Universe::NewObject || _type == Universe::NewPrimitiveArray) {
      instanceOopDesc* iobj = (instanceOopDesc*)(oopDesc*)_obj;
      return (uint64_t)(char*)iobj;
    } else if (_type == Universe::NewArray) {
      return (uint64_t)((char*)((objArrayOopDesc*)(oopDesc*)_obj)->base());
    } else {
      assert(false, "");
      return 0;
    }
  }
  void* end() const {
    if (_type == Universe::NewObject || _type == Universe::NewPrimitiveArray) {
      instanceOopDesc* iobj = (instanceOopDesc*)(oopDesc*)_obj;
      return (void*)((char*)iobj + iobj->base_offset_in_bytes() + size()*HeapWordSize);
    } else if (_type == Universe::NewArray) {
      return (void*)((char*)((objArrayOopDesc*)(oopDesc*)_obj)->base() + size() * sizeof(oop));
    } else {
      assert(false, "");
      return NULL;
    }
  }

  const Universe::unordered_map<uint64_t, FieldEdge>& fields() const {return _fields;}
  void update_or_add_field(void* address, oop val, uint64_t id) {
    uint64_t off = (uint64_t)address - start_address();
    _fields[off] = FieldEdge(val, off, id);
  }

  void update_field_vals(const Universe::map<oopDesc*, oopDesc*>& old_to_newobjs) {
    for (auto& it : _fields) {
      if (old_to_newobjs.count(it.second.val()) > 0)
        it.second.set_val(old_to_newobjs.at(it.second.val()));
    }
  }

  oop field_val(uint64_t offset) {
    return _fields[offset].val();
  }

  oop field_val(void* field_addr) {
    return field_val((uint64_t)field_addr - start_address());
  }

  bool has_field (uint64_t offset) const {
    return _fields.count(offset) == 1;
  }

  bool has_field (void* field_addr) const {
    return has_field((uint64_t)field_addr - start_address());
  }
};

Universe::map<oopDesc*, ObjectNode> ObjectNode::oop_to_obj_node;
uint32_t Universe::checking = 0;

template<typename T>
static T min(T a1, T a2) { return (a1 < a2) ? a1 : a2;}
template<typename T>
static T max(T a1, T a2) { return (a1 > a2) ? a1 : a2;}

static char* get_klass_name(Klass* klass, char buf[]) {
  if (klass != NULL && (uint64_t)klass != 0xbaadbabebaadbabe && klass->is_klass())
    klass->name()->as_C_string(buf, 1024);
  return buf;
}

char* Universe::get_oop_klass_name(oop obj_, char buf[]) {
  get_klass_name(obj_->klass(), buf);
  return buf;
}

BasicType signature_to_type(char* signature) {
  if (signature[0] == 'L')
    return T_OBJECT;
  if (signature[0] == '[')
    return T_ARRAY;
  
  return T_BOOLEAN; //Does not matter for this purpose yet.
}

bool is_field_of_reference_type(InstanceKlass* ik, int field) {
  char signature[1024];
  ik->field_signature(field)->as_C_string(signature, 1024);

  return signature_to_type(signature) == T_OBJECT || 
         signature_to_type(signature) == T_ARRAY;
}

int InstanceKlassPointerComparer(const void* a, const void* b) {
  InstanceKlass* bik = *(InstanceKlass**)b;
  InstanceKlass* aik = *(InstanceKlass**)a;
  if (aik < bik) return -1;
  if (aik == bik) return 0;
  return 1;
}

class CheckGraph : public ObjectClosure {
  public:
  const bool print_not_found = false;
  static const void* INVALID_PTR;
  static const oop INVALID_OOP;
  bool _check_objects;
  bool _check_object_fields;
  bool _check_array_elements;
  bool _check_static_fields;
  bool valid;
  int num_found, num_not_found;
  int num_src_not_correct;
  int num_statics_checked;
  int num_oops;
  int num_fields;
  Universe::unordered_set<HeapWord*> static_fields_checked;

  CheckGraph(bool check_objects, bool check_object_fields, bool check_array_elements, bool check_static_fields) {
    _check_objects = check_objects;
    _check_object_fields = check_object_fields;
    _check_array_elements = check_array_elements;
    _check_static_fields = check_static_fields; 
    valid = true;
    num_found = 0;
    num_not_found = 0;
    num_src_not_correct = 0;
    num_statics_checked = 0;
    num_oops = 0;
    num_fields = 0;

    if (_check_object_fields) _check_objects = true;
    if (_check_array_elements) _check_objects = true;
  }
  
  virtual void do_object(oop obj) {
    if (obj == INVALID_OOP) return;
    Klass* klass = obj->klass_or_null();
    if (klass && klass != INVALID_PTR && klass->is_klass()) {
      auto oop_obj_node_pair = ObjectNode::oop_to_obj_node.find(obj);
      //Check obj is in HeapEventGraph and see if the size in HeapEventGraph matches with 
      //object size
      if (_check_objects) {
        if (oop_obj_node_pair != ObjectNode::oop_to_obj_node.end()) {
          num_found++;
          if (klass->is_instance_klass()) {
            if (obj->size() != oop_obj_node_pair->second.size()) {
              if (num_src_not_correct < 100) {
                char class_name[1024];
                Universe::get_oop_klass_name(obj, class_name);
                printf("Size mismatch for obj '%p'(with event '%ld') of class '%s': '%ld' != '%ld'\n", (oopDesc*)obj, oop_obj_node_pair->second.type(), class_name, obj->size(), oop_obj_node_pair->second.size());
              }
              num_src_not_correct++;
            }
            if (oop_obj_node_pair->second.type() != Universe::NewObject) {
              printf("Wrong obj type '%ld' instead of NewObject\n", oop_obj_node_pair->second.type());
            }
          } else if (klass->id() == ObjArrayKlassID) {
            objArrayOop array = (objArrayOop)obj;
            if ((size_t)array->length() != oop_obj_node_pair->second.size()) {
              if (num_src_not_correct < 100) {
                printf("Array length mismatch for '%p': %d != %ld\n", (oopDesc*)obj, array->length(), oop_obj_node_pair->second.size());
              }
              num_src_not_correct++;

              if (oop_obj_node_pair->second.type() != Universe::NewArray) {
                printf("Wrong obj type '%ld' instead of ObjArrayKlass\n", oop_obj_node_pair->second.type());
              }
            }
          } else if (klass->id() == TypeArrayKlassID) {
            if (oop_obj_node_pair->second.type() != Universe::NewPrimitiveArray) {
              char buf[1024];
              Universe::get_oop_klass_name(obj, buf);
              // printf("Wrong obj type '%ld' instead of NewPrimitiveArray '%s'\n", oop_obj_node_pair->second.type(), buf);
            }
          }
        } else {
          num_not_found++;
          if (num_not_found < 100) {
            char class_name[1024];
            Universe::get_oop_klass_name(obj, class_name);
            printf("Not found: object '%p' with class '%s'\n", (void*)obj, class_name);
            if (obj->klass()->is_array_klass()) {
              printf("length: %d\n", ((arrayOop)obj)->length());
            }
          }
        }
      }

      if (oop_obj_node_pair == ObjectNode::oop_to_obj_node.end()) return;
      
      const ObjectNode& obj_node = oop_obj_node_pair->second;

      //If this oop is a MirrorOop of a klass then check static fields
      if (_check_static_fields && obj->klass()->id() == InstanceMirrorKlassID) {
        oop ikoop                  = ((InstanceKlass*)obj->klass())->java_mirror();
        auto ikoop_iter            = ObjectNode::oop_to_obj_node.find(ikoop);
        InstanceMirrorKlass* imk   = (InstanceMirrorKlass*)ikoop->klass();
        HeapWord* obj_static_start = imk->start_of_static_fields(obj);
        HeapWord* tmp              = obj_static_start;
        int num_statics            = java_lang_Class::static_oop_field_count(obj);
        HeapWord* end              = obj_static_start + num_statics;
        
        if (num_statics > 0  && num_statics < 100 && static_fields_checked.count(obj_static_start) == 0) {
          for (; tmp < end; tmp++) {              
            uint64_t val = *((uint64_t*)tmp);
            bool found = obj_node.has_field((void*)tmp);
            
            if (found) {
              num_found++;
            } else {
              if(val != 0) 
                num_not_found++;
            }
          }
          
          static_fields_checked.insert(obj_static_start);
        }
      }

      if(_check_object_fields && klass->is_instance_klass()) {
        InstanceKlass* ik = (InstanceKlass*)klass;
        do {
          char buf2[1024];
          char buf3[1024];

          for(int f = 0; f < ik->java_fields_count(); f++) {
            //Only go through non static and reference fields here.
            if (AccessFlags(ik->field_access_flags(f)).is_static()) continue;
            
            if (is_field_of_reference_type(ik, f)) {
              void* field_addr = (void*)(((uint64_t)(void*)obj) + ik->field_offset(f));
              oop actual_val = obj->obj_field(ik->field_offset(f));
              auto field_edge = obj_node.fields().find(ik->field_offset(f));
              bool found = field_edge != obj_node.fields().end();

              if (found) {
                num_found++;
                if (found && field_edge->second.val() != actual_val) {
                  if ((actual_val == NULL) || !actual_val->is_forwarded() || (actual_val != NULL && actual_val != INVALID_OOP && actual_val->is_forwarded() && field_edge->second.val() != actual_val->forwardee())) {
                    num_src_not_correct++;
                    if (num_src_not_correct < 100) {
                      char field_name[1024];
                      ik->field_name(f)->as_C_string(field_name, 1024);
                      char class_name[1024];
                      Universe::get_oop_klass_name(obj, class_name);
                      bool val_is_forwarded = (oopDesc*)field_edge->second.val() != NULL && ((oopDesc*)field_edge->second.val())->is_forwarded();
                      bool actual_is_forwarded = (oopDesc*)actual_val != NULL && ((oopDesc*)actual_val)->is_forwarded();
                      printf("Field '%s' (%p) of oop '%p' of class '%s'(size '%ld') not correct '%p' (is_forwarded() %d) != '%p' (is_forwarded() %d)\n", 
                              field_name, field_addr, (oopDesc*)obj, class_name, obj->size(), (oopDesc*)field_edge->second.val(), val_is_forwarded, (oopDesc*)actual_val, actual_is_forwarded);
                      if (actual_val != NULL) {
                        printf("actual val %s\n", Universe::get_oop_klass_name(actual_val, class_name));
                      }
                    }
                  }
                }
              } else {
                if (actual_val != NULL && actual_val != INVALID_OOP) {
                  char field_name[1024];
                  ik->field_name(f)->as_C_string(field_name, 1024);
                  char class_name[1024];
                  Universe::get_oop_klass_name(obj, class_name);
                  if (num_not_found < 100) { 
                    printf("Field '%s' ('%d':'%p') not found in oop '%p' of class '%s'\n", field_name, ik->field_offset(f), ((char*)(void*)obj + ik->field_offset(f)), (oopDesc*)obj, class_name);
                  }
                  if (num_not_found < 5) {
                    for (auto it = obj_node.fields().begin(); it != obj_node.fields().end(); it++) {
                      printf("off %d obj_off %ld\n", ik->field_offset(f), it->first);
                    } 
                  }
                  num_not_found++;
                }
              }
            }
          }
          
          if (_check_static_fields) {
            //TODO: Is this really needed? Wouldn't an InstanceMirrorOop will
            //be considered earlier?
        #if 0
            oop ikoop = ik->java_mirror();
            InstanceMirrorKlass* imk = (InstanceMirrorKlass*)ikoop->klass();
            auto ikoop_iter = ObjectNode::oop_to_obj_node.find(ikoop);

            if (java_lang_Class::static_oop_field_count(ikoop) > 0) {
              HeapWord* static_start = imk->start_of_static_fields(ikoop);
              HeapWord* p = static_start;
              if (static_fields_checked.count(static_start) == 0) {
                int static_field_to_field_index[ik->java_fields_count()];
                int static_field_number = 0;
                for(int f = 0; f < ik->java_fields_count(); f++) {
                  if (AccessFlags(ik->field_access_flags(f)).is_static()) {
                    Symbol* name = ik->field_name(f);
                    Symbol* signature = ik->field_signature(f);
                    char buf2[1024];

                    if (signature_to_type(signature->as_C_string(buf2,1024)) == T_OBJECT || 
                        signature_to_type(signature->as_C_string(buf2,1024)) == T_ARRAY) {
                      static_field_to_field_index[static_field_number++] = f;
                    }
                  }
                }

                HeapWord* end = p + java_lang_Class::static_oop_field_count(ikoop);
                if (static_field_number != java_lang_Class::static_oop_field_count(ikoop)) {
                  printf("not eq static_field_number %d %d\n", static_field_number, java_lang_Class::static_oop_field_count(ikoop));
                }
                
                for (int i = 0; p < end; p++, i++) {
                  int f = static_field_to_field_index[i];
                  if (!AccessFlags(ik->field_access_flags(f)).is_static()) printf("not static %d\n", i);
                  
                  num_statics_checked++;
                  uint64_t fd_address = (uint64_t)p;
                  uint64_t val = *((uint64_t*)fd_address);
                  
                  bool found = ikoop_iter != ObjectNode::oop_to_obj_node.end();
                  if (found) {
                    auto field_edge = ikoop_iter->second.fields().find((void*)fd_address); 
                    found = field_edge != ikoop_iter->second.fields().end();
                  }
                  
                  if (found) {
                    // field_src = (uint64_t)field_edge->second.val();
                    num_found++;
                  } else {
                    if(val != 0) num_not_found++;
                  }

                  num_fields++;

                  if (found) {num_found++;} else if (val != 0)
                  {
                    if (num_not_found < 100) { 
                      Symbol* name = ik->field_name(f);
                      Symbol* signature = ik->field_signature(f);
                      char buf2[1024];
                      char buf[1024];
                      char buf3[1024];
                      
                      if (signature_to_type(signature->as_C_string(buf2,1024)) == T_OBJECT || 
                          signature_to_type(signature->as_C_string(buf2,1024)) == T_ARRAY) {
                        printf("%p: %s.%s : %s 0x%lx\n", p, ik->name()->as_C_string(buf3, 1024), name->as_C_string(buf, 1024), buf2, val);
                      }
                    }
                    num_not_found++;
                  }

                  //TODO: Lazy.... Static value will probably be right.
                }

                static_fields_checked.insert(static_start);
              }
            }
          #endif
          }
          ik = ik->superklass();
        } while (ik && ik->is_klass());
      } else if (_check_array_elements && klass->id() == ObjArrayKlassID) {
        //Check each array element
        objArrayOop array = (objArrayOop)obj;
        int length = array->length();
        for (int i = 0; i < length; i++) {
          oop actual_elem = array->obj_at(i);
          void* elem_addr = (void*)(((uint64_t)array->base()) + i * sizeof(oop)); 
          auto field_edge = obj_node.fields().find(i * sizeof(oop)); 
          bool found = field_edge != obj_node.fields().end();

          if (found) {
            num_found++;
            if (field_edge->second.val() != actual_elem) {
              num_src_not_correct++;
              if (num_src_not_correct < 100) {
                printf("Elem at index '%d' in array '%p' of length '%d' is not correct: '%p' != '%p'\n", i, (oopDesc*)array, length, (void*)field_edge->second.val(), (oopDesc*)actual_elem);
              }
            }
          } else if (actual_elem != NULL && actual_elem != INVALID_OOP) {
            num_not_found++;
            if (num_not_found < 100) {
              printf("Elem at index '%d' of addr '%p' is not found in array '%p' of length '%d'\n", i, elem_addr, (oopDesc*)array, length);
              char name[1024];
              printf("elem type %s\n", Universe::get_oop_klass_name(actual_elem, name));
            }
          }
        }
      }

      valid = valid && num_not_found == 0;
    }
  }
};
const void* CheckGraph::INVALID_PTR = (void*)0xbaadbabebaadbabe;

const oop CheckGraph::INVALID_OOP = oop((oopDesc*)INVALID_PTR);

Universe::EventsToTransfer Universe::events_to_transfer;

void Universe::transfer_events_to_gpu_list_head() {
  Universe::HeapEvent* events = *all_heap_events.head()->data();
  events_to_transfer.length = *(uint64_t*)events;
  events_to_transfer.events = events + 1;
  sem_post(&cuda_semaphore);
  // printf("744: Transferring Events to GPU *cur_thread::heap_event_counter_ptr %ld\n", *(uint64_t*)events);
  *(uint64_t*)events = 0;
}

void Universe::transfer_events_to_gpu_no_zero(HeapEvent* events, size_t length) {
  events_to_transfer.length = length;
  events_to_transfer.events = events;
  // printf("580 %ld %p\n", length, events);
  sem_post(&cuda_semaphore);
}

void Universe::transfer_events_to_gpu() {
  Universe::HeapEvent* events = Universe::get_heap_events_ptr();
  events_to_transfer.length = *(uint64_t*)events;
  events_to_transfer.events = events + 1;
  sem_post(&cuda_semaphore);
  //fprintf(stderr, "T\n");
  *(uint64_t*)events = 0;
}

void Universe::verify_heap_graph_for_copy_array() {
  abort();
  // JavaThread* cur_thread = JavaThread::current();
  // if (*(uint64_t*)cur_thread->heap_events + 3 > MaxHeapEvents) {
  //   Universe::verify_heap_graph();
  // }
}

void Universe::lock_mutex_heap_event() {
  if (CheckHeapEventGraphWithHeap) {
    pthread_mutex_lock(&Universe::mutex_heap_event);
  } else {
    pthread_spin_lock(&spin_lock_heap_event);
  }
}

void Universe::unlock_mutex_heap_event() {
  if (CheckHeapEventGraphWithHeap) {
    pthread_mutex_unlock(&Universe::mutex_heap_event);
  } else {
    pthread_spin_unlock(&spin_lock_heap_event);
  }
}

void Universe::print_heap_event_counter() {
  // *Universe::heap_event_counter_ptr = 0; 
  HeapEvent* heap_events = Universe::get_heap_events_ptr();
  uint64_t* heap_event_counter_ptr = (uint64_t*)heap_events;

  printf("ctr %ld\n", *heap_event_counter_ptr);

  *heap_event_counter_ptr = 0;
}

void Universe::add_heap_events(Universe::HeapEventType event_type1, Universe::HeapEvent event1, 
                               Universe::HeapEventType event_type2, Universe::HeapEvent event2) {
  // JavaThread* cur_thread = JavaThread::current();
  // assert(cur_thread->heap_events, "");
  if (!InstrumentHeapEvents) return;
  // printf("sizeof Universe::heap_events %ld\n", sizeof(Universe::heap_events));
  if (CheckHeapEventGraphWithHeap)
    Universe::lock_mutex_heap_event();
  Universe::HeapEvent* heap_events = (CheckHeapEventGraphWithHeap) ? Universe::get_heap_events_ptr() : *all_heap_events.head()->data() ; //(Universe::HeapEvent*)(((char*)curr_thread) + 2048);
  uint64_t* heap_event_counter_ptr = (uint64_t*)heap_events;
  // Universe::heap_event_counter++;
  // if (event.src == 0x0) {
  //   printf("src 0x%lx dst 0x%lx\n", event.src, event.dst);
  // }
  #ifndef PRODUCT
  // if (all_heap_events.find(heap_events) == NULL) {
  //   printf("835: heap_events %p\n", heap_events);
  //   abort();
  // }
  #endif
  // if (*heap_event_counter_ptr + 2 > MaxHeapEvents) {
  //   if (CheckHeapEventGraphWithHeap)
  //     Universe::verify_heap_graph();
  //   else
  //     Universe::transfer_events_to_gpu();
  // }
  uint64_t v = *heap_event_counter_ptr;
  *heap_event_counter_ptr = v + 2;

  (&heap_events[1])[v] = encode_heap_event(event_type1, event1);
  (&heap_events[1])[1+v] = encode_heap_event(event_type2, event2);
  
  // if (event.heap_event_type == 0) {
  //   printf("new object at %ld\n");
  // }
  // if (*heap_event_counter_ptr >= MaxHeapEvents) {
  //   if (CheckHeapEventGraphWithHeap)
  //     Universe::verify_heap_graph();
  //   else
  //     Universe::transfer_events_to_gpu_list_head();
    
  // }
  if (CheckHeapEventGraphWithHeap)
    Universe::unlock_mutex_heap_event();
}

uint64_t Universe::heap_event_mask() {
  ShouldNotReachHere();
  return (~0L) >> (64-16);
}

uint64_t Universe::encode_heap_event_dst(HeapEventType event_type, uint64_t dst){
  if (event_type == Universe::FieldSet && event_type == 0) 
    return dst; 
  return ((uint64_t)event_type) | (dst << 15);
}

Universe::HeapEvent Universe::encode_heap_event(Universe::HeapEventType event_type, Universe::HeapEvent event) {
  return HeapEvent{event.src, encode_heap_event_dst(event_type, event.dst)};
}

Universe::HeapEventType Universe::decode_heap_event_type(Universe::HeapEvent event) {
  //Obtain the least significant 16-bits and return those as HeapEventType
  uint64_t mask = (1UL << 15) - 1;
  return (HeapEventType) (event.dst & mask);
}

uint64_t Universe::decode_heap_event_dst(HeapEvent event) {
  //Obtain the Most significant 48 bits as dest
  return (event.dst >> 15);
}

Universe::HeapEvent Universe::decode_heap_event(Universe::HeapEvent event) {
  return HeapEvent{event.src, decode_heap_event_dst(event)};
}

static pthread_mutex_t heap_events_list_lock = PTHREAD_MUTEX_INITIALIZER;

void Universe::copy_heap_events(Universe::HeapEvent* ptr) {  
  const size_t heap_events_size = *(const uint64_t*)ptr;
  if (heap_events_size == 0) return;
  pthread_mutex_lock(&heap_events_list_lock);
  printf("882: th_heap_events %p heap_events_size %ld\n", ptr, heap_events_size);
  Universe::HeapEvent* copy = (Universe::HeapEvent*)Universe::mmap(heap_events_size * sizeof(Universe::HeapEvent));
  memcpy(copy, ptr, heap_events_size*sizeof(Universe::HeapEvent));
  all_heap_events.add(copy);
  pthread_mutex_unlock(&heap_events_list_lock);
}

void Universe::add_heap_event_ptr(Universe::HeapEvent* ptr) {
  pthread_mutex_lock(&heap_events_list_lock);
  uint64_t second_part = ((uint64_t)(ptr + MaxHeapEvents)/4096)*4096;
  printf("703: 0x%lx\n", second_part);
  Universe::mprotect((void*)second_part, 4096, PROT_READ); //MaxHeapEvents*sizeof(Universe::HeapEvent)
  //memset last page to zeros
  memset((char*)second_part + MaxHeapEvents*sizeof(Universe::HeapEvent), 0, 4096);
  all_heap_events.add(ptr);
  pthread_mutex_unlock(&heap_events_list_lock);
}

void Universe::remove_heap_event_ptr(Universe::HeapEvent* ptr) {
  pthread_mutex_lock(&heap_events_list_lock);
  all_heap_events.remove(ptr);
  pthread_mutex_unlock(&heap_events_list_lock);
}

Universe::HeapEvent* Universe::get_heap_events_ptr() {
  JavaThread* curr_thread = JavaThread::current();
  
  Universe::HeapEvent* p = (Universe::HeapEvent*)((uint64_t)curr_thread + JavaThread::heap_events_offset());
  // printf("curr_thread %p sizeof(JavaThread) %ld p %p\n", curr_thread, sizeof(JavaThread), p);
  return p;
}

size_t Universe::heap_events_buf_size() {
  int PAGE_SIZE = 4096;
  size_t size = (MaxHeapEvents)*sizeof(Universe::HeapEvent)*2 + PAGE_SIZE + PAGE_SIZE;
  
  return size;
}

bool Universe::handle_heap_events_sigsegv(int sig, siginfo_t* info) {
  if (sig != SIGSEGV)
    return false;

  Universe::HeapEvent* sig_addr = (Universe::HeapEvent*)info->si_addr;
  if ((uint64_t)sig_addr < 0x10000)
    return false;

  HeapEvent* heap_events_ptr = Universe::get_heap_events_ptr();
  void* end_heap_events_buff = (char*)heap_events_ptr + heap_events_buf_size();
  if (heap_events_ptr < sig_addr && sig_addr <= end_heap_events_buff) {
    uint64_t second_part = ((uint64_t)(heap_events_ptr + MaxHeapEvents)/4096)*4096;
    if (second_part == (size_t)sig_addr) {
      //TODO: make a template mprotect
      //TODO: Only set the page starting at second_part to PROT_READ
      Universe::mprotect((void*)second_part, 4096, PROT_READ|PROT_WRITE);
  
      if (CheckHeapEventGraphWithHeap) {
        //Call verify_heap_graph when second part is filled
        // Universe::HeapEvent* last_event = (char*)second_part + MaxHeapEvents*sizeof(Universe::HeapEvent);
        // Universe::verify_heap_graph(last_event);
      }
      else {
        size_t length = (second_part - (uint64_t)heap_events_ptr)/sizeof(HeapEvent);
        Universe::transfer_events_to_gpu_no_zero(heap_events_ptr, length);
      }
      Universe::mprotect((char*)second_part + MaxHeapEvents*sizeof(Universe::HeapEvent), 4096, PROT_READ);
      // printf("759: changing permissions of %p\n", sig_addr);
      return true;
    } else if (second_part + MaxHeapEvents*sizeof(Universe::HeapEvent) == (size_t)sig_addr) {
      Universe::mprotect((char*)second_part + MaxHeapEvents*sizeof(Universe::HeapEvent), 4096, PROT_READ|PROT_WRITE);
      if (CheckHeapEventGraphWithHeap)
        Universe::verify_heap_graph();
      else {
        Universe::transfer_events_to_gpu_no_zero((HeapEvent*)second_part, MaxHeapEvents);
        *(uint64_t*)heap_events_ptr = 0;
        //fprintf(stderr, "T\n");
        // *(uint64_t*)events = 0;
      }
      Universe::mprotect((void*)second_part, 4096, PROT_READ);
      // printf("772: changing permissions of %p\n", sig_addr);
      return true;
    } else {
      return false;
    }
  }

  printf("738: sigaddr %p\n", sig_addr);
  for (auto heap_events_iter = LinkedListIterator<HeapEvent*>(all_heap_events.head()); 
       !heap_events_iter.is_empty(); heap_events_iter.next()) {
    void* end_heap_events_buff = (char*)*heap_events_iter + heap_events_buf_size();
    if (*heap_events_iter < sig_addr && sig_addr <= end_heap_events_buff) {
      //Found the address does it 
      uint64_t second_part = ((uint64_t)(*heap_events_iter + MaxHeapEvents)/4096)*4096;
      if (second_part == (size_t)sig_addr) {
        //TODO: make a template mprotect
        //TODO: Only set the page starting at second_part to PROT_READ
        Universe::mprotect((void*)second_part, MaxHeapEvents*sizeof(Universe::HeapEvent), PROT_READ|PROT_WRITE);
        if (CheckHeapEventGraphWithHeap) {
          //Call verify_heap_graph when second part is filled
          // Universe::HeapEvent* last_event = (char*)second_part + MaxHeapEvents*sizeof(Universe::HeapEvent);
          // Universe::verify_heap_graph(last_event);
        }
        else {
          size_t length = (second_part - (uint64_t)*heap_events_iter)/sizeof(HeapEvent);
          Universe::transfer_events_to_gpu_no_zero(*heap_events_iter, length);
        }
        Universe::mprotect((char*)second_part + MaxHeapEvents*sizeof(Universe::HeapEvent), 4096, PROT_READ);
        printf("744 changing permissions of %p\n", sig_addr);
        return true;
      } else if (second_part + MaxHeapEvents*sizeof(Universe::HeapEvent) == (size_t)sig_addr) {
        Universe::mprotect((char*)second_part + MaxHeapEvents*sizeof(Universe::HeapEvent), 4096, PROT_READ|PROT_WRITE);
        if (CheckHeapEventGraphWithHeap)
          Universe::verify_heap_graph();
        else {
          Universe::transfer_events_to_gpu_no_zero((HeapEvent*)second_part, MaxHeapEvents);
          *(uint64_t*)*heap_events_iter = 0;
          //fprintf(stderr, "T\n");
          // *(uint64_t*)events = 0;
        }
        Universe::mprotect((void*)second_part, MaxHeapEvents*sizeof(Universe::HeapEvent), PROT_READ);
        printf("749 changing permissions of %p\n", sig_addr);
        return true;
      } else {
        return false;
      }
    }
  }

  return false;
}

template<typename Map>
oopDesc* oop_for_address(Map& oop_map, oopDesc* field) {
  auto next_obj_iter = oop_map.lower_bound(field);
  oopDesc* obj = NULL;
  if (next_obj_iter == oop_map.begin()) {
    printf("870 %p %p for %p\n", next_obj_iter->first, next_obj_iter->second.end(), field);
  }
  if (next_obj_iter != oop_map.end() && next_obj_iter->first == field) {
    obj = next_obj_iter->first;
  } else {
    auto obj_iter = --next_obj_iter;
    if (obj_iter->first <= field && field < obj_iter->second.end()) {
      obj = obj_iter->first;
    }
  }

  return obj;
}

bool Universe::is_verify_cause_full_gc = false;
bool Universe::is_verify_from_exit = false;
bool Universe::is_verify_from_gc = false;
bool Universe::is_verify_from_full_gc_start = false;

void Universe::process_heap_event(Universe::HeapEvent event) {
  HeapEventType heap_event_type = decode_heap_event_type(event);      
  if (heap_event_type == Universe::NewObject || 
      heap_event_type == Universe::NewArray || 
      heap_event_type == Universe::NewPrimitiveArray || 
      heap_event_type == Universe::NewObjectSizeInBits) {
    HeapEvent event2 = decode_heap_event(event);
    oopDesc* obj = (oopDesc*)event2.dst;
    
    auto obj_src_node_iter = ObjectNode::oop_to_obj_node.find(obj);
    if (obj_src_node_iter != ObjectNode::oop_to_obj_node.end()) {
      printf("858: Replacing %p from old size %ld event %ld to new size %ld event %ld\n", obj, obj_src_node_iter->second.size(), obj_src_node_iter->second.type(), event2.src, heap_event_type);
      ObjectNode::oop_to_obj_node.erase(obj_src_node_iter);
    }

    if (heap_event_type == Universe::NewObjectSizeInBits) {
      event2.src = event2.src/8;
      heap_event_type = Universe::NewObject;
    }
    ObjectNode obj_node = ObjectNode(obj, event2.src, heap_event_type, 0);
    ObjectNode::oop_to_obj_node.emplace(obj, obj_node);
  }
}

bool is_field_set(Universe::HeapEvent event, const void* heap_start, const void* heap_end) {
  bool is_src_in_heap = ((uint64_t)heap_start) <= event.src && event.src < ((uint64_t)heap_end);
  bool is_dst_in_heap = ((uint64_t)heap_start) <= event.dst && event.dst < ((uint64_t)heap_end);
  if ((event.src == 0 || is_src_in_heap) and is_dst_in_heap)
    return true;
  
  return false;
}

bool Universe::is_curr_Java_thread() {
  return Thread::current()->is_Java_thread();
}

static char* jstr_to_utf(oop str, char utfstr[]) {
  int len = java_lang_String::utf8_length(str);
  typeArrayOop s_value = java_lang_String::value(str);
  if (s_value != NULL) {
    size_t length = java_lang_String::utf8_length(str, s_value);
    java_lang_String::as_utf8_string(str, s_value, utfstr, (int) length + 1);
  }

  return utfstr;
}

class GetRoots : public BasicOopIterateClosure {
public:
  Universe::vector<oop> root_oops;
  virtual void do_oop(oop* p) {root_oops.push_back(RawAccess<>::oop_load(p));}
  virtual void do_oop(narrowOop* p) {abort();}
};

class GetCLDRoots : public CLDClosure {
 public:
  GetRoots* get_roots_;
  GetCLDRoots(GetRoots* get_roots) : get_roots_(get_roots) {}
  virtual void do_cld(ClassLoaderData* cld) {
    cld->oops_do(get_roots_, ClassLoaderData::_claim_none, /*clear_modified_oops*/false);
  };
};

class GetCodeBlobRoots : public CodeBlobToOopClosure {
 public:
  GetCodeBlobRoots(OopClosure* cl) : CodeBlobToOopClosure(cl, false) {}
  // Called for each code blob, but at most once per unique blob.

  virtual void do_code_blob(CodeBlob* cb) {
    nmethod* nm = cb->as_nmethod_or_null();
    if (nm != NULL) {
      do_nmethod(nm);
    }
  }
};

class GetFieldsClosure: public OopIterateClosure {
public:
  Universe::vector<oop*> field_addr;
  virtual void do_oop(oop* p) {
    field_addr.push_back(p);
  }
  virtual void do_oop(narrowOop* p) {
  
  }

  virtual bool do_metadata() { return true; }
  virtual void do_klass(Klass* k) {

  }
  virtual void do_cld(ClassLoaderData* cld) {

  }
};

class CheckMarkedObjects : public ObjectClosure {
  // Universe::unordered_set<void*>& marked_objects_;
  
public:
  int num_obj_marked;
  int num_obj_unmarked;
  const int max_unmarked_to_print = 100;
  CheckMarkedObjects() : num_obj_marked(0), num_obj_unmarked(0) {
  }
  
  virtual void do_object(oop obj) {
    if (obj != NULL && obj != CheckGraph::INVALID_OOP && obj->mark().is_marked()) {
      if (Universe::marked_objects.count((void*)obj) == 0) {
        num_obj_unmarked++;
        if (num_obj_unmarked < max_unmarked_to_print) {
          char buf[10240];

          printf("Object not marked %p of class %s\n", (void*)obj, Universe::get_oop_klass_name(obj, buf));
          if (strcmp(buf,"java/lang/String") == 0) {
            jstr_to_utf(obj, buf);
            printf("String is %s\n", buf);
          }
        }

        if (ObjectNode::oop_to_obj_node.count((oopDesc*)obj) == 0) {
          printf("985: Not present\n");
        }
      } else {
        num_obj_marked++;
      }
    }
  }
};

bool Universe::is_verify_from_young_gc_start = 0;
void Universe::check_marked_objects() {
  // assert(marked_objects.size() > 0, "sanity");

  CheckMarkedObjects checkMarkedObjects;
  Universe::heap()->object_iterate(&checkMarkedObjects);

  printf("Objects Marked %d Objects Not Marked: %d Extra Objects Marked: %ld\n", checkMarkedObjects.num_obj_marked, checkMarkedObjects.num_obj_unmarked, marked_objects.size() - checkMarkedObjects.num_obj_marked);
}

void Universe::mark_objects(Universe::unordered_set<void*>& visited) {
  GetRoots get_roots;
  GetCLDRoots cld_closure(&get_roots);
  ClassLoaderDataGraph::roots_cld_do(&cld_closure, &cld_closure);
  
  GetCodeBlobRoots code_blobs_roots(&get_roots);
  Threads::oops_do(&get_roots, &code_blobs_roots);
  
  OopStorageSet::strong_oops_do(&get_roots);
  
  printf("roots %ld\n", get_roots.root_oops.size());

  //Mark reachable objects
  Universe::deque<oop> bfs_queue;
  for (auto& root : get_roots.root_oops) {
    bfs_queue.push_back(root);
    while (!bfs_queue.empty()) {
      oop obj = bfs_queue.front();
      bfs_queue.pop_front();
      if (visited.count((void*)obj) > 0)
        continue;
      if (obj == NULL) continue;
      visited.insert((void*)obj);
      if (false) {
        // GetFieldsClosure get_fields;
        // obj->oop_iterate(&get_fields);
        // ObjectNode objNode = ObjectNode::oop_to_obj_node[(oopDesc*)obj];
        // for (auto field : get_fields.field_addr) {
        //   oop heap_oop = RawAccess<>::oop_load(field);
        //   if (heap_oop != NULL) {
        //     if (!objNode.has_field(field)) {
        //       char buf[1024];
        //       char buf2[1024];
        //       printf("1052: Field '%p'(oop '%s') not found in obj '%p' of class '%s'\n", field, get_oop_klass_name(heap_oop, buf2), (void*)obj, get_oop_klass_name(obj, buf));
        //     } else if (objNode.field_val(field) != heap_oop) {
        //       char buf[1024];
        //       char buf2[1024];
        //       printf("1061: Value of field '%p'(oop '%s') is not correct in obj '%p' of class '%s'\n", field, get_oop_klass_name(heap_oop, buf2), (void*)obj, get_oop_klass_name(obj, buf));
        //     }
        //     bfs_queue.push_back(heap_oop);
        //   }
        // }
      } else if (true) {
        ObjectNode objNode = ObjectNode::oop_to_obj_node[(oopDesc*)obj];
        for (auto field : objNode.fields()) {
          if ((void*)field.second.val() != NULL) {
            bfs_queue.push_back(field.second.val());
          }
        }
      } else {
        // if (obj != NULL && obj->is_instance()) {
        //   if(obj->klass() && obj->klass()->is_klass()) {
        //     ObjectNode objNode = ObjectNode::oop_to_obj_node[(oopDesc*)obj];
        //     InstanceKlass* ik = (InstanceKlass*)obj->klass();
        //     do {
        //       for(int f = 0; f < ik->java_fields_count(); f++) {
        //         //Only go through non static and reference fields here.
        //         if (AccessFlags(ik->field_access_flags(f)).is_static()) continue;
                
        //         if (is_field_of_reference_type(ik, f)) {
        //           oop* field_addr = (oop*)(((uint64_t)(void*)obj) + ik->field_offset(f));
        //           bfs_queue.push_back(RawAccess<>::oop_load(field_addr));
        //         }
        //       }
        //       ik = ik->superklass();
        //     } while (ik && ik->is_klass());
        //   }
        // } else if (obj->is_objArray()) {
        //   objArrayOop array = (objArrayOop)obj;
        //   ObjectNode obj_node = ObjectNode::oop_to_obj_node[(oopDesc*)obj];
        //   int length = array->length();
        //   for (int i = 0; i < length; i++) {
        //     oop actual_elem = array->obj_at(i);
        //     bfs_queue.push_back(actual_elem);
        //   }
        // }
      }
    }
  }

  // #0  MarkSweep::FollowRootClosure::do_oop (this=0x7ffff71564c0 <MarkSweep::follow_root_closure>, p=0x7fffec09bec0) at /mnt/homes/aabhinav/jdk/src/hotspot/share/gc/serial/markSweep.cpp:145
  // #1  0x00007ffff5a988d6 in OopStorage::OopFn<OopClosure>::operator()<oop*> (ptr=<optimized out>, this=<synthetic pointer>) at /mnt/homes/aabhinav/jdk/src/hotspot/share/gc/shared/oopStorage.inline.hpp:240
  // #2  OopStorage::Block::iterate_impl<OopStorage::OopFn<OopClosure>, OopStorage::Block*> (block=0x7fffec09bec0, f=...) at /mnt/homes/aabhinav/jdk/src/hotspot/share/gc/shared/oopStorage.inline.hpp:337
  // #3  OopStorage::Block::iterate<OopStorage::OopFn<OopClosure> > (f=..., this=0x7fffec09bec0) at /mnt/homes/aabhinav/jdk/src/hotspot/share/gc/shared/oopStorage.inline.hpp:346
  // #4  OopStorage::iterate_impl<OopStorage::OopFn<OopClosure>, OopStorage> (storage=<optimized out>, f=...) at /mnt/homes/aabhinav/jdk/src/hotspot/share/gc/shared/oopStorage.inline.hpp:369
  // #5  OopStorage::iterate_safepoint<OopStorage::OopFn<OopClosure> > (f=..., this=<optimized out>) at /mnt/homes/aabhinav/jdk/src/hotspot/share/gc/shared/oopStorage.inline.hpp:378
  // #6  OopStorage::oops_do<OopClosure> (cl=0x7ffff71564c0 <MarkSweep::follow_root_closure>, this=<optimized out>) at /mnt/homes/aabhinav/jdk/src/hotspot/share/gc/shared/oopStorage.inline.hpp:388
  // #7  OopStorageSet::strong_oops_do<OopClosure> (cl=cl@entry=0x7ffff71564c0 <MarkSweep::follow_root_closure>) at /mnt/homes/aabhinav/jdk/src/hotspot/share/gc/shared/oopStorageSet.inline.hpp:36
  // #8  0x00007ffff5a963fe in GenCollectedHeap::process_roots (code_roots=0x7ffff01a3c10, weak_cld_closure=<optimized out>, strong_cld_closure=<optimized out>, strong_roots=0x7ffff71564c0 <MarkSweep::follow_root_closure>, so=GenCollectedHeap::SO_None, this=0x0) at /mnt/homes/aabhinav/jdk/src/hotspot/share/gc/shared/genCollectedHeap.cpp:796
  // #9  GenCollectedHeap::full_process_roots (this=this@entry=0x7fffec048120, is_adjust_phase=is_adjust_phase@entry=false, so=so@entry=GenCollectedHeap::SO_None, only_strong_roots=<optimized out>, root_closure=0x7ffff71564c0 <MarkSweep::follow_root_closure>, cld_closure=<optimized out>) at /mnt/homes/aabhinav/jdk/src/hotspot/share/gc/shared/genCollectedHeap.cpp:825
  // #10 0x00007ffff5a99a60 in GenMarkSweep::mark_sweep_phase1 (clear_all_softrefs=clear_all_softrefs@entry=false) at /mnt/homes/aabhinav/jdk/src/hotspot/share/gc/serial/genMarkSweep.cpp:189
  // #11 0x00007ffff5a9b059 in GenMarkSweep::invoke_at_safepoint (rp=<optimized out>, clear_all_softrefs=<optimized out>) at /mnt/homes/aabhinav/jdk/src/hotspot/share/gc/serial/genMarkSweep.cpp:93
  // #12 0x00007ffff67328e9 in TenuredGeneration::collect (this=0x7fffec0518e0, full=<optimized out>, clear_all_soft_refs=<optimized out>, size=<optimized out>, is_tlab=<optimized out>) at /mnt/homes/aabhinav/jdk/src/hotspot/share/gc/shared/generation.hpp:414
  // #13 0x00007ffff5a95ddc in GenCollectedHeap::collect_generation (this=this@entry=0x7fffec048120, gen=0x7fffec0518e0, full=full@entry=false, size=size@entry=3, is_tlab=is_tlab@entry=false, run_verification=<optimized out>, clear_soft_refs=false) at /mnt/homes/aabhinav/jdk/src/hotspot/share/gc/shared/genCollectedHeap.cpp:483
  // #14 0x00007ffff5a96ee3 in GenCollectedHeap::do_collection (this=this@entry=0x7fffec048120, full=full@entry=false, clear_all_soft_refs=clear_all_soft_refs@entry=false, size=<optimized out>, size@entry=3, is_tlab=is_tlab@entry=false, max_generation=max_generation@entry=GenCollectedHeap::OldGen) at /mnt/homes/aabhinav/jdk/src/hotspot/share/gc/shared/genCollectedHeap.cpp:628
  // #15 0x00007ffff5a97ca9 in GenCollectedHeap::satisfy_failed_allocation (this=this@entry=0x7fffec048120, size=3, is_tlab=<optimized out>) at /mnt/homes/aabhinav/jdk/src/hotspot/share/gc/shared/genCollectedHeap.cpp:708
  // #16 0x00007ffff5a7ef62 in VM_GenCollectForAllocation::doit (this=0x7ffff7fc7aa0) at /mnt/homes/aabhinav/jdk/src/hotspot/share/gc/shared/gcVMOperations.cpp:178
  // #17 0x00007ffff684435a in VM_Operation::evaluate (this=this@entry=0x7ffff7fc7aa0) at /mnt/homes/aabhinav/jdk/src/hotspot/share/runtime/vmOperations.cpp:70
  // #18 0x00007ffff6867973 in VMThread::evaluate_operation (this=this@entry=0x7fff57ffd030, op=0x7ffff7fc7aa0) at /mnt/homes/aabhinav/jdk/src/hotspot/share/runtime/vmThread.cpp:282
  // #19 0x00007ffff68687d7 in VMThread::inner_execute (this=this@entry=0x7fff57ffd030, op=<optimized out>) at /mnt/homes/aabhinav/jdk/src/hotspot/share/runtime/vmThread.cpp:429
  // #20 0x00007ffff6868915 in VMThread::loop (this=this@entry=0x7fff57ffd030) at /mnt/homes/aabhinav/jdk/src/hotspot/share/runtime/vmThread.cpp:496
  // #21 0x00007ffff6868a34 in VMThread::run (this=0x7fff57ffd030) at /mnt/homes/aabhinav/jdk/src/hotspot/share/runtime/vmThread.cpp:175
  // #22 0x00007ffff67422b0 in Thread::call_run (this=0x7fff57ffd030) at /mnt/homes/aabhinav/jdk/src/hotspot/share/runtime/thread.cpp:384
  // #23 0x00007ffff62feac4 in thread_native_entry (thread=0x7fff57ffd030) at /mnt/homes/aabhinav/jdk/src/hotspot/os/linux/os_linux.cpp:705
  // #24 0x00007ffff719a6db in start_thread (arg=0x7ffff01a5700) at pthread_create.c:463
  // #25 0x00007ffff78f461f in clone () at ../sysdeps/unix/sysv/linux/x86_64/clone.S:95
}

Universe::unordered_set<void*> Universe::marked_objects;

void Universe::verify_heap_graph() {
  // if (*Universe::heap_event_counter_ptr < MaxHeapEvents)
  //   return;
  const int LOG_MAX_OBJ_SIZE = 16;
  static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;  

  if (!CheckHeapEventGraphWithHeap)
    return;
  
  // heap()->print();

  if (Universe::is_verify_from_gc) {
    printf("GC from thread %p\n", Thread::current_or_null());
  }
  // pthread_mutex_lock(&lock);
  size_t num_events_created = 0;
  checking++;
  // printf("checking %d %ld tid %ld\n", checking++, Universe::heap_event_counter, gettid()); 

  int max_prints=0;
  int zero_event_types = 0;
  //Update heap hash table
  unordered_set<uint64_t> event_threads;
  printf("Check Shadow Graph is_verify_cause_full_gc %d is_verify_from_exit %d is_verify_from_full_gc_start %d\n", 
         is_verify_cause_full_gc, is_verify_from_exit, is_verify_from_full_gc_start);
  uint64_t num_field_sets = 0;
  uint64_t num_new_obj = 0;

  map<oopDesc*, oopDesc*> old_to_new_objects;
  printf("Creating Nodes\n");
  const void* heap_start = ((GenCollectedHeap*)Universe::heap())->base();
  const void* heap_end = ((GenCollectedHeap*)Universe::heap())->end();
  printf("Heap top address %p end address %p\n", heap_start, heap_end);
  //Update heap events counter to include heap events stored in the last page of second part
  if (!Universe::is_verify_from_exit) {
    for (auto heap_events_iter = LinkedListIterator<HeapEvent*>(all_heap_events.head()); 
        !heap_events_iter.is_empty(); heap_events_iter.next()) {
      auto th_heap_events = *heap_events_iter;
      const size_t heap_events_size = *(const uint64_t*)th_heap_events;
      if (heap_events_size < MaxHeapEvents)
        continue;
      
      uint64_t second_part = ((uint64_t)(*heap_events_iter + MaxHeapEvents)/4096)*4096;
      HeapEvent* last_page = (HeapEvent*)second_part + MaxHeapEvents;

      printf("New heap events %p from %ld to %ld\n", th_heap_events, heap_events_size, heap_events_size + 4096/sizeof(HeapEvent));
      *(uint64_t*)th_heap_events += 4096/sizeof(HeapEvent);
    }
  }

  Universe::HeapEvent* reverse_events[all_heap_events.size()];
  auto heap_events_iter = LinkedListIterator<HeapEvent*>(all_heap_events.head());

  for (uint i = 0; i < all_heap_events.size(); i++) {
    reverse_events[all_heap_events.size() - i - 1] = *heap_events_iter;
    heap_events_iter.next();
  }

  if (CreateGPUHeapEventGraph) {
    //ask cuda thread to do its work
    sem_post(&Universe::cuda_semaphore);
    //wait for thread to complete transfers
    sem_wait(&Universe::cuda_thread_wait_semaphore);
  }

  for (auto heap_events_iter = LinkedListIterator<HeapEvent*>(all_heap_events.head()); 
       !heap_events_iter.is_empty(); heap_events_iter.next()) {
    auto th_heap_events = *heap_events_iter;
    const size_t heap_events_size = *(const uint64_t*)th_heap_events;
    HeapEvent* heap_events_start = &th_heap_events[1];
    // uint64_t second_part = ((uint64_t)(th_heap_events + MaxHeapEvents)/4096)*4096;
    // uint64_t first_part_max_events = (second_part - (uint64_t)th_heap_events)/sizeof(Universe::MaxHeapEvents);

    // if (heap_events_size >= MaxHeapEvents) {
    //   heap_events_start += MaxHeapEvents;

    // }
    printf("heap_events_size %ld %p\n", heap_events_size, th_heap_events);
    
    for (uint64_t event_iter = 0; event_iter < heap_events_size; event_iter++) {
      HeapEvent event = heap_events_start[event_iter];
      if(is_field_set(event, heap_start, heap_end)) continue;
      HeapEventType heap_event_type = decode_heap_event_type(event);
      HeapEvent event2 = decode_heap_event(event);
      if (heap_event_type == Universe::NewObject || 
          heap_event_type == Universe::NewArray || 
          heap_event_type == Universe::NewPrimitiveArray ||
          heap_event_type == Universe::NewObjectSizeInBits ||
          heap_event_type == Universe::FieldSetWithNewObject ||
          heap_event_type == Universe::CopyNewObject || 
          heap_event_type == Universe::CopyNewArray ||
          heap_event_type == Universe::CopyNewArrayOfSameLength) {
        if (heap_event_type == Universe::NewObjectSizeInBits) {
          event2.src = event2.src/8;
          heap_event_type = Universe::NewObject;
        } else if (heap_event_type == Universe::FieldSetWithNewObject) {
          HeapEvent event = heap_events_start[event_iter+1];
          uint64_t sz = (event.src % 8 == 0) ? event.src/8 : event.src;
          event2 = {sz, event2.src};
          heap_event_type = Universe::NewObject;
          event_iter++;
        } else if (heap_event_type == Universe::CopyNewObject) {
          event2.src = (event2.src & ((1UL << LOG_MAX_OBJ_SIZE) - 1))/8;
          heap_event_type = Universe::NewObject;
        } else if (heap_event_type == Universe::CopyNewArray || heap_event_type == Universe::CopyNewArrayOfSameLength) {
          heap_event_type = Universe::NewArray;
          event_iter++;
        }

        oopDesc* obj = (oopDesc*)event2.dst;
        
        auto obj_src_node_iter = ObjectNode::oop_to_obj_node.find(obj);
        if (obj_src_node_iter != ObjectNode::oop_to_obj_node.end()) {
          // char class_name[1024];
          // get_oop_klass_name(oop(obj), class_name);
          // printf("858: Replacing %p ('%s') from old size %ld event %ld to new size %ld event %ld event_iter %ld\n", obj, class_name, obj_src_node_iter->second.size(), obj_src_node_iter->second.type(), event2.src, heap_event_type, event_iter);
          ObjectNode::oop_to_obj_node.erase(obj_src_node_iter);
        }

        ObjectNode obj_node = ObjectNode(obj, event2.src, heap_event_type, 0);
        ObjectNode::oop_to_obj_node.emplace(obj, obj_node);
      } else if (heap_event_type == Universe::CopyArray) {
        event_iter++;
      }
    }
  }

  printf("Nodes created: %ld\nCreating Edges\n", ObjectNode::oop_to_obj_node.size());
  // printf("newobj %ld fieldsets %ld\n", num_new_obj, num_field_sets);
  //Go through events in the order they are created
  bool CheckSameFieldSetsBetweenThreads = true;
  if (CheckSameFieldSetsBetweenThreads) {
    Universe::unordered_set<uint64_t> field_set_in_thread[16];
    for (uint i = 0; i < all_heap_events.size(); i++) {
      auto th_heap_events = reverse_events[i];
      HeapEvent* heap_events_start = &th_heap_events[1];
      const size_t heap_events_size = *(const uint64_t*)th_heap_events;
      for (uint64_t event_iter = 0; event_iter < heap_events_size; event_iter++) {
        HeapEvent event = heap_events_start[event_iter];
        if (is_field_set(event, heap_start, heap_end)) {
          field_set_in_thread[i].insert(event.dst);
        } else if (decode_heap_event_type(event) == Universe::FieldSet) {
          event = decode_heap_event(event);
          field_set_in_thread[i].insert(event.dst);
        }
      }
    }

    for (uint i = 0; i < all_heap_events.size(); i++) {
      Universe::unordered_set<uint64_t>& first = field_set_in_thread[i];
      if (first.size() == 0) continue;
      for (uint j = 0; j < all_heap_events.size(); j++) {
        Universe::unordered_set<uint64_t>& second = field_set_in_thread[j];
        if (second.size() == 0 || i == j) continue;
        Universe::unordered_set<uint64_t> result;
        for (auto first_elem : first) {
          if (second.count(first_elem) > 0)
            result.insert(first_elem);
        }
        printf("'%ld' common field set between %p (%ld) and %p (%ld)\n", result.size(), 
               reverse_events[i], first.size(), reverse_events[j], second.size());
      }
    }
  }

  for (uint i = 0; i < all_heap_events.size(); i++) {
    auto th_heap_events = reverse_events[i];
    const size_t heap_events_size = *(const uint64_t*)th_heap_events;
    // printf("heap_events_size %ld %p %p\n", heap_events_size, th_heap_events, get_heap_events_ptr());
    *(uint64_t*)th_heap_events = 0;
    HeapEvent* heap_events_start = &th_heap_events[1];
    HeapEvent prevEvent = {0,0};
    bool PrintHeapEventStatistics = true;
    for (uint64_t event_iter = 0; event_iter < heap_events_size; event_iter++) {
      HeapEvent event = heap_events_start[event_iter];
      if (event.dst == 0)
        continue;
      ((HeapEvent*)heap_events_start)[event_iter] = HeapEvent{0,0};
      HeapEventType heap_event_type;
      if(is_field_set(event, heap_start, heap_end)) {
        heap_event_type = Universe::FieldSet;
      } else {
        heap_event_type = decode_heap_event_type(event);
        event = decode_heap_event(event);
      }
      
      if (heap_event_type == Universe::NewObject ||
          heap_event_type == Universe::NewArray ||
          heap_event_type == Universe::NewPrimitiveArray ||
          heap_event_type == Universe::NewObjectSizeInBits) {
            continue;
        // oopDesc* obj = (oopDesc*)event.dst;
        // if (ObjectNode::oop_to_obj_node.find(obj) == ObjectNode::oop_to_obj_node.end()) {
        //   ObjectNode::oop_to_obj_node.emplace(obj, ObjectNode(obj, event.src,
        //                                            heap_event_type, 0));
        // }
      } else if (heap_event_type == Universe::FieldSet || heap_event_type == Universe::FieldSetWithNewObject) {
        Universe::HeapEvent field_set_events[2];
        if (heap_event_type == Universe::FieldSet) {
          field_set_events[0] = event;
        } else if (heap_event_type == Universe::FieldSetWithNewObject) {
          field_set_events[0] = {heap_events_start[event_iter+1].dst, event.dst};
          event_iter += 1;
        }

        for (int e = 0; e < 1; e++) {
          Universe::HeapEvent event = field_set_events[e];
          if (event.dst == 0) continue;
          oopDesc* field = (oopDesc*)event.dst;
          oop obj = oop_for_address(ObjectNode::oop_to_obj_node, field);
          if (obj == NULL) {
            // printf("heap_event %p size %ld iter %ld\n", th_heap_events, heap_events_size, event_iter);
            auto next_obj_iter = ObjectNode::oop_to_obj_node.lower_bound(field);
            oopDesc* obj = NULL;
            if (next_obj_iter != ObjectNode::oop_to_obj_node.end() && next_obj_iter->first == field) {
              obj = next_obj_iter->first;
            } else {
              printf("Obj is NULL for %p e %d type %s\n", field, e, heapEventTypeString(heap_event_type));
              auto obj_iter = --next_obj_iter;
              if (obj_iter->first != NULL || obj_iter->first != CheckGraph::INVALID_OOP) {
                char buf[1024];
                printf("start %p end %ld %ld t %ld %s\n", obj_iter->first, obj_iter->second.size(), obj_iter->first->size(), 
                obj_iter->second.type(), Universe::get_oop_klass_name(obj_iter->first, buf));
              }
              if (obj_iter->first <= field && field < obj_iter->second.end()) {
                obj = obj_iter->first;
              }
            }
          } else {
            // if (checking==3 && obj != NULL) {
            //   char klass_name[1024];
            //   void* curr_val = NULL;
            //   if (ObjectNode::oop_to_obj_node[obj].has_field(field)) curr_val = ObjectNode::oop_to_obj_node[obj].field_val(field);
            //   Universe::get_oop_klass_name(obj, klass_name);
            //   if (strcmp(klass_name, "java/lang/invoke/MethodType$ConcurrentWeakInternSet$WeakEntry") == 0) {
            //     printf("(%p) setting %p to %p from %p : heap_event_type %ld\n", th_heap_events, field, (oopDesc*)event.src, curr_val, heap_event_type);
            //   }
            // }
            // if (PrintHeapEventStatistics) {
            //   char buf[1024];
            //   if (prevEvent.dst == event.dst && heap_event_type == Universe::FieldSet) {
            //     printf("Prev event {0x%lx, 0x%lx} == curr event {0x%lx, 0x%lx} at %ld. oop '%s'\n", prevEvent.src, prevEvent.dst, event.src, event.dst, event_iter, get_oop_klass_name(obj, buf));
            //   }

            //   prevEvent = event;
            // }
            // if(checking == 3) printf("FieldSet (%p) of oop %p to %p in %p\n", field, (oopDesc*)obj, (oopDesc*)event.src, th_heap_events);
            ObjectNode::oop_to_obj_node[obj].update_or_add_field((void*)field, (oopDesc*)event.src, 
                                                                0);
          }
        }

        // if (heap_event_type == FieldSet && 0 != 0) {
        //   printf("0 0x%lx\n", 0);
        // }
      } else if (heap_event_type == Universe::CopyObject || heap_event_type == Universe::CopyNewObject) {
        if (heap_event_type == Universe::CopyNewObject) {
          event.src = event.src >> LOG_MAX_OBJ_SIZE;
        }
        oop obj_src = oop((oopDesc*)event.src);
        oop obj_dst = oop((oopDesc*)event.dst);
        auto obj_src_node_iter = ObjectNode::oop_to_obj_node.find(obj_src);
        auto obj_dst_node_iter = ObjectNode::oop_to_obj_node.find(obj_dst);
        
        if (obj_dst_node_iter == ObjectNode::oop_to_obj_node.end() ||
            obj_src_node_iter == ObjectNode::oop_to_obj_node.end()) {
            printf("didn't find %p %p\n", (void*)obj_src, (void*)obj_dst);
            continue;
        }

        if (obj_dst_node_iter != ObjectNode::oop_to_obj_node.end() && 
          obj_src_node_iter != ObjectNode::oop_to_obj_node.end()) {
          InstanceKlass* ik = (InstanceKlass*)obj_src->klass();
          do {
            char buf2[1024];
            char buf3[1024];

            for(int f = 0; f < ik->java_fields_count(); f++) {
              if (AccessFlags(ik->field_access_flags(f)).is_static()) continue;
              Symbol* name = ik->field_name(f);
              Symbol* signature = ik->field_signature(f);
              
              if (signature_to_type(signature->as_C_string(buf2,1024)) == T_OBJECT || signature_to_type(signature->as_C_string(buf2,1024)) == T_ARRAY) {
                uint64_t dst_obj_field_offset = event.dst + ik->field_offset(f);
                uint64_t src_obj_field_offset = event.src + ik->field_offset(f);

                if (obj_src_node_iter->second.has_field((void*)src_obj_field_offset)) {
                  oop src_field_val = obj_src_node_iter->second.field_val((void*)src_obj_field_offset);
                  obj_dst_node_iter->second.update_or_add_field((void*)dst_obj_field_offset, 
                  src_field_val, 0);
                }
              }
            }
            ik = ik->superklass();
          } while(ik && ik->is_klass());
        }
      } else if (heap_event_type == Universe::CopyArray || heap_event_type == Universe::CopySameArray ||
                 heap_event_type == Universe::CopyNewArray || heap_event_type == Universe::CopyNewArrayOfSameLength) {
        oopDesc* obj_src_start = (oopDesc*)event.src;
        oopDesc* obj_dst_start = (oopDesc*)event.dst;
        HeapEvent length_event;
        HeapEvent offsets;
        
        objArrayOop obj_src;
        objArrayOop obj_dst;
        obj_dst = (objArrayOop)oop_for_address(ObjectNode::oop_to_obj_node, obj_dst_start);
        if (obj_dst == NULL)
          printf("1137: Didn't find dst '%p'\n", obj_dst_start);

        if (heap_event_type == Universe::CopyArray) {
          length_event = heap_events_start[event_iter+1];
          event_iter = event_iter + 1;

          obj_src = (objArrayOop)oop_for_address(ObjectNode::oop_to_obj_node, obj_src_start);

          if (obj_src == NULL) {
            printf("1144: Didn't find src '%p'\n", obj_src_start);
          }
          //No need to consider objArrayOop::base() in offset calculation
          offsets = {(uint64_t)obj_src_start - (uint64_t)(oopDesc*)obj_src,
                     (uint64_t)obj_dst_start - (uint64_t)(oopDesc*)obj_dst};
        } else if (heap_event_type == Universe::CopySameArray) {
          obj_src = obj_dst;
          if (obj_src == NULL) {
            printf("1152: Didn't find src '%p'\n", obj_dst_start);
          }
          offsets = {event.src >> 32,
                     (uint64_t)obj_dst_start - (uint64_t)(oopDesc*)obj_dst};
          length_event = {event.src & ((1UL << 32) - 1), 0};
        } else if (heap_event_type == Universe::CopyNewArray || 
                   heap_event_type == Universe::CopyNewArrayOfSameLength) {
          HeapEvent next_event = heap_events_start[event_iter+1];
          
         if (heap_event_type == Universe::CopyNewArray) {
            length_event = {next_event.src, 0};
            obj_src_start = (oopDesc*)next_event.dst;
          } else if (heap_event_type == Universe::CopyNewArrayOfSameLength) {
            length_event = {event.src, 0};
            obj_src_start = (oopDesc*)next_event.src;
          }

          event_iter = event_iter + 1;
          obj_src = (objArrayOop)oop_for_address(ObjectNode::oop_to_obj_node, obj_src_start);

          if (obj_src == NULL) {
            printf("1167: Didn't find src '%p'\n", obj_src_start);
          }
          //No need to consider objArrayOop::base() in offset calculation
          offsets = {(uint64_t)obj_src_start - (uint64_t)(oopDesc*)obj_src,
                     (uint64_t)obj_dst_start - (uint64_t)(oopDesc*)obj_dst};
        }

        // printf("src %p %ld dst %p %ld length %ld\n", (oopDesc*)obj_src, offsets.src, (oopDesc*)obj_dst, offsets.dst, length_event.src);
        auto obj_src_node_iter = ObjectNode::oop_to_obj_node.find(obj_src);
        auto obj_dst_node_iter = ObjectNode::oop_to_obj_node.find(obj_dst);

        if (obj_dst_node_iter == ObjectNode::oop_to_obj_node.end() ||
            obj_src_node_iter == ObjectNode::oop_to_obj_node.end()) {
            printf("1085: didn't find %p %p\n", (void*)obj_src, (void*)obj_dst);
            continue;
        }

        if (obj_dst_node_iter->second.type() != Universe::NewArray || 
            obj_src_node_iter->second.type() != Universe::NewArray) {
          char buf[1024];
          printf("Destination of class type '%s' is not object array but is '%ld' ", 
                 Universe::get_oop_klass_name(obj_src_node_iter->first, buf), obj_dst_node_iter->second.type());
          printf("Source of class type '%s' is not object array but is '%ld'\n", 
                 Universe::get_oop_klass_name(obj_src_node_iter->first, buf), obj_src_node_iter->second.type());
        }

        if (length_event.src >= 1UL<<30) {
          printf("too large copy length %ld\n", length_event.src);
          abort();
        }
        if (obj_src != obj_dst || (obj_src == obj_dst && offsets.src >= offsets.dst)) {
          //Non overlapping arrays, so copy forward
          for (uint i = 0; i < length_event.src; i++) {
            int src_array_index = offsets.src + i;
            int dst_array_index = offsets.dst + i;
            uint64_t src_elem_addr = ((uint64_t)obj_src->base()) + src_array_index * sizeof(oop);
            uint64_t dst_elem_addr = ((uint64_t)obj_dst->base()) + dst_array_index * sizeof(oop);
            
            if (obj_src_node_iter->second.has_field((void*)src_elem_addr)) {
              oop src_elem_val = obj_src_node_iter->second.field_val((void*)src_elem_addr);
              obj_dst_node_iter->second.update_or_add_field((void*)dst_elem_addr, 
              src_elem_val, 0);
            } else {
              // obj_dst_node_iter->second.update_or_add_field((void*)dst_elem_addr, (oopDesc*)*(reinterpret_cast<uint64_t*>(src_elem_addr)), 0);
              // printf("1134: Not found 0x%lx -> 0x%lx for array %p\n", src_elem_addr, dst_elem_addr, (oopDesc*)obj_dst);
            }
          }
        } else {
          //Overlapping arrays, so copy backward
          for (int i = (int)length_event.src - 1; i >= 0; i--) {
            int src_array_index = offsets.src + i;
            int dst_array_index = offsets.dst + i;
            uint64_t src_elem_addr = ((uint64_t)obj_src->base()) + src_array_index * sizeof(oop);
            uint64_t dst_elem_addr = ((uint64_t)
            obj_dst->base()) + dst_array_index * sizeof(oop);
            
            if (obj_src_node_iter->second.has_field((void*)src_elem_addr)) {
              oop src_elem_val = obj_src_node_iter->second.field_val((void*)src_elem_addr);
              obj_dst_node_iter->second.update_or_add_field((void*)dst_elem_addr, 
              src_elem_val, 0);
            } else {
              // printf("1151: Not found 0x%lx -> 0x%lx for array %p\n", src_elem_addr, dst_elem_addr, (oopDesc*)obj_dst);
            }
          }
        }
      } else if (heap_event_type == MoveObject) {
        oop obj_src = oop((oopDesc*)event.src);
        oop obj_dst = oop((oopDesc*)event.dst);
        auto obj_src_node_iter = ObjectNode::oop_to_obj_node.find(obj_src);
        if(checking == 3) printf("Move %p --> %p\n", (oopDesc*)obj_src, (oopDesc*)obj_dst);
        if (obj_src_node_iter != ObjectNode::oop_to_obj_node.end()) {
          ObjectNode node = obj_src_node_iter->second;
          ObjectNode::oop_to_obj_node.erase(obj_src_node_iter);
          if (ObjectNode::oop_to_obj_node.find((oopDesc*)obj_dst) != ObjectNode::oop_to_obj_node.end()) {
            ObjectNode::oop_to_obj_node.erase((oopDesc*)obj_dst);
          }
          node.set_oop(obj_dst);
          ObjectNode::oop_to_obj_node.emplace((oopDesc*)obj_dst, node);
        }

        // if (checking > 1) {
        //   printf("%p -> %p\n", obj_src, obj_dst);
        // }

        old_to_new_objects[obj_src] = obj_dst;
      } else if (heap_event_type == ClearContiguousSpace) {
        uint64_t start = event.src;
        uint64_t end = event.dst;
        oopDesc* first = (oopDesc*)start;
        oopDesc* last = (oopDesc*)end;
        if (first == last) {
          ObjectNode::oop_to_obj_node.erase(first);
        } else {
          auto first_obj_iter = ObjectNode::oop_to_obj_node.lower_bound(first);
          auto last_obj_iter = ObjectNode::oop_to_obj_node.lower_bound(last);

          if (first_obj_iter != ObjectNode::oop_to_obj_node.end() && 
            first_obj_iter->first < first) 
            first_obj_iter++;
          if (last_obj_iter != ObjectNode::oop_to_obj_node.end() && 
              last_obj_iter->first < last) last_obj_iter++;

          if (first && last) {
            ObjectNode::oop_to_obj_node.erase(first_obj_iter, last_obj_iter);
          }
        }
      } else {
        printf("Unknown event %ld 0x%lx 0x%lx at %ld\n", heap_event_type, event.src, event.dst, event_iter);
      }
    } 
  }
  
  //Update object values in each field/element to new objects
  if (old_to_new_objects.size() > 0) {
    for (auto& obj_node : ObjectNode::oop_to_obj_node) {
      obj_node.second.update_field_vals(old_to_new_objects);
    }
  }
  printf("event_threads.size() %ld\n", event_threads.size());
  CheckGraph check_graph(true, true, true, true);
  if (not CheckHeapEventGraphOnlyBeforeExit || Universe::is_verify_from_exit) {
    Universe::heap()->object_iterate(&check_graph);
  } else {
    printf("Only Check Heap Graph before exit\n");
    check_graph.num_found = check_graph.num_not_found = check_graph.num_src_not_correct = -1;
  }

  size_t num_objects = ObjectNode::oop_to_obj_node.size();
  size_t num_fields = 0;
  for (auto& it : ObjectNode::oop_to_obj_node) {
    num_fields += it.second.fields().size();
  }
  printf("Total Events '%ld' {Object: %ld, FieldSet: %ld} ; Events-Found '%d' Events-Notfound '%d' Events-Wrong '%d'\n", 
  num_objects + num_fields, num_objects, num_fields, 
  check_graph.num_found, check_graph.num_not_found, check_graph.num_src_not_correct);
  if (is_verify_from_full_gc_start && false) {
    marked_objects.clear();
    mark_objects(marked_objects);
    printf("1570: Marked objects: %ld\n", marked_objects.size());
  } 
  // else if (is_verify_from_young_gc_start) {
  //   marked_objects.clear();
  //   mark_objects(marked_objects);
  //   printf("Marked objects: %ld\n", marked_objects.size());
  // }

  // if (Universe::is_verify_cause_full_gc) abort();

  Universe::is_verify_cause_full_gc = false;
  Universe::is_verify_from_gc = false;
  Universe::is_verify_from_full_gc_start = false;
  // pthread_mutex_unlock(&lock);
}

#include <cuda.h>
#include <pthread.h>
#include <semaphore.h>

extern int* h_heap_events;
extern CUdeviceptr d_heap_events;

#define checkCudaErrors(err)  __checkCudaErrors ((err), __FILE__, __LINE__)

void __checkCudaErrors( CUresult err, const char *file, const int line )
{
  if( CUDA_SUCCESS != err) {
    const char * errstr;
    cuGetErrorString(err, &errstr);
    fprintf(stderr,
            "CUDA Driver API error = %04d '%s' from file <%s>, line %i.\n",
            err, errstr, file, line );
    
    exit(-1);
  }
}

void* Universe::cudaAllocHost(size_t size) {
  void* p;
  checkCudaErrors(::cuMemAllocHost((void**)&p, size));
  return p;
}

void* Universe::cumemcpy_func(void* arg)
{
  CUdevice   device;
  CUcontext  context;
  CUstream   stream;

  checkCudaErrors(cuInit(0));
  checkCudaErrors(cuDeviceGet(&device, 0));
  checkCudaErrors(cuCtxCreate(&context, 0, device));
  checkCudaErrors(cuStreamCreate(&stream, CU_STREAM_NON_BLOCKING));
  uint32_t MAX_THREADS = 16;
  Universe::HeapEvent* d_heap_events[MAX_THREADS];
  uint64_t num_events[MAX_THREADS];

  if (CreateGPUHeapEventGraph && CheckHeapEventGraphWithHeap) {
    for (uint i = 0; i < MAX_THREADS; i++) {
      CUdeviceptr ptr;
      checkCudaErrors(cuMemAlloc(&ptr, Universe::heap_events_buf_size()));
      d_heap_events[i] = (Universe::HeapEvent*)ptr;
    }
  } else {
    // void* ptr;
    // checkCudaErrors(cuMemAlloc(&ptr, MaxHeapEvents * 2 * sizeof(Universe::HeapEvent)));
    // d_heap_events[0] = (Universe::HeapEvent*)ptr;
  }
  checkCudaErrors(cuMemAllocHost((void**)&h_heap_events, MaxHeapEvents * sizeof(Universe::HeapEvent)));

  Universe::vector<Universe::HeapEvent*> is_registered;
  while(true) {
    sem_wait(&Universe::cuda_semaphore);
    #ifndef PRODUCT
      // printf("Transferring %ld from %p\n", Universe::events_to_transfer.length, Universe::events_to_transfer.events);
    #else
      // printf("Transferring\n");
    #endif

    if (CreateGPUHeapEventGraph && CheckHeapEventGraphWithHeap) {
      uint thread_i = 0;
      for (auto heap_events_iter = LinkedListIterator<HeapEvent*>(all_heap_events.head()); 
       !heap_events_iter.is_empty(); heap_events_iter.next(), thread_i++) {
        auto th_heap_events = *heap_events_iter;
        const size_t heap_events_size = *(const uint64_t*)th_heap_events;
        printf("Transferring %ld from %p\n", heap_events_size, th_heap_events);
        num_events[thread_i] = heap_events_size;
        checkCudaErrors(cuMemcpyHtoD((CUdeviceptr)d_heap_events[thread_i], th_heap_events + 1, Universe::heap_events_buf_size() - sizeof(Universe::HeapEvent)));
      }

      sem_post(&Universe::cuda_thread_wait_semaphore);
    }
    
    
    //TODO: Enable for doing pinned memory transfers
    // bool found = false;
    // for (auto ptr : is_registered) {
    //   if (ptr <= Universe::events_to_transfer.events && Universe::events_to_transfer.events <= ptr + MaxHeapEvents*2) {
    //     found = true;
    //     break;
    //   }
    // } 

    // if (!found) {
    //   Universe::HeapEvent* page_start = (Universe::HeapEvent*)(((uint64_t)Universe::events_to_transfer.events/4096)*4096);
    //   // printf("1246: %p page start %p %ld %ld\n", Universe::events_to_transfer.events, page_start, Universe::events_to_transfer.length, MaxHeapEvents);
    //   checkCudaErrors(cuMemHostRegister(page_start,
    //                                     MaxHeapEvents * 2 * sizeof(Universe::HeapEvent),
    //                                     CU_MEMHOSTREGISTER_PORTABLE));
    //   is_registered.push_back(page_start);
    // }

    // checkCudaErrors(cuMemcpyHtoD(d_heap_events, Universe::events_to_transfer.events, Universe::events_to_transfer.length * sizeof(Universe::HeapEvent) - 1024));
    // checkCudaErrors(cuMemcpyHtoDAsync(d_heap_events, h_heap_events, MaxHeapEvents * sizeof(Universe::HeapEvent), stream));
  }
}

pthread_t cumemcpy_tid;

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

  if (InstrumentHeapEvents) {
    HeapEvent* th0_heap_events = (Universe::HeapEvent*)Universe::mmap((128+MaxHeapEvents)*sizeof(Universe::HeapEvent));
    all_heap_events.add(th0_heap_events);
    JavaThread* cur_thread = JavaThread::current();
    cur_thread->heap_events = th0_heap_events;
    sem_init(&Universe::cuda_semaphore, 0, 0);
    int error = pthread_create(&cumemcpy_tid, NULL, &cumemcpy_func, NULL);
    if (error != 0)
      printf("CUDA Thread can't be created : [%s]\n", strerror(error));
  }

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

class ObjectCount : public ObjectClosure {
  private:
  uint64_t _count;
  public:
  ObjectCount() : _count(0) {}

  virtual void do_object(oop obj) {
    _count++;
  }

  uint64_t count(){return _count;}
};

class ObjectClasses : public ObjectClosure {
  public:
  Universe::unordered_map<Klass*, int> all_klasses;

  ObjectClasses() {}

  virtual void do_object(oop obj) {
    if (obj != NULL && (uint64_t)(void*)obj != 0xbaadbabebaadbabe) {
      if (all_klasses.find(obj->klass()) == all_klasses.end()) {
        all_klasses[obj->klass()] = 0;
      }

      all_klasses[obj->klass()] += 1;
    }
  }
};

void Universe::print_on(outputStream* st) {
  GCMutexLocker hl(Heap_lock); // Heap_lock might be locked by caller thread.
  st->print_cr("Heap");
  heap()->print_on(st);
  if (PrintNumberOfObjects) {
    ObjectCount obj_count;
    Universe::heap()->object_iterate(&obj_count);
    st->print("Number of Object: %ld\n", obj_count.count());
    return;
    ObjectClasses obj_classes;
    heap()->object_iterate(&obj_classes);

    for (auto k : obj_classes.all_klasses) {
      char buf[1024];
      st->print("%s: %d\n", get_klass_name(k.first, buf), k.second);
    }
  }
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
