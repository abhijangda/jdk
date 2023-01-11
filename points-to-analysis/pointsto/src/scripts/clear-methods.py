import sys

def methodToCare(method):
  return method != "NULL" and method.find("java.") != 0 and method.find("jdk.") != 0 and method.find("sun.") != 0

f = open(sys.argv[1])
output = ""

for line in f.readlines():
  if "{" in line:
    output += line
  elif line[0] == "[":
    # print(line, line.split())
    method = line.split(',')[1].strip()
    if methodToCare(method):
      # print(method)
      # print(method == "NULL" and method.find("java.") == 0 and method.find("jdk.") == 0 and method.find("sun.") == 0 and "<clinit>" in method)
      # sys.exit(0)
      output += line

f.close()

# print(output)

f = open(sys.argv[2], "w")
f.write(output)
f.close()