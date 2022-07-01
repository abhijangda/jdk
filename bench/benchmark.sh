#!/bin/bash

trap ctrl_c INT

function ctrl_c() {
  exit
}


GCTYPES=(SerialGC G1GC ZGC ParallelGC ShenandoahGC)
PERMS_MEM=(128m 256m 512m 1024m)
PERMS_BENCH=(jython h2)

VANILLA_JAVA="java"

trim_ms() {
  echo $1 | tr -d 'ms'
}

run_gc_type() {
    echo "Running $1 $2 $3"
    CMD="$VANILLA_JAVA -Xlog:'gc*' -XX:+Use$1 -Xmx$3 -jar dacapo-9.12-MR1-bach.jar -n1 -t1 $2 -s default"
    OUT=$(eval $CMD)
    RESULT=$?
    if [ $RESULT -ne 0 ]; then
      echo "Error detected. skipping this benchmark."
      return 0; # skip this one
    fi

    # do average of 5 runs
    RUNS=()
    for i in {1..5}; do
      echo "Calculating avg... run $i of 5"
      RUNS+=($(eval $CMD 2> >(grep " in " | awk '{print $7}') > /dev/null))
    done

    # get average
    AVERAGE=0
    for run in ${RUNS[@]}; do
        TR_RUN=$(trim_ms $run)
        AVERAGE=$(($AVERAGE + $TR_RUN))
    done
    AVERAGE="$(($AVERAGE / ${#RUNS[@]}))ms"
    
    echo "Average total time taken: $AVERAGE"

    # remove previous and create new one
    FILE=$1_$2_$3_bench.xml
    test -f $FILE && rm $FILE
    touch $FILE

    # prelude
    echo "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" > $FILE
    echo "<benchmark>" >> $FILE
    echo "  <gc>$1</gc>" >> $FILE
    echo "  <avg-5-run>$AVERAGE</avg-5-run>" >> $FILE
    echo "  <results>" >> $FILE

    TOTAL_GC=0

    if [ "$1" == "SerialGC" ]; then
      YOUNG_GEN=$(echo "$OUT" | awk '/gc     / && /Pause Young/ { printf "%s %s\n", $NF, $3 }')
      while read l; do
        _TOTAL=$(echo "$l" | cut -d ' ' -f1)
        TOTAL_GC=$(echo - | awk "{print $TOTAL_GC + $(trim_ms $_TOTAL) }")
        echo "    <result>" >> $FILE
        echo "      <pause>young</pause>" >> $FILE
        echo "      <event>$(echo "$l" | cut -d ' ' -f2 | awk -F"[()]" '{print $2}')</event>" >> $FILE
        echo "      <total>$_TOTAL</total>" >> $FILE
        echo "    </result>" >> $FILE
      done < <(echo "$YOUNG_GEN")
      FULL_EVENTS=$(echo "$OUT" | awk '/gc,start/ && /Pause Full/' | awk -F"[()]" '{print $2}')
      while read event; do
        SPAN=$(echo "$OUT" | sed -n "/GC($event) Pause Full/,/GC($event) Pause Full/p")
        MARK_TIME=$(echo "$SPAN" | grep 'Mark live objects ' | awk '{print $NF}')
        NEW_ADDR_TIME=$(echo "$SPAN" | grep "Compute new object addresses " | awk '{print $NF}')
        ADJ_POINTERS=$(echo "$SPAN" | grep "Adjust pointers " | awk '{print $NF}')
        MOVE_OBJ=$(echo "$SPAN" | grep "Move objects " | awk '{print $NF}')
        TOTAL_EVENT=$(echo "$SPAN" | tail -n 1 | awk '{print $NF}')
        TOTAL_GC=$(echo - | awk "{print $TOTAL_GC + $(trim_ms $TOTAL_EVENT) }")
        echo "    <result>" >> $FILE
        echo "      <pause>full</pause>" >> $FILE
        echo "      <event>$event</event>" >> $FILE
        echo "      <mark>$MARK_TIME</mark>" >> $FILE
        echo "      <new-addresses>$NEW_ADDR_TIME</new-addresses>" >> $FILE
        echo "      <adjust-pointers>$ADJ_POINTERS</adjust-pointers>" >> $FILE
        echo "      <move-objects>$MOVE_OBJ</move-objects>" >> $FILE
        echo "      <total>$TOTAL_EVENT</total>" >> $FILE
        echo "    </result>" >> $FILE
      done < <(echo "$FULL_EVENTS")
    elif [ "$1" == "G1GC" ]; then
      YOUNG_GEN=$(echo "$OUT" | awk '/gc     / && /Pause Young/ { printf "%s %s\n", $NF, $3 }')
      while read l; do
        _TOTAL=$(echo "$l" | cut -d ' ' -f1)
        TOTAL_GC=$(echo - | awk "{print $TOTAL_GC + $(trim_ms $_TOTAL) }")
        echo "    <result>" >> $FILE
        echo "      <pause>young</pause>" >> $FILE
        echo "      <event>$(echo "$l" | cut -d ' ' -f2 | awk -F"[()]" '{print $2}')</event>" >> $FILE
        echo "      <total>$_TOTAL</total>" >> $FILE
        echo "    </result>" >> $FILE
      done < <(echo "$YOUNG_GEN")
      FULL_EVENTS=$(echo "$OUT" | awk '/gc,start/ && /Pause Full/' | awk -F"[()]" '{print $2}')
      while read event; do
        SPAN=$(echo "$OUT" | sed -n "/GC($event) Pause Full/,/GC($event) Pause Full/p")
        MARK_TIME=$(echo "$SPAN" | grep 'Mark live objects ' | awk '{print $NF}')
        PREP_COMPACT=$(echo "$SPAN" | grep "Prepare for compaction " | awk '{print $NF}')
        ADJ_POINTERS=$(echo "$SPAN" | grep "Adjust pointers " | awk '{print $NF}')
        COMP_HEAP=$(echo "$SPAN" | grep "Compact heap " | awk '{print $NF}')
        TOTAL_EVENT=$(echo "$SPAN" | tail -n 1 | awk '{print $NF}')
        TOTAL_GC=$(echo - | awk "{print $TOTAL_GC + $(trim_ms $TOTAL_EVENT) }")
        echo "    <result>" >> $FILE
        echo "      <pause>full</pause>" >> $FILE
        echo "      <event>$event</event>" >> $FILE
        echo "      <mark>$MARK_TIME</mark>" >> $FILE
        echo "      <prepare-compaction>$PREP_COMPACT</prepare-compaction>" >> $FILE
        echo "      <adjust-pointers>$ADJ_POINTERS</adjust-pointers>" >> $FILE
        echo "      <compact-heap>$COMP_HEAP</compact-heap>" >> $FILE
        echo "      <total>$TOTAL_EVENT</total>" >> $FILE
        echo "    </result>" >> $FILE
      done < <(echo "$FULL_EVENTS")
    elif [ "$1" == "ZGC" ]; then
      EVENTS=$(echo "$OUT" | awk '/gc,start/ && /Garbage Collection/' | awk -F"[()]" '{print $2}')
      while read event; do
        SPAN=$(echo "$OUT" | sed -n "/GC($event) Garbage Collection/,/GC($event) Garbage Collection/p")
        MARK_START=$(echo "$SPAN" | grep "Pause Mark Start" | awk '{print $NF}')
        MARK_END=$(echo "$SPAN" | grep "Pause Mark End" | awk '{print $NF}')
        CONCURRENT_MARK=$(echo "$SPAN" | grep -v "Free" | grep "Concurrent Mark" | awk '{print $NF}')
        CONCURRENT_MARK_FREE=$(echo "$SPAN" | grep "Concurrent Mark Free" | awk '{print $NF}')
        CONCURRENT_NON_STRONG_REF=$(echo "$SPAN" | grep "Concurrent Process Non-Strong References" | awk '{print $NF}')
        CONCURRENT_RESET_RELOC=$(echo "$SPAN" | grep "Concurrent Reset Relocation" | awk '{print $NF}')
        CONCURRENT_SELECT_RELOC=$(echo "$SPAN" | grep "Concurrent Select Relocation" | awk '{print $NF}')
        PAUSE_RELOC_START=$(echo "$SPAN" | grep "Pause Relocate Start" | awk '{print $NF}')
        CONCURRENT_RELOC=$(echo "$SPAN" | grep "Concurrent Relocate" | awk '{print $NF}')
        TOTAL_EVENT=$(echo - | awk "{print $(trim_ms $MARK_START) + $(trim_ms $MARK_END) + $(trim_ms $CONCURRENT_MARK) + $(trim_ms $CONCURRENT_MARK_FREE) + $(trim_ms $CONCURRENT_NON_STRONG_REF) + $(trim_ms $CONCURRENT_RESET_RELOC) + $(trim_ms $CONCURRENT_SELECT_RELOC) + $(trim_ms $PAUSE_RELOC_START) + $(trim_ms $CONCURRENT_RELOC) }")
        TOTAL_GC=$(echo - | awk "{ print $TOTAL_GC + $TOTAL_EVENT }")
        echo "    <result>" >> $FILE
        echo "      <event>$event</event>" >> $FILE
        echo "      <mark-start>$MARK_START</mark-start>" >> $FILE
        echo "      <mark-end>$MARK_END</mark-end>" >> $FILE
        echo "      <concurrent-mark>$CONCURRENT_MARK</concurrent-mark>" >> $FILE
        echo "      <concurrent-mark-free>$CONCURRENT_MARK_FREE</concurrent-mark-free>" >> $FILE
        echo "      <concurrent-non-strong-ref>$CONCURRENT_NON_STRONG_REF</concurrent-non-strong-ref>" >> $FILE
        echo "      <concurrent-reset-reloc>$CONCURRENT_RESET_RELOC</concurrent-reset-reloc>" >> $FILE
        echo "      <concurrent-select-reloc>$CONCURRENT_SELECT_RELOC</concurrent-select-reloc>" >> $FILE
        echo "      <pause-relocate-start>$PAUSE_RELOC_START</pause-relocate-start>" >> $FILE
        echo "      <concurrent-relocate>$CONCURRENT_RELOC</concurrent-relocate>" >> $FILE
        echo "      <total>$(echo "$TOTAL_EVENT")ms</total>" >> $FILE
        echo "    </result>" >> $FILE
      done < <(echo "$EVENTS")
    elif [ "$1" == "ParallelGC" ]; then
      YOUNG_GEN=$(echo "$OUT" | awk '/gc     / && /Pause Young/ { printf "%s %s\n", $NF, $3 }')
      while read l; do
        _TOTAL=$(echo "$l" | cut -d ' ' -f1)
        TOTAL_GC=$(echo - | awk "{print $TOTAL_GC + $(trim_ms $_TOTAL) }")
        echo "    <result>" >> $FILE
        echo "      <pause>young</pause>" >> $FILE
        echo "      <event>$(echo "$l" | cut -d ' ' -f2 | awk -F"[()]" '{print $2}')</event>" >> $FILE
        echo "      <total>$_TOTAL</total>" >> $FILE
        echo "    </result>" >> $FILE
      done < <(echo "$YOUNG_GEN")
      FULL_EVENTS=$(echo "$OUT" | awk '/gc,start/ && /Pause Full/' | awk -F"[()]" '{print $2}')
      while read event; do
        SPAN=$(echo "$OUT" | sed -n "/GC($event) Pause Full/,/GC($event) Pause Full/p")
        MARK_TIME=$(echo "$SPAN" | grep 'Marking Phase ' | awk '{print $NF}')
        SUMMARY=$(echo "$SPAN" | grep 'Summary Phase ' | awk '{print $NF}')
        ADJ_ROOTS=$(echo "$SPAN" | grep 'Adjust Roots ' | awk '{print $NF}')
        COMPACTION_PHASE=$(echo "$SPAN" | grep 'Compaction Phase ' | awk '{print $NF}')
        POST_COMPACT=$(echo "$SPAN" | grep 'Post Compact ' | awk '{print $NF}')
        TOTAL_EVENT=$(echo "$SPAN" | tail -n 1 | awk '{print $NF}')
        TOTAL_GC=$(echo - | awk "{print $TOTAL_GC + $(trim_ms $TOTAL_EVENT) }")
        echo "    <result>" >> $FILE
        echo "      <pause>full</pause>" >> $FILE
        echo "      <event>$event</event>" >> $FILE
        echo "      <mark>$MARK_TIME</mark>" >> $FILE
        echo "      <summary>$SUMMARY</summary>" >> $FILE
        echo "      <adjust-roots>$ADJ_ROOTS</adjust-roots>" >> $FILE
        echo "      <compaction-phase>$COMPACTION_PHASE</compaction-phase>" >> $FILE
        echo "      <post-compaction>$POST_COMPACT</post-compaction>" >> $FILE
        echo "      <total>$TOTAL_EVENT</total>" >> $FILE
        echo "    </result>" >> $FILE
      done < <(echo "$FULL_EVENTS")
    elif [ "$1" == "ShenandoahGC" ]; then
      FULL_EVENTS=$(echo "$OUT" | awk '/gc,start/ && /Concurrent reset/' | awk -F"[()]" '{print $2}')
      while read event; do
        SPAN=$(echo "$OUT" | sed -n "/GC($event) Concurrent reset/,/GC($event) Concurrent cleanup /p")
        CONCURRENT_RESET=$(echo "$SPAN" | grep 'Concurrent reset ' | awk '{print $NF}')
        PAUSE_INIT_MARK=$(echo "$SPAN" | grep 'Pause Init Mark (unload classes) ' | awk '{print $NF}')
        CONCURRENT_MARK_ROOTS=$(echo "$SPAN" | grep 'Concurrent marking roots ' | awk '{print $NF}')
        CONCURRENT_MARK=$(echo "$SPAN" | grep 'Concurrent marking (unload classes) ' | awk '{print $NF}')
        PAUSE_FINAL_MARK=$(echo "$SPAN" | grep 'Pause Final Mark (unload classes) ' | awk '{print $NF}')
        CONCURRENT_THREAD_ROOTS=$(echo "$SPAN" | grep 'Concurrent thread roots ' | awk '{print $NF}')
        CONCURRENT_WEAK_REF=$(echo "$SPAN" | grep 'Concurrent weak references ' | awk '{print $NF}')
        CONCURRENT_WEAK_ROOTS=$(echo "$SPAN" | grep 'Concurrent weak roots ' | awk '{print $NF}')
        CONCURRENT_CLEANUP=$(echo "$SPAN" | grep 'Concurrent cleanup ' | awk '{print $NF}')
        TOTAL_EVENT=$(echo - | awk "{ print $(trim_ms $CONCURRENT_RESET) + $(trim_ms $PAUSE_INIT_MARK) + $(trim_ms $CONCURRENT_MARK_ROOTS) + $(trim_ms $CONCURRENT_MARK) + $(trim_ms $PAUSE_FINAL_MARK) + $(trim_ms $CONCURRENT_THREAD_ROOTS) + $(trim_ms $CONCURRENT_WEAK_REF) + $(trim_ms $CONCURRENT_WEAK_ROOTS) + $(trim_ms $CONCURRENT_CLEANUP) }")
        TOTAL_GC=$(echo - | awk "{print $TOTAL_GC + $(trim_ms $TOTAL_EVENT) }")
        echo "    <result>" >> $FILE
        echo "      <event>$event</event>" >> $FILE
        echo "      <concurrent-reset>$CONCURRENT_RESET</concurrent-reset>" >> $FILE
        echo "      <pause-init-mark>$PAUSE_INIT_MARK</pause-init-mark>" >> $FILE
        echo "      <concurrent-mark-roots>$CONCURRENT_MARK_ROOTS</concurrent-mark-roots>" >> $FILE
        echo "      <concurrent-mark>$CONCURRENT_MARK</concurrent-mark>" >> $FILE
        echo "      <pause-final-mark>$PAUSE_FINAL_MARK</pause-final-mark>" >> $FILE
        echo "      <concurrent-thread-roots>$CONCURRENT_THREAD_ROOTS</concurrent-thread-roots>" >> $FILE
        echo "      <concurrent-weak-ref>$CONCURRENT_WEAK_REF</concurrent-weak-ref>" >> $FILE
        echo "      <concurrent-weak-roots>$CONCURRENT_WEAK_ROOTS</concurrent-weak-roots>" >> $FILE
        echo "      <concurrent-cleanup>$CONCURRENT_CLEANUP</concurrent-cleanup>" >> $FILE
        echo "      <total>$TOTAL_EVENT</total>" >> $FILE
        echo "    </result>" >> $FILE

      done < <(echo "$FULL_EVENTS")
    fi

    echo "Total GC Time: $TOTAL_GC"

    # postlude
    echo "  </results>" >> $FILE
    echo "  <total-gc>$(echo "$TOTAL_GC")ms</total-gc>" >> $FILE
    echo "</benchmark>" >> $FILE
}

for t in ${GCTYPES[@]}; do
  for mem in ${PERMS_MEM[@]}; do
    for b in ${PERMS_BENCH[@]}; do
      run_gc_type $t $b $mem
    done
  done
done
