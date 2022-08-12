/*
 * Copyright (c) 2017, 2020, Oracle and/or its affiliates. All rights reserved.
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

#ifndef SHARE_OOPS_ACCESS_HPP
#define SHARE_OOPS_ACCESS_HPP

#include "memory/allocation.hpp"
#include "oops/accessBackend.hpp"
#include "oops/accessDecorators.hpp"
#include "oops/oopsHierarchy.hpp"
#include "utilities/debug.hpp"
#include "utilities/globalDefinitions.hpp"
#include "memory/universe.hpp"

// = GENERAL =
// Access is an API for performing accesses with declarative semantics. Each access can have a number of "decorators".
// A decorator is an attribute or property that affects the way a memory access is performed in some way.
// There are different groups of decorators. Some have to do with memory ordering, others to do with,
// e.g. strength of references, strength of GC barriers, or whether compression should be applied or not.
// Some decorators are set at buildtime, such as whether primitives require GC barriers or not, others
// at callsites such as whether an access is in the heap or not, and others are resolved at runtime
// such as GC-specific barriers and encoding/decoding compressed oops. For more information about what
// decorators are available, cf. oops/accessDecorators.hpp.
// By pipelining handling of these decorators, the design of the Access API allows separation of concern
// over the different orthogonal concerns of decorators, while providing a powerful way of
// expressing these orthogonal semantic properties in a unified way.
//
// == OPERATIONS ==
// * load: Load a value from an address.
// * load_at: Load a value from an internal pointer relative to a base object.
// * store: Store a value at an address.
// * store_at: Store a value in an internal pointer relative to a base object.
// * atomic_cmpxchg: Atomically compare-and-swap a new value at an address if previous value matched the compared value.
// * atomic_cmpxchg_at: Atomically compare-and-swap a new value at an internal pointer address if previous value matched the compared value.
// * atomic_xchg: Atomically swap a new value at an address if previous value matched the compared value.
// * atomic_xchg_at: Atomically swap a new value at an internal pointer address if previous value matched the compared value.
// * arraycopy: Copy data from one heap array to another heap array. The ArrayAccess class has convenience functions for this.
// * clone: Clone the contents of an object to a newly allocated object.
//
// == IMPLEMENTATION ==
// Each access goes through the following steps in a template pipeline.
// There are essentially 5 steps for each access:
// * Step 1:   Set default decorators and decay types. This step gets rid of CV qualifiers
//             and sets default decorators to sensible values.
// * Step 2:   Reduce types. This step makes sure there is only a single T type and not
//             multiple types. The P type of the address and T type of the value must
//             match.
// * Step 3:   Pre-runtime dispatch. This step checks whether a runtime call can be
//             avoided, and in that case avoids it (calling raw accesses or
//             primitive accesses in a build that does not require primitive GC barriers)
// * Step 4:   Runtime-dispatch. This step performs a runtime dispatch to the corresponding
//             BarrierSet::AccessBarrier accessor that attaches GC-required barriers
//             to the access.
// * Step 5.a: Barrier resolution. This step is invoked the first time a runtime-dispatch
//             happens for an access. The appropriate BarrierSet::AccessBarrier accessor
//             is resolved, then the function pointer is updated to that accessor for
//             future invocations.
// * Step 5.b: Post-runtime dispatch. This step now casts previously unknown types such
//             as the address type of an oop on the heap (is it oop* or narrowOop*) to
//             the appropriate type. It also splits sufficiently orthogonal accesses into
//             different functions, such as whether the access involves oops or primitives
//             and whether the access is performed on the heap or outside. Then the
//             appropriate BarrierSet::AccessBarrier is called to perform the access.
//
// The implementation of step 1-4 resides in in accessBackend.hpp, to allow selected
// accesses to be accessible from only access.hpp, as opposed to access.inline.hpp.
// Steps 5.a and 5.b require knowledge about the GC backends, and therefore needs to
// include the various GC backend .inline.hpp headers. Their implementation resides in
// access.inline.hpp. The accesses that are allowed through the access.hpp file
// must be instantiated in access.cpp using the INSTANTIATE_HPP_ACCESS macro.

template <DecoratorSet decorators = DECORATORS_NONE>
class Access: public AllStatic {
  // This function asserts that if an access gets passed in a decorator outside
  // of the expected_decorators, then something is wrong. It additionally checks
  // the consistency of the decorators so that supposedly disjoint decorators are indeed
  // disjoint. For example, an access can not be both in heap and on root at the
  // same time.
  template <DecoratorSet expected_decorators>
  static void verify_decorators();

  template <DecoratorSet expected_mo_decorators>
  static void verify_primitive_decorators() {
    const DecoratorSet primitive_decorators = (AS_DECORATOR_MASK ^ AS_NO_KEEPALIVE) |
                                              IN_HEAP | IS_ARRAY;
    verify_decorators<expected_mo_decorators | primitive_decorators>();
  }

  template <DecoratorSet expected_mo_decorators>
  static void verify_oop_decorators() {
    const DecoratorSet oop_decorators = AS_DECORATOR_MASK | IN_DECORATOR_MASK |
                                        (ON_DECORATOR_MASK ^ ON_UNKNOWN_OOP_REF) | // no unknown oop refs outside of the heap
                                        IS_ARRAY | IS_NOT_NULL | IS_DEST_UNINITIALIZED;
    verify_decorators<expected_mo_decorators | oop_decorators>();
  }

  template <DecoratorSet expected_mo_decorators>
  static void verify_heap_oop_decorators() {
    const DecoratorSet heap_oop_decorators = AS_DECORATOR_MASK | ON_DECORATOR_MASK |
                                             IN_HEAP | IS_ARRAY | IS_NOT_NULL;
    verify_decorators<expected_mo_decorators | heap_oop_decorators>();
  }

  static const DecoratorSet load_mo_decorators = MO_UNORDERED | MO_RELAXED | MO_ACQUIRE | MO_SEQ_CST;
  static const DecoratorSet store_mo_decorators = MO_UNORDERED | MO_RELAXED | MO_RELEASE | MO_SEQ_CST;
  static const DecoratorSet atomic_xchg_mo_decorators = MO_SEQ_CST;
  static const DecoratorSet atomic_cmpxchg_mo_decorators = MO_RELAXED | MO_SEQ_CST;

protected:
  template <typename T>
  static inline bool oop_arraycopy(arrayOop src_obj, size_t src_offset_in_bytes, const T* src_raw,
                                   arrayOop dst_obj, size_t dst_offset_in_bytes, T* dst_raw,
                                   size_t length) {
    verify_decorators<ARRAYCOPY_DECORATOR_MASK | IN_HEAP |
                      AS_DECORATOR_MASK | IS_ARRAY | IS_DEST_UNINITIALIZED>();
    #if 0
    // //TODO: Might also be called by ObjArrayKlass:copy_array
    // if (src_obj != NULL && dst_obj != NULL) {
    //   objArrayOop src_arrayoop = (objArrayOop)src_obj;
    //   objArrayOop dst_arrayoop = (objArrayOop)dst_obj;

    //   int src_pos = (src_offset_in_bytes - src_arrayoop->base_offset_in_bytes())/sizeof(oop);
    //   int dst_pos = (dst_offset_in_bytes - dst_arrayoop->base_offset_in_bytes())/sizeof(oop);
    //   // printf("src_obj %p dst_obj %p\n", src_obj, dst_obj);
    //   for (uint64_t i = 0; i < length; i++) {
    //     oop elem = src_arrayoop->obj_at(src_pos + i);
    //     uint64_t elem_addr = (uint64_t)(((objArrayOop)dst_arrayoop)->base()) + (dst_pos + i) * sizeof(oop);
    //     Universe::add_heap_event(Universe::HeapEvent{Universe::FieldSet, (uint64_t)(void*)elem, elem_addr});
    //   }
    // } else if (src_raw != NULL && dst_raw != NULL) {
    //   // printf("src_obj %p dst_obj %p\n", src_obj, dst_obj);
    //   for (uint64_t i = 0; i < length; i++) {
    //     T elem = src_raw[i];
    //     uint64_t elem_addr = (uint64_t)&dst_raw[i];
    //     Universe::add_heap_event(Universe::HeapEvent{Universe::FieldSet, (uint64_t)(void*)elem, elem_addr});
    //   }
    // } else if (src_raw != NULL && dst_obj != NULL) {
    //   objArrayOop dst_arrayoop = (objArrayOop)dst_obj;

    //   int dst_pos = (dst_offset_in_bytes - dst_arrayoop->base_offset_in_bytes())/sizeof(oop);
    //   // printf("src_obj %p dst_obj %p\n", src_obj, dst_obj);
    //   for (uint64_t i = 0; i < length; i++) {
    //     T elem = src_raw[i];
    //     uint64_t elem_addr = (uint64_t)(((objArrayOop)dst_arrayoop)->base()) + (dst_pos + i) * sizeof(oop);
    //     Universe::add_heap_event(Universe::HeapEvent{Universe::FieldSet, (uint64_t)(void*)elem, elem_addr});
    //   }
    // } else if (src_obj != NULL && dst_raw != NULL) {
    //   objArrayOop src_arrayoop = (objArrayOop)src_obj;

    //   int src_pos = (src_offset_in_bytes - src_arrayoop->base_offset_in_bytes())/sizeof(oop);
    //   for (uint64_t i = 0; i < length; i++) {
    //     oop elem = src_arrayoop->obj_at(src_pos + i);
    //     uint64_t elem_addr = (uint64_t)&dst_raw[i];
    //     Universe::add_heap_event(Universe::HeapEvent{Universe::FieldSet, (uint64_t)(void*)elem, elem_addr});
    //   }
    // } 
    #endif
    return AccessInternal::arraycopy<decorators | INTERNAL_VALUE_IS_OOP>(src_obj, src_offset_in_bytes, src_raw,
                                                                         dst_obj, dst_offset_in_bytes, dst_raw,
                                                                         length);
  }

  template <typename T>
  static inline void arraycopy(arrayOop src_obj, size_t src_offset_in_bytes, const T* src_raw,
                               arrayOop dst_obj, size_t dst_offset_in_bytes, T* dst_raw,
                               size_t length) {
    verify_decorators<ARRAYCOPY_DECORATOR_MASK | IN_HEAP |
                      AS_DECORATOR_MASK | IS_ARRAY>();
    AccessInternal::arraycopy<decorators>(src_obj, src_offset_in_bytes, src_raw,
                                          dst_obj, dst_offset_in_bytes, dst_raw,
                                          length);
  }

public:
  // Primitive heap accesses
  static inline AccessInternal::LoadAtProxy<decorators> load_at(oop base, ptrdiff_t offset) {
    verify_primitive_decorators<load_mo_decorators>();
    return AccessInternal::LoadAtProxy<decorators>(base, offset);
  }

  template <typename T>
  static inline void store_at(oop base, ptrdiff_t offset, T value) {
    // printf("206: dst 0x%lx\n", ((uint64_t)(void*)base) + offset);
    verify_primitive_decorators<store_mo_decorators>();
    AccessInternal::store_at<decorators>(base, offset, value);
  }

  template <typename T>
  static inline T atomic_cmpxchg_at(oop base, ptrdiff_t offset, T compare_value, T new_value) {
    verify_primitive_decorators<atomic_cmpxchg_mo_decorators>();
    return AccessInternal::atomic_cmpxchg_at<decorators>(base, offset, compare_value, new_value);
  }

  template <typename T>
  static inline T atomic_xchg_at(oop base, ptrdiff_t offset, T new_value) {
    verify_primitive_decorators<atomic_xchg_mo_decorators>();
    return AccessInternal::atomic_xchg_at<decorators>(base, offset, new_value);
  }

  // Oop heap accesses
  static inline AccessInternal::OopLoadAtProxy<decorators> oop_load_at(oop base, ptrdiff_t offset) {
    verify_heap_oop_decorators<load_mo_decorators>();
    return AccessInternal::OopLoadAtProxy<decorators>(base, offset);
  }

  template <typename T>
  static inline void oop_store_at(oop base, ptrdiff_t offset, T value) {
    verify_heap_oop_decorators<store_mo_decorators>();
    typedef typename AccessInternal::OopOrNarrowOop<T>::type OopType;
    OopType oop_value = value;
    // printf("256: src %p src+offset 0x%lx\n", (void*)base, ((uint64_t)base) + offset);
    //TODO: This is also called by obj_put_field_at and also by unsafe PutReference?
    //TODO: Assuming T is Oop and not a NarrowOop
    // if (true) {
    //   uint64_t dst = ((uint64_t)(void*)base) + offset;
    //   if (Universe::checking == 1) {
    //     char buf[1024];
    //     base->klass()->name()->as_C_string(buf, 1024);
    //     printf("0x%lx: oop %p offset 0x%lx (%p) src %p oop-class %s oop-class-id %d\n", dst, (void*)base, offset, (char*)base + offset, (void*)value, buf, base->klass()->id());
    //   }
    // }
    Universe::add_heap_event(Universe::FieldSet, Universe::HeapEvent{(uint64_t)(void*)value, ((uint64_t)(void*)base) + offset});
    AccessInternal::store_at<decorators | INTERNAL_VALUE_IS_OOP>(base, offset, oop_value);
  }

  template <typename T>
  static inline T oop_atomic_cmpxchg_at(oop base, ptrdiff_t offset, T compare_value, T new_value) {
    verify_heap_oop_decorators<atomic_cmpxchg_mo_decorators>();
    typedef typename AccessInternal::OopOrNarrowOop<T>::type OopType;
    OopType new_oop_value = new_value;
    OopType compare_oop_value = compare_value;
    // printf("206: src %p dst 0x%lx\n", (void*)new_oop_value, ((uint64_t)(void*)base) + offset);
    //TODO: Is this also called by some other function
    //TODO: Assuming T is oop and not a narrowoop
    Universe::add_heap_event(Universe::FieldSet, Universe::HeapEvent{(uint64_t)(void*)new_value, ((uint64_t)(void*)base) + offset});
    return AccessInternal::atomic_cmpxchg_at<decorators | INTERNAL_VALUE_IS_OOP>(base, offset, compare_oop_value, new_oop_value);
  }

  template <typename T>
  static inline T oop_atomic_xchg_at(oop base, ptrdiff_t offset, T new_value) {
    verify_heap_oop_decorators<atomic_xchg_mo_decorators>();
    typedef typename AccessInternal::OopOrNarrowOop<T>::type OopType;
    OopType new_oop_value = new_value;
    // printf("206: src %p dst 0x%lx\n", (void*)new_oop_value, ((uint64_t)(void*)base) + offset);
    //TODO: Is this also called by some other function
    //TODO: Assuming T is oop and not a narrowoop
    Universe::add_heap_event(Universe::FieldSet, Universe::HeapEvent{(uint64_t)(void*)new_value, ((uint64_t)(void*)base) + offset});
    return AccessInternal::atomic_xchg_at<decorators | INTERNAL_VALUE_IS_OOP>(base, offset, new_oop_value);
  }

  // Clone an object from src to dst
  static inline void clone(oop src, oop dst, size_t size) {
    verify_decorators<IN_HEAP>();
    // printf("212: src %p dst %p\n", (void*)src, (void*)dst);
    //Setting a field of dst is an heap event
    AccessInternal::clone<decorators>(src, dst, size);
  }

  // Primitive accesses
  template <typename P>
  static inline P load(P* addr) {
    verify_primitive_decorators<load_mo_decorators>();
    return AccessInternal::load<decorators, P, P>(addr);
  }

  template <typename P, typename T>
  static inline void store(P* addr, T value) {
    verify_primitive_decorators<store_mo_decorators>();
    // printf("228: src %p\n", (void*)addr);
    AccessInternal::store<decorators>(addr, value);
  }

  template <typename P, typename T>
  static inline T atomic_cmpxchg(P* addr, T compare_value, T new_value) {
    verify_primitive_decorators<atomic_cmpxchg_mo_decorators>();
    return AccessInternal::atomic_cmpxchg<decorators>(addr, compare_value, new_value);
  }

  template <typename P, typename T>
  static inline T atomic_xchg(P* addr, T new_value) {
    verify_primitive_decorators<atomic_xchg_mo_decorators>();
    return AccessInternal::atomic_xchg<decorators>(addr, new_value);
  }

  // Oop accesses
  template <typename P>
  static inline AccessInternal::OopLoadProxy<P, decorators> oop_load(P* addr) {
    verify_oop_decorators<load_mo_decorators>();
    return AccessInternal::OopLoadProxy<P, decorators>(addr);
  }

  template <typename P, typename T>
  static inline void oop_store(P* addr, T value) {
    verify_oop_decorators<store_mo_decorators>();
    typedef typename AccessInternal::OopOrNarrowOop<T>::type OopType;
    OopType oop_value = value;
    if (Universe::is_verify_cause_full_gc) {
      // printf("%p at %p\n", (void*)addr, value);
      // Universe::print_heap_event_counter();
      //Universe::add_heap_event(Universe::HeapEvent{Universe::FieldSet, (uint64_t)addr, (uint64_t)value});
    }
    AccessInternal::store<decorators | INTERNAL_VALUE_IS_OOP>(addr, oop_value);
  }

  template <typename P, typename T>
  static inline T oop_atomic_cmpxchg(P* addr, T compare_value, T new_value) {
    verify_oop_decorators<atomic_cmpxchg_mo_decorators>();
    typedef typename AccessInternal::OopOrNarrowOop<T>::type OopType;
    OopType new_oop_value = new_value;
    OopType compare_oop_value = compare_value;
    // printf("256: src %p\n", (void*)addr);
    return AccessInternal::atomic_cmpxchg<decorators | INTERNAL_VALUE_IS_OOP>(addr, compare_oop_value, new_oop_value);
  }

  template <typename P, typename T>
  static inline T oop_atomic_xchg(P* addr, T new_value) {
    verify_oop_decorators<atomic_xchg_mo_decorators>();
    typedef typename AccessInternal::OopOrNarrowOop<T>::type OopType;
    OopType new_oop_value = new_value;
    // printf("206: src %p dst %p\n", (void*)new_oop_value, ((void*)addr));
    return AccessInternal::atomic_xchg<decorators | INTERNAL_VALUE_IS_OOP>(addr, new_oop_value);
  }
};

// Helper for performing raw accesses (knows only of memory ordering
// atomicity decorators as well as compressed oops)
template <DecoratorSet decorators = DECORATORS_NONE>
class RawAccess: public Access<AS_RAW | decorators> {};

// Helper for performing normal accesses on the heap. These accesses
// may resolve an accessor on a GC barrier set
template <DecoratorSet decorators = DECORATORS_NONE>
class HeapAccess: public Access<IN_HEAP | decorators> {};

// Helper for performing normal accesses in roots. These accesses
// may resolve an accessor on a GC barrier set
template <DecoratorSet decorators = DECORATORS_NONE>
class NativeAccess: public Access<IN_NATIVE | decorators> {};

// Helper for array access.
template <DecoratorSet decorators = DECORATORS_NONE>
class ArrayAccess: public HeapAccess<IS_ARRAY | decorators> {
  typedef HeapAccess<IS_ARRAY | decorators> AccessT;
public:
  template <typename T>
  static inline void arraycopy(arrayOop src_obj, size_t src_offset_in_bytes,
                               arrayOop dst_obj, size_t dst_offset_in_bytes,
                               size_t length) {
    AccessT::arraycopy(src_obj, src_offset_in_bytes, reinterpret_cast<const T*>(NULL),
                       dst_obj, dst_offset_in_bytes, reinterpret_cast<T*>(NULL),
                       length);
  }

  template <typename T>
  static inline void arraycopy_to_native(arrayOop src_obj, size_t src_offset_in_bytes,
                                         T* dst,
                                         size_t length) {
    AccessT::arraycopy(src_obj, src_offset_in_bytes, reinterpret_cast<const T*>(NULL),
                       NULL, 0, dst,
                       length);
  }

  template <typename T>
  static inline void arraycopy_from_native(const T* src,
                                           arrayOop dst_obj, size_t dst_offset_in_bytes,
                                           size_t length) {
    AccessT::arraycopy(NULL, 0, src,
                       dst_obj, dst_offset_in_bytes, reinterpret_cast<T*>(NULL),
                       length);
  }

  static inline bool oop_arraycopy(arrayOop src_obj, size_t src_offset_in_bytes,
                                   arrayOop dst_obj, size_t dst_offset_in_bytes,
                                   size_t length) {
    return AccessT::oop_arraycopy(src_obj, src_offset_in_bytes, reinterpret_cast<const HeapWord*>(NULL),
                                  dst_obj, dst_offset_in_bytes, reinterpret_cast<HeapWord*>(NULL),
                                  length);
  }

  template <typename T>
  static inline bool oop_arraycopy_raw(T* src, T* dst, size_t length) {
    return AccessT::oop_arraycopy(NULL, 0, src,
                                  NULL, 0, dst,
                                  length);
  }

};

template <DecoratorSet decorators>
template <DecoratorSet expected_decorators>
void Access<decorators>::verify_decorators() {
  STATIC_ASSERT((~expected_decorators & decorators) == 0); // unexpected decorator used
  const DecoratorSet barrier_strength_decorators = decorators & AS_DECORATOR_MASK;
  STATIC_ASSERT(barrier_strength_decorators == 0 || ( // make sure barrier strength decorators are disjoint if set
    (barrier_strength_decorators ^ AS_NO_KEEPALIVE) == 0 ||
    (barrier_strength_decorators ^ AS_RAW) == 0 ||
    (barrier_strength_decorators ^ AS_NORMAL) == 0
  ));
  const DecoratorSet ref_strength_decorators = decorators & ON_DECORATOR_MASK;
  STATIC_ASSERT(ref_strength_decorators == 0 || ( // make sure ref strength decorators are disjoint if set
    (ref_strength_decorators ^ ON_STRONG_OOP_REF) == 0 ||
    (ref_strength_decorators ^ ON_WEAK_OOP_REF) == 0 ||
    (ref_strength_decorators ^ ON_PHANTOM_OOP_REF) == 0 ||
    (ref_strength_decorators ^ ON_UNKNOWN_OOP_REF) == 0
  ));
  const DecoratorSet memory_ordering_decorators = decorators & MO_DECORATOR_MASK;
  STATIC_ASSERT(memory_ordering_decorators == 0 || ( // make sure memory ordering decorators are disjoint if set
    (memory_ordering_decorators ^ MO_UNORDERED) == 0 ||
    (memory_ordering_decorators ^ MO_RELAXED) == 0 ||
    (memory_ordering_decorators ^ MO_ACQUIRE) == 0 ||
    (memory_ordering_decorators ^ MO_RELEASE) == 0 ||
    (memory_ordering_decorators ^ MO_SEQ_CST) == 0
  ));
  const DecoratorSet location_decorators = decorators & IN_DECORATOR_MASK;
  STATIC_ASSERT(location_decorators == 0 || ( // make sure location decorators are disjoint if set
    (location_decorators ^ IN_NATIVE) == 0 ||
    (location_decorators ^ IN_HEAP) == 0
  ));
}

#endif // SHARE_OOPS_ACCESS_HPP
