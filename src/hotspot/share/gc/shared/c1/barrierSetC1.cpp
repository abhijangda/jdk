/*
 * Copyright (c) 2018, 2020, Oracle and/or its affiliates. All rights reserved.
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
#include "c1/c1_Defs.hpp"
#include "c1/c1_LIRGenerator.hpp"
#include "classfile/javaClasses.hpp"
#include "gc/shared/c1/barrierSetC1.hpp"
#include "utilities/macros.hpp"

#ifndef PATCHED_ADDR
#define PATCHED_ADDR  (max_jint)
#endif

#ifdef ASSERT
#define __ gen->lir(__FILE__, __LINE__)->
#else
#define __ gen->lir()->
#endif

LIR_Opr BarrierSetC1::resolve_address(LIRAccess& access, bool resolve_in_register) {
  DecoratorSet decorators = access.decorators();
  bool is_array = (decorators & IS_ARRAY) != 0;
  bool needs_patching = (decorators & C1_NEEDS_PATCHING) != 0;

  LIRItem& base = access.base().item();
  LIR_Opr offset = access.offset().opr();
  LIRGenerator *gen = access.gen();

  LIR_Opr addr_opr;
  if (is_array) {
    addr_opr = LIR_OprFact::address(gen->emit_array_address(base.result(), offset, access.type()));
    // printf("54: addr_opr %p\n", addr_opr->as_address_ptr());
  } else if (needs_patching) {
    // we need to patch the offset in the instruction so don't allow
    // generate_address to try to be smart about emitting the -1.
    // Otherwise the patching code won't know how to find the
    // instruction to patch.
    addr_opr = LIR_OprFact::address(new LIR_Address(base.result(), PATCHED_ADDR, access.type()));
  } else {
    addr_opr = LIR_OprFact::address(gen->generate_address(base.result(), offset, 0, 0, access.type()));
  }

  if (resolve_in_register) {
    LIR_Opr resolved_addr = gen->new_pointer_register();
    if (needs_patching) {
      __ leal(addr_opr, resolved_addr, lir_patch_normal, access.patch_emit_info());
      access.clear_decorators(C1_NEEDS_PATCHING);
    } else {
      __ leal(addr_opr, resolved_addr);
    }
    access.set_resolved_in_register(true);
    LIR_Opr r = LIR_OprFact::address(new LIR_Address(resolved_addr, access.type()));
    return r;
  } else {
    access.set_resolved_in_register(false);
    return addr_opr;
  }
}

void BarrierSetC1::store_at(LIRAccess& access, LIR_Opr value) {
  DecoratorSet decorators = access.decorators();
  bool in_heap = (decorators & IN_HEAP) != 0;
  assert(in_heap, "not supported yet");

  LIR_Opr resolved = resolve_address(access, false);
  access.set_resolved_addr(resolved);
  // printf("resolved %p IS_ARRAY %d\n", resolved->as_address_ptr(), (decorators & IS_ARRAY) != 0);
  store_at_resolved(access, value);
}

void BarrierSetC1::load_at(LIRAccess& access, LIR_Opr result) {
  DecoratorSet decorators = access.decorators();
  bool in_heap = (decorators & IN_HEAP) != 0;
  assert(in_heap, "not supported yet");

  LIR_Opr resolved = resolve_address(access, false);
  access.set_resolved_addr(resolved);
  load_at_resolved(access, result);
}

void BarrierSetC1::load(LIRAccess& access, LIR_Opr result) {
  DecoratorSet decorators = access.decorators();
  bool in_heap = (decorators & IN_HEAP) != 0;
  assert(!in_heap, "consider using load_at");
  load_at_resolved(access, result);
}

LIR_Opr BarrierSetC1::atomic_cmpxchg_at(LIRAccess& access, LIRItem& cmp_value, LIRItem& new_value) {
  DecoratorSet decorators = access.decorators();
  bool in_heap = (decorators & IN_HEAP) != 0;
  assert(in_heap, "not supported yet");

  access.load_address();

  LIR_Opr resolved = resolve_address(access, true);
  access.set_resolved_addr(resolved);
  return atomic_cmpxchg_at_resolved(access, cmp_value, new_value);
}

LIR_Opr BarrierSetC1::atomic_xchg_at(LIRAccess& access, LIRItem& value) {
  DecoratorSet decorators = access.decorators();
  bool in_heap = (decorators & IN_HEAP) != 0;
  assert(in_heap, "not supported yet");

  access.load_address();

  LIR_Opr resolved = resolve_address(access, true);
  access.set_resolved_addr(resolved);
  return atomic_xchg_at_resolved(access, value);
}

LIR_Opr BarrierSetC1::atomic_add_at(LIRAccess& access, LIRItem& value) {
  DecoratorSet decorators = access.decorators();
  bool in_heap = (decorators & IN_HEAP) != 0;
  assert(in_heap, "not supported yet");

  access.load_address();

  LIR_Opr resolved = resolve_address(access, true);
  access.set_resolved_addr(resolved);
  return atomic_add_at_resolved(access, value);
}

static int test_counter = 0;

void print_test_counter() {printf("test_counter %d %p\n", test_counter, &test_counter);}

void BarrierSetC1::store_at_resolved(LIRAccess& access, LIR_Opr value) {
  DecoratorSet decorators = access.decorators();
  bool is_volatile = (((decorators & MO_SEQ_CST) != 0) || AlwaysAtomicAccesses);
  bool needs_patching = (decorators & C1_NEEDS_PATCHING) != 0;
  bool mask_boolean = (decorators & C1_MASK_BOOLEAN) != 0;
  bool is_array = (decorators & IS_ARRAY) != 0;
  LIRGenerator* gen = access.gen();

  if (mask_boolean) {
    value = gen->mask_boolean(access.base().opr(), value, access.access_emit_info());
  }

  bool reference_type = (is_reference_type(value.type()) ||  is_reference_type(access.type()));

  if (Universe::heap_event_stub_in_C1_LIR && Universe::enable_heap_event_logging && reference_type) {
    // printf("access.resolved_addr()->as_address_ptr() %d\n", LIR_OprFact::address(access.resolved_addr()->as_address_ptr()).is_address());
    // printf("value.is_constant() %d value.is_register() %d value.is_address() %d\n", value.is_constant(), value.is_register(), value.is_address());
    // printf("Universe::heap_event_counter %ld\n", Universe::heap_event_counter);
    LIR_Opr heap_event_counter_addr_reg = gen->new_pointer_register();
    LIR_Opr heap_events_addr_reg = gen->new_pointer_register();
    LIR_Opr heap_events_idx = gen->new_register(T_INT);
    LIR_Opr size_heap_event = gen->new_register(T_INT);
    LabelObj* pass_through = new LabelObj();
    BasicTypeList signature;
    if (Universe::enable_heap_graph_verify)
      gen->call_runtime(&signature, new LIR_OprList(), CAST_FROM_FN_PTR(address, Universe::lock_mutex_heap_event), (ValueType*)voidType, NULL);
    
    {
      LIR_Opr counter = gen->new_register(T_INT);
      __ move(LIR_OprFact::intConst(sizeof(Universe::HeapEvent)), size_heap_event);
      __ move(LIR_OprFact::longConst((uint64_t)&Universe::heap_event_counter), heap_event_counter_addr_reg);
      LIR_Address* heap_event_counter_addr = new LIR_Address(heap_event_counter_addr_reg, 0, T_INT);
      __ load(heap_event_counter_addr, counter);
      __ move(LIR_OprFact::longConst((uint64_t)&Universe::heap_events), heap_events_addr_reg);
      __ shift_left(counter, 5, heap_events_idx);

      __ add(heap_events_addr_reg, heap_events_idx, heap_events_addr_reg);
      LIR_Address* heap_events_addr_type = new LIR_Address(heap_events_addr_reg, 0, T_LONG);
      BasicType src_type = (value.is_constant() && value.as_jobject() != NULL) ? T_OBJECT : T_LONG; //Extra additions must be done to constant object pointers.
      LIR_Address* heap_events_addr_src = new LIR_Address(heap_events_addr_reg, 8, src_type);
      LIR_Address* heap_events_addr_dst = new LIR_Address(heap_events_addr_reg, 16, T_LONG);
      
      __ store(LIR_OprFact::longConst(Universe::FieldSet), heap_events_addr_type);
      __ store(value, heap_events_addr_src);
      
      LIR_Address* orig_addr = access.resolved_addr()->as_address_ptr();      
      LIR_Address* field_addr;

      if (orig_addr->index()->is_valid()) {
        LIR_Opr __reg__ = gen->new_pointer_register();
        field_addr = new LIR_Address(orig_addr->base(), orig_addr->index(), orig_addr->disp(), orig_addr->type());
        __ leal(field_addr, __reg__);
        __ store(__reg__, heap_events_addr_dst);
      } else if (orig_addr->disp() != 0) {
        LIR_Opr __reg__ = gen->new_pointer_register();
        field_addr = new LIR_Address(orig_addr->base(), orig_addr->disp(), orig_addr->type());
        __ leal(field_addr, __reg__);
        __ store(__reg__, heap_events_addr_dst);
      } else {
        __ store(orig_addr->base(), heap_events_addr_dst);
      }

      __ add(counter, LIR_OprFact::intConst(1L), counter);
      if (Universe::verify_heap_graph) {
        __ store(counter, heap_event_counter_addr);
        gen->call_runtime(&signature, new LIR_OprList(), CAST_FROM_FN_PTR(address, Universe::verify_heap_graph), (ValueType*)voidType, NULL);
      } else {
        __ cmp(LIR_Condition::lir_cond_less, counter, LIR_OprFact::intConst(Universe::max_heap_events));
        __ branch(LIR_Condition::lir_cond_less, pass_through->label()); 
        __ move(LIR_OprFact::intConst(0), counter);
        __ branch_destination(pass_through->label());
        __ store(counter, heap_event_counter_addr);
      }
    }

    if (Universe::enable_heap_graph_verify)
      gen->call_runtime(&signature, new LIR_OprList(), CAST_FROM_FN_PTR(address, Universe::unlock_mutex_heap_event), (ValueType*)voidType, NULL);
  }

  if (is_volatile) {
    __ membar_release();
  }

  LIR_PatchCode patch_code = needs_patching ? lir_patch_normal : lir_patch_none;
  if (is_volatile && !needs_patching) {
    gen->volatile_field_store(value, access.resolved_addr()->as_address_ptr(), access.access_emit_info());
  } else {
    __ store(value, access.resolved_addr()->as_address_ptr(), access.access_emit_info(), patch_code);
  }

  if (is_volatile && !support_IRIW_for_not_multiple_copy_atomic_cpu) {
    __ membar();
  }
}

void BarrierSetC1::load_at_resolved(LIRAccess& access, LIR_Opr result) {
  LIRGenerator *gen = access.gen();
  DecoratorSet decorators = access.decorators();
  bool is_volatile = (((decorators & MO_SEQ_CST) != 0) || AlwaysAtomicAccesses);
  bool needs_patching = (decorators & C1_NEEDS_PATCHING) != 0;
  bool mask_boolean = (decorators & C1_MASK_BOOLEAN) != 0;
  bool in_native = (decorators & IN_NATIVE) != 0;

  if (support_IRIW_for_not_multiple_copy_atomic_cpu && is_volatile) {
    __ membar();
  }

  LIR_PatchCode patch_code = needs_patching ? lir_patch_normal : lir_patch_none;
  if (in_native) {
    __ move_wide(access.resolved_addr()->as_address_ptr(), result);
  } else if (is_volatile && !needs_patching) {
    gen->volatile_field_load(access.resolved_addr()->as_address_ptr(), result, access.access_emit_info());
  } else {
    __ load(access.resolved_addr()->as_address_ptr(), result, access.access_emit_info(), patch_code);
  }

  if (is_volatile) {
    __ membar_acquire();
  }

  /* Normalize boolean value returned by unsafe operation, i.e., value  != 0 ? value = true : value false. */
  if (mask_boolean) {
    LabelObj* equalZeroLabel = new LabelObj();
    __ cmp(lir_cond_equal, result, 0);
    __ branch(lir_cond_equal, equalZeroLabel->label());
    __ move(LIR_OprFact::intConst(1), result);
    __ branch_destination(equalZeroLabel->label());
  }
}

LIR_Opr BarrierSetC1::atomic_cmpxchg_at_resolved(LIRAccess& access, LIRItem& cmp_value, LIRItem& new_value) {
  LIRGenerator *gen = access.gen();
  return gen->atomic_cmpxchg(access.type(), access.resolved_addr(), cmp_value, new_value);
}

LIR_Opr BarrierSetC1::atomic_xchg_at_resolved(LIRAccess& access, LIRItem& value) {
  LIRGenerator *gen = access.gen();
  return gen->atomic_xchg(access.type(), access.resolved_addr(), value);
}

LIR_Opr BarrierSetC1::atomic_add_at_resolved(LIRAccess& access, LIRItem& value) {
  LIRGenerator *gen = access.gen();
  return gen->atomic_add(access.type(), access.resolved_addr(), value);
}

void BarrierSetC1::generate_referent_check(LIRAccess& access, LabelObj* cont) {
  // We might be reading the value of the referent field of a
  // Reference object in order to attach it back to the live
  // object graph. If G1 is enabled then we need to record
  // the value that is being returned in an SATB log buffer.
  //
  // We need to generate code similar to the following...
  //
  // if (offset == java_lang_ref_Reference::referent_offset()) {
  //   if (src != NULL) {
  //     if (klass(src)->reference_type() != REF_NONE) {
  //       pre_barrier(..., value, ...);
  //     }
  //   }
  // }

  bool gen_pre_barrier = true;     // Assume we need to generate pre_barrier.
  bool gen_offset_check = true;    // Assume we need to generate the offset guard.
  bool gen_source_check = true;    // Assume we need to check the src object for null.
  bool gen_type_check = true;      // Assume we need to check the reference_type.

  LIRGenerator *gen = access.gen();

  LIRItem& base = access.base().item();
  LIR_Opr offset = access.offset().opr();

  if (offset->is_constant()) {
    LIR_Const* constant = offset->as_constant_ptr();
    jlong off_con = (constant->type() == T_INT ?
                     (jlong)constant->as_jint() :
                     constant->as_jlong());


    if (off_con != (jlong) java_lang_ref_Reference::referent_offset()) {
      // The constant offset is something other than referent_offset.
      // We can skip generating/checking the remaining guards and
      // skip generation of the code stub.
      gen_pre_barrier = false;
    } else {
      // The constant offset is the same as referent_offset -
      // we do not need to generate a runtime offset check.
      gen_offset_check = false;
    }
  }

  // We don't need to generate stub if the source object is an array
  if (gen_pre_barrier && base.type()->is_array()) {
    gen_pre_barrier = false;
  }

  if (gen_pre_barrier) {
    // We still need to continue with the checks.
    if (base.is_constant()) {
      ciObject* src_con = base.get_jobject_constant();
      guarantee(src_con != NULL, "no source constant");

      if (src_con->is_null_object()) {
        // The constant src object is null - We can skip
        // generating the code stub.
        gen_pre_barrier = false;
      } else {
        // Non-null constant source object. We still have to generate
        // the slow stub - but we don't need to generate the runtime
        // null object check.
        gen_source_check = false;
      }
    }
  }
  if (gen_pre_barrier && !PatchALot) {
    // Can the klass of object be statically determined to be
    // a sub-class of Reference?
    ciType* type = base.value()->declared_type();
    if ((type != NULL) && type->is_loaded()) {
      if (type->is_subtype_of(gen->compilation()->env()->Reference_klass())) {
        gen_type_check = false;
      } else if (type->is_klass() &&
                 !gen->compilation()->env()->Object_klass()->is_subtype_of(type->as_klass())) {
        // Not Reference and not Object klass.
        gen_pre_barrier = false;
      }
    }
  }

  if (gen_pre_barrier) {
    // We can have generate one runtime check here. Let's start with
    // the offset check.
    // Allocate temp register to base and load it here, otherwise
    // control flow below may confuse register allocator.
    LIR_Opr base_reg = gen->new_register(T_OBJECT);
    __ move(base.result(), base_reg);
    if (gen_offset_check) {
      // if (offset != referent_offset) -> continue
      // If offset is an int then we can do the comparison with the
      // referent_offset constant; otherwise we need to move
      // referent_offset into a temporary register and generate
      // a reg-reg compare.

      LIR_Opr referent_off;

      if (offset->type() == T_INT) {
        referent_off = LIR_OprFact::intConst(java_lang_ref_Reference::referent_offset());
      } else {
        assert(offset->type() == T_LONG, "what else?");
        referent_off = gen->new_register(T_LONG);
        __ move(LIR_OprFact::longConst(java_lang_ref_Reference::referent_offset()), referent_off);
      }
      __ cmp(lir_cond_notEqual, offset, referent_off);
      __ branch(lir_cond_notEqual, cont->label());
    }
    if (gen_source_check) {
      // offset is a const and equals referent offset
      // if (source == null) -> continue
      __ cmp(lir_cond_equal, base_reg, LIR_OprFact::oopConst(NULL));
      __ branch(lir_cond_equal, cont->label());
    }
    LIR_Opr src_klass = gen->new_register(T_METADATA);
    if (gen_type_check) {
      // We have determined that offset == referent_offset && src != null.
      // if (src->_klass->_reference_type == REF_NONE) -> continue
      gen->load_klass(base_reg, src_klass, NULL);
      LIR_Address* reference_type_addr = new LIR_Address(src_klass, in_bytes(InstanceKlass::reference_type_offset()), T_BYTE);
      LIR_Opr reference_type = gen->new_register(T_INT);
      __ move(reference_type_addr, reference_type);
      __ cmp(lir_cond_equal, reference_type, LIR_OprFact::intConst(REF_NONE));
      __ branch(lir_cond_equal, cont->label());
    }
  }
}
