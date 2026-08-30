import os
src = "/mnt/c/Users/Mykyta/CascadeProjects/windsurf-project-3/tes3mp-vr/android/gradlew"
dst = "/tmp/gradlew"
with open(src, "rb") as f:
    data = f.read().replace(b"\r\n", b"\n").replace(b"\r", b"\n")
with open(dst, "wb") as f:
    f.write(data)
os.chmod(dst, 0o755)
print("Done:", dst)
