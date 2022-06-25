#!/bin/bash

trap ctrl_c INT

function ctrl_c() {
  exit
}

GCTYPES=(SerialGC G1GC ZGC ParallelGC ShenandoahGC)

BENCH_PARAMS="-jar dacapo-9.12-MR1-bach.jar -n1 -t1 jython -s default"
VANILLA_JAVA="java"


run_gc_type() {
    echo "Running $1"
    CMD="$VANILLA_JAVA -Xlog:'gc*' -XX:+Use$1 $BENCH_PARAMS"
    OUT=$(eval $CMD)
    TOTAL=$(echo "$OUT" | grep 'Totals:         ' | sed -e 's/ .* / /' | cut -d ' ' -f2)
    # do average of 5 runs (doing 4, as we already have 1 run)
    RUNS=($TOTAL)
    for i in {1..4}; do
        _OUT=$(eval $CMD)
        RUNS+=($(echo "$_OUT" | grep 'Totals:         ' | sed -e 's/ .* / /' | cut -d ' ' -f2))
    done

    # get average
    AVERAGE=0
    for run in ${RUNS[@]}; do
        TR_RUN=$(echo "$run" | tr -d 'ms')
        AVERAGE=$(($AVERAGE + $TR_RUN))
    done
    AVERAGE="$(($AVERAGE / ${#RUNS[@]}))ms"
    
    echo "Average total time taken: $AVERAGE"

    # remove previous and create new one
    FILE=$1_bench.xml
    test -f $FILE && rm $FILE
    touch $FILE

    # prelude
    echo "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" > $FILE
    echo "<benchmark>" >> $FILE
    echo "  <gc>$1</gc>" >> $FILE
    echo "  <total>$TOTAL</total>" >> $FILE
    echo "  <avg-5-run>$AVERAGE</avg-5-run>" >> $FILE
    echo "  <results>" >> $FILE


    if [ "$1" == "SerialGC" ]; then
      YOUNG_GEN=$(echo "$OUT" | awk '/gc     / && /Pause Young/ { printf "%s %s\n", $NF, $3 }')
      while read l; do
        echo "    <result>" >> $FILE
        echo "      <pause>young</pause>" >> $FILE
        echo "      <event>$(echo "$l" | cut -d ' ' -f2 | awk -F"[()]" '{print $2}')</event>" >> $FILE
        echo "      <total>$(echo "$l" | cut -d ' ' -f1)</total>" >> $FILE
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
        echo "    <result>" >> $FILE
        echo "      <pause>young</pause>" >> $FILE
        echo "      <event>$(echo "$l" | cut -d ' ' -f2 | awk -F"[()]" '{print $2}')</event>" >> $FILE
        echo "      <total>$(echo "$l" | cut -d ' ' -f1)</total>" >> $FILE
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
        echo "    <result>" >> $FILE
        echo "      <event>$event</event>" >> $FILE
        echo "      <mark-start>$(echo "$SPAN" | grep "Pause Mark Start" | awk '{print $NF}')</mark-start>" >> $FILE
        echo "      <mark-end>$(echo "$SPAN" | grep "Pause Mark End" | awk '{print $NF}')</mark-end>" >> $FILE
        echo "      <concurrent-mark>$(echo "$SPAN" | grep -v "Free" | grep "Concurrent Mark" | awk '{print $NF}')</concurrent-mark>" >> $FILE
        echo "      <concurrent-mark-free>$(echo "$SPAN" | grep "Concurrent Mark Free" | awk '{print $NF}')</concurrent-mark-free>" >> $FILE
        echo "      <concurrent-non-strong-ref>$(echo "$SPAN" | grep "Concurrent Process Non-Strong References" | awk '{print $NF}')</concurrent-non-strong-ref>" >> $FILE
        echo "      <concurrent-reset-reloc>$(echo "$SPAN" | grep "Concurrent Reset Relocation" | awk '{print $NF}')</concurrent-reset-reloc>" >> $FILE
        echo "      <concurrent-select-reloc>$(echo "$SPAN" | grep "Concurrent Select Relocation" | awk '{print $NF}')</concurrent-select-reloc>" >> $FILE
        echo "      <pause-relocate-start>$(echo "$SPAN" | grep "Pause Relocate Start" | awk '{print $NF}')</pause-relocate-start>" >> $FILE
        echo "      <concurrent-relocate>$(echo "$SPAN" | grep "Concurrent Relocate" | awk '{print $NF}')</concurrent-relocate>" >> $FILE
        echo "    </result>" >> $FILE
      done < <(echo "$EVENTS")
    elif [ "$1" == "ParallelGC" ]; then
      YOUNG_GEN=$(echo "$OUT" | awk '/gc     / && /Pause Young/ { printf "%s %s\n", $NF, $3 }')
      while read l; do
        echo "    <result>" >> $FILE
        echo "      <pause>young</pause>" >> $FILE
        echo "      <event>$(echo "$l" | cut -d ' ' -f2 | awk -F"[()]" '{print $2}')</event>" >> $FILE
        echo "      <total>$(echo "$l" | cut -d ' ' -f1)</total>" >> $FILE
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
        echo "    </result>" >> $FILE

      done < <(echo "$FULL_EVENTS")
    fi

    # postlude
    echo "  </results>" >> $FILE
    echo "</benchmark>" >> $FILE
}

for t in ${GCTYPES[@]}; do
  run_gc_type $t
done
