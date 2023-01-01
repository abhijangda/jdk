import sys
import re

file1 = sys.argv[1]
file2 = sys.argv[2]

def slurp(file):
  f = open(file)
  s = f.read()
  f.close()
  return s

class CallGraphNode:
  def __init__(self, parent, func):
    self.parent = parent
    self.func = func
    self.children = []

  def add(self, child):
    self.children.append(child)

def parseCallTrace(s):
  rootNode = None
  stack = []
  for d in re.findall(r'.+', s):
    mode = None
    if d[0] == ">":
      mode = "start"
    elif d[0] == "<":
      mode = "end"
    stackdepth = int(re.findall(r'\[(\d+)\]', d)[0])
    func = d[d.find(']', d.find(']')) + 1:]
    parent = stack[-1] if len(stack) > 0 else None
    if mode == "start":
      child = CallGraphNode(parent, func)
      if parent == None:
        rootNode = child
      if parent != None:
        parent.add(child)
      stack.append(child)
      assert stackdepth == len(stack) - 1, "Not same " +str(stackdepth) + " == " + str(len(stack) - 1) + " for " + d
    else:
      stack.pop()
      assert stackdepth == len(stack), "Not same " +str(stackdepth) + " == " + str(len(stack)) + " for " + d
  return rootNode

s1 = slurp(file1)
s2 = slurp(file2)

firstRoot = parseCallTrace(s1)
secondRoot = parseCallTrace(s2)

