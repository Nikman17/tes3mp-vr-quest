with open("/tmp/gradlew", "rb") as f:
    lines = f.readlines()
for i, line in enumerate(lines[60:70], start=61):
    print(f"L{i}: {repr(line)}")
print("---")
for i, line in enumerate(lines[118:125], start=119):
    print(f"L{i}: {repr(line)}")
