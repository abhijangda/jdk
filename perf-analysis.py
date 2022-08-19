import os
import subprocess

num_runs = 4

base_jvm_path = "java"
build = "release"
modified_jvm_path = "taskset -c 10,11,12,13,14,15,16,17,18,19,20,21,22,23 ~/jdk/build/linux-x86_64-server-%s/jdk/bin/java"%build
#lusearch fixes with -XX:+UnlockDiagnosticVMOptions -XX:-InlineArrayCopy -XX:-InlineClassNatives -XX:-InlineMathNatives -XX:-InlineNatives -XX:-InlineObjectCopy -XX:-InlineObjectHash -XX:-                  InlineReflectionGetCallerClass -XX:-InlineSynchronizedMethods -XX:-InlineThreadNatives -XX:-InlineUnsafeOps -XX:+IncrementalInline -XX:+IncrementalInlineMH -XX:+IncrementalInlineVirtual
config = "-XX:+TieredCompilation -XX:+UseInterpreter"
#"-XX:+UnlockDiagnosticVMOptions -XX:-InlineArrayCopy -XX:-InlineClassNatives -XX:-InlineMathNatives -XX:-InlineNatives -XX:-InlineObjectCopy -XX:-InlineObjectHash -XX:-InlineReflectionGetCallerClass -XX:-InlineSynchronizedMethods -XX:-InlineThreadNatives -XX:-InlineUnsafeOps -XX:+IncrementalInline -XX:+IncrementalInlineMH -XX:+IncrementalInlineVirtual"
inline_copy = "-XX:+UnlockDiagnosticVMOptions -XX:-InlineObjectCopy"
jvm_command = modified_jvm_path + f" -XX:ActiveProcessorCount=1 -XX:-UseTLAB -XX:-UseCompressedOops -XX:+UseSerialGC -XX:-UseCompressedClassPointers -Xlog:gc* -XX:NewSize=32769m -XX:MaxNewSize=32769m -Xms32769m -Xmx32769m -XX:+DisableExplicitGC -XX:-DoEscapeAnalysis -XX:MetaspaceSize=16384m"
dacapo_args = "-jar dacapo-9.12-MR1-bach.jar %s -n1 -t1"

instrument_args = "-XX:+InstrumentHeapEvents -XX:-CheckHeapEventGraphWithHeap -XX:MaxHeapEvents=%s"

all_benchs = "avrora fop h2 jython luindex lusearch sunflow xalan pmd".split() #h2 batik eclipse tomcat tradebeans tradesoap

if "-Tiered" in config:
  all_benchs = all_benchs.replace("h2 ",'')

cannot_use_InlineObjCopy = ["luindex", "h2", "fop"]

def exec_bench(bench, c):
  s, o = subprocess.getstatusoutput(c)
  return (s, o)

all_bench_times = {b: {"baseline": [], "instrument": []} for b in all_benchs}

import re

(s, o) = subprocess.getstatusoutput('git rev-parse HEAD')
print(f"***At git commit {o}*****")

def parse_time(s, bench):
  if s.find('%s FAILED') != -1:
    return -1
  try:
    return re.findall(r'PASSED in (\d+) msec', s)[0]
  except:
    print(s)
    return -1

for bench in all_benchs:
  bench_args = dacapo_args % bench
  bench_c = jvm_command + " " + config
  if bench not in cannot_use_InlineObjCopy:
    bench_c += " " + inline_copy
  c = bench_c + " " + bench_args
  print ("Baseline", c)
  for i in range(num_runs):
    print ("exec", i)
    (s, o) = exec_bench(bench, c)
    t = parse_time(o, bench)
    all_bench_times[bench]["baseline"].append(float(t))
    print (bench, t)

  max_heap_events = str(4*1024*1024)
  c = bench_c + " " + instrument_args%max_heap_events + " " + bench_args
  print("Instrument", c)
  for i in range(num_runs):
    print ("exec", i)
    (s, o) = exec_bench(bench, c)
    t = parse_time(o, bench)
    all_bench_times[bench]["instrument"].append(float(t))
    print (bench, t)

def process_times(times_l, k):
  a = []
  for i in times_l:
    if i != -1:
      a.append(i)
  if a == []:
    a = [1, 1]

  a = sorted(a)
  if (k == "baseline"):
    return a[1:]
  if (k == "instrument"):
    if len(a) == 1:
      return a
    return a[:-1]
  if a == []:
    return [1]

from prettytable import PrettyTable
print("Times of benchmark")
for bench in all_benchs:
  baseline_times = process_times(all_bench_times[bench]["baseline"], "baseline")
  instrument_times = process_times(all_bench_times[bench]["instrument"], "instrument")
  if len(instrument_times) == 0:
    instrument_times = [1]
  try:
    baseline = sum(baseline_times)/len(baseline_times)
    instrument = sum(instrument_times)/len(instrument_times)
    print("===%s==="%bench, "Average: baseline ", baseline, "instrument:", instrument, "Average Overhead: ", (instrument/baseline))
    tab = PrettyTable()
    tab.add_column("Baseline", all_bench_times[bench]["baseline"])
    tab.add_column("Instrument", all_bench_times[bench]["instrument"])
    print(tab)
  except BaseException as err:
    print(err)
