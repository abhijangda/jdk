import sys
import re

file1 = sys.argv[1]
file2 = sys.argv[2]

def slurp(file):
  f = open(file)
  s = f.read()
  f.close()
  return s

def parseCallEdges(s):
  edges = set()
  for d in re.findall(r'.+', s):
    edges.add(d)
  return edges

s1 = slurp(file1)
s2 = slurp(file2)

firstEdges = parseCallEdges(s1)
secondEdges = parseCallEdges(s2)

print("Edges of first", len(firstEdges))
print("Edges of second", len(secondEdges))

found = 0
notfound = 0
for e in firstEdges:
  if e in secondEdges:
    #print(e)
    found += 1
  elif "clinit" not in e:
    # print(e)
    notfound += 1
  # else:
  #   print(e)

print("Edges of first found in second: ", found, "Edges NOT found in second: ", notfound)

found = 0
notfound = 0
for e in secondEdges:
  if e in firstEdges:
    #print(e)
    found += 1
  elif "clinit" not in e:
    print(e)
    notfound += 1  

print("Edges of second found in first: ", found, "Edges NOT found in first: ", notfound)
