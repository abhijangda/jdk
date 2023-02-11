import re
import sys

f = open(sys.argv[1])

callstacks = []
callstack = []

for line in f.readlines():
  if (line.strip() == ""):
    callstacks.append(callstack)
    callstack = []
  else:
    callstack.append(line)
f.close()
# print (callstacks)

termquerystacks = set()

d = "org.apache.lucene.index.TermBuffer.set"
# d = "org.apache.lucene.index.IndexReader.ensureOpen"
# d = "org.apache.lucene.index.SegmentReader.docFreq"
# d = "TermInfosReader.ensureIndexIsRead"
# d = "org.apache.lucene.index.TermInfosReader.get"

for callstack in callstacks:
  found = False
  for f in callstack:
    if f.find(d) != -1:
      found = True
  strcallstack = ""
  if found:
    callstack.reverse()
    for f in callstack:
      if f.find(d) != -1:
        strcallstack += f
        break
      strcallstack += f
    termquerystacks.add(strcallstack)

for stack in termquerystacks:
  print(stack)