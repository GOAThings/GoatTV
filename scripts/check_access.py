#!/usr/bin/env python3
"""
Procura no APK classes que estendem uma classe não pública de outro pacote.
O R8 com `packageScope` gerou isto uma vez e a SHIELD (Android 11) crashou com IllegalAccessError,
enquanto o emulador recente corria bem. Uso: scripts/check_access.py app-release.apk
"""
import re, subprocess, sys, zipfile, tempfile, os
apk = sys.argv[1]
sdk = os.environ.get("ANDROID_HOME", os.path.expanduser("~/Android/Sdk"))
tools = sorted(os.listdir(os.path.join(sdk, "build-tools")))
dexdump = os.path.join(sdk, "build-tools", tools[-1], "dexdump")
classes = {}
with tempfile.TemporaryDirectory() as d:
    with zipfile.ZipFile(apk) as z:
        dexes = [n for n in z.namelist() if re.fullmatch(r"classes\d*\.dex", n)]
        for n in dexes: z.extract(n, d)
    for n in dexes:
        out = subprocess.run([dexdump, "-h", os.path.join(d, n)], capture_output=True).stdout.decode("utf-8", "replace")
        cur = None
        for line in out.splitlines():
            m = re.search(r"Class descriptor\s*:\s*'L([^;]+);'", line)
            if m: cur = {"name": m.group(1)}; classes[cur["name"]] = cur; continue
            if cur is None: continue
            m = re.search(r"Access flags\s*:\s*0x([0-9a-f]+)", line)
            if m and "flags" not in cur: cur["flags"] = int(m.group(1), 16); continue
            m = re.search(r"Superclass\s*:\s*'L([^;]+);'", line)
            if m: cur["super"] = m.group(1)
pkg = lambda n: n.rsplit("/", 1)[0] if "/" in n else ""
bad = []
for c in classes.values():
    s = classes.get(c.get("super", ""))
    if s and not (s.get("flags", 0) & 0x1) and pkg(s["name"]) != pkg(c["name"]):
        bad.append(f'{c["name"]} extends {s["name"]}')
print(f"{len(classes)} classes, {len(bad)} problemas")
for b in bad[:10]: print("  ", b)
sys.exit(1 if bad else 0)
