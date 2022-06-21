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
    OUT=$($VANILLA_JAVA -XX:+Use$1 $BENCH_PARAMS)
    FILTERED=$(
      echo "$OUT" | sed -n "/-----/,/-----/p" | grep -v -- "-----\|PYBENCH\|Benchmark" | grep -v "^$" \
        | sed -e 's/ \t* *//' | sed -e 's/ * / /g'
    )
    TOTAL=$(echo "$OUT" | grep Totals | sed -e 's/ .* / /' | cut -d ' ' -f2)
    echo "Total time taken: $TOTAL"

    # remove previous and create new one
    FILE=$1_bench.xml
    test -f $FILE && rm $FILE
    touch $FILE

    # prelude
    echo "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" > $FILE
    echo "<benchmark>" >> $FILE
    echo "  <gc>$1</gc>" >> $FILE
    echo "  <total>$TOTAL</total>" >> $FILE
    echo "  <results>" >> $FILE

    # results, line by line
    while read l; do
      echo "    <result>" >> $FILE
      echo "      <name>$(echo $l | cut -d ':' -f1)</name>" >> $FILE
      echo "      <min>$(echo $l | cut -d ' ' -f2)</min>" >> $FILE
      echo "      <avg>$(echo $l | cut -d ' ' -f3)</avg>" >> $FILE
      echo "      <op>$(echo $l | cut -d ' ' -f4)</op>" >> $FILE
      echo "      <over>$(echo $l | cut -d ' ' -f5)</over>" >> $FILE
      echo "    </result>" >> $FILE
    done < <(echo "$FILTERED")

    # postlude
    echo "  </results>" >> $FILE
    echo "</benchmark>" >> $FILE
}

for t in ${GCTYPES[@]}; do
  run_gc_type $t
done
