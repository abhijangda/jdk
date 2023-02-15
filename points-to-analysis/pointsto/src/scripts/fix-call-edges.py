import sys
import re

call_sites = sys.argv[1]
call_edges = sys.argv[2]

def slurp(file):
  f = open(file)
  s = f.read()
  f.close()
  return s

def parseCallEdges(s):
  edgesSet = set()
  for d in re.findall(r'.+', s):
    edgesSet.add(d)
  
  edges = []
  for d in edgesSet:
    edges.append(d.split(' '))
  return edges

callEdges = parseCallEdges(slurp(call_edges))

def processCallTraces(s):
  callstacks = []
  callstack = []
  f = open(s, "r+")
  for line in f.readlines():
    if (line.strip() == ""):
      if (callstack == []):
        continue

      callstacks.append(callstack)
      callstack = []
    else:
      line = line[:line.index("(")]
      callstack.append(line)
  f.close()

  return callstacks

callstacks = processCallTraces(call_sites)
newedges = set()

for edge in callEdges:
  validEdge = False
  for stack in callstacks:
    m1idx = -1
    m2idx = -1
    for i in range(0, len(stack)):
      if stack[i] == edge[1][:edge[1].index("(")]:
        m1idx = i
        break
    
    for i in range(0, len(stack)):
      if stack[i] == edge[0][:edge[0].index("(")]:
        m2idx = i
        break

    # if ("removeEldestEntry" in edge[1]):
    #   print(m1idx, m2idx)
    if m1idx != -1 and m2idx != -1 and m1idx <= m2idx:
      validEdge = True
      for i in range(len(stack)):
        if "java." in stack[i]:
          validEdge = False
          break
      
      break
    
  if validEdge:
    newedges.add(" ".join(edge))

print("New Edges ", len(newedges))
for  e in newedges:
  print(e)