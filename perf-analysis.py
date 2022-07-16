import os
import subprocess

num_runs = 5

base_jvm_path = "java"
build = "release"
modified_jvm_path = "taskset -c 10 ~/jdk/build/linux-x86_64-server-%s/jdk/bin/java"%build

jvm_command = modified_jvm_path + " -XX:ActiveProcessorCount=1 -XX:-UseTLAB -XX:-UseCompressedOops -XX:TieredStopAtLevel=3 -XX:+UseInterpreter -XX:+UseSerialGC -XX:-UseCompressedClassPointers -Xlog:gc* -XX:NewSize=32769m -XX:MaxNewSize=32769m -Xms32769m -Xmx32769m -XX:+DisableExplicitGC -XX:MetaspaceSize=16384m %s -jar dacapo-9.12-MR1-bach.jar %s -n1 -t1"

instrument_args = "-XX:+InstrumentHeapEvents -XX:-CheckHeapEventGraphWithHeap"

all_benchs = "avrora fop h2 jython luindex lusearch lusearch-fix sunflow xalan pmd".split() #batik eclipse tomcat tradebeans tradesoap

def exec_bench(bench, c):
  s, o = subprocess.getstatusoutput(c)
  return (s, o)
  
all_bench_times = {b: {"baseline": [], "instrument": []} for b in all_benchs}

import re

def parse_time(s, bench):
  if s.find('%s FAILED') != -1:
    return -1
  try:
    return re.findall(r'PASSED in (\d+) msec', s)[0]
  except:
    print(s)
    return -1

for bench in all_benchs:
  c = jvm_command % ("", bench)
  print ("Baseline", c)
  for i in range(num_runs):
    print ("exec", i)
    (s, o) = exec_bench(bench, c)
    t = parse_time(o, bench)
    all_bench_times[bench]["baseline"].append(float(t))
    print (bench, t)

  c = jvm_command % (instrument_args, bench)
  print("Instrument", c)
  for i in range(num_runs):
    print ("exec", i)
    (s, o) = exec_bench(bench, c)
    t = parse_time(o, bench)
    all_bench_times[bench]["instrument"].append(float(t))
    print (bench, t)

def process_times(times_l):
  a = []
  for i in times_l:
    if i != -1:
      a.append(i)
  if a == []:
    a = [1]
  
  return a

from prettytable import PrettyTable
print("Times of benchmark")
for bench in all_benchs:
  baseline_times = process_times(all_bench_times[bench]["baseline"])
  instrument_times = process_times(all_bench_times[bench]["instrument"])

  baseline = sum(baseline_times)/len(baseline_times)
  instrument = sum(instrument_times)/len(instrument_times)
  print("===%s==="%bench, "Average: baseline ", baseline, "instrument:", instrument, "Average Overhead: ", (instrument/baseline))
  tab = PrettyTable()
  tab.add_column("Baseline", all_bench_times[bench]["baseline"])
  tab.add_column("Instrument", all_bench_times[bench]["instrument"])
  print(tab)