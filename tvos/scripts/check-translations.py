#!/usr/bin/env python3
"""Lista os textos t("inglês", "português") da app que não têm tradução (nem no Android, nem em translations.json)."""
import glob, json, os, re

root = os.path.join(os.path.dirname(__file__), "..")
android = json.load(open(os.path.join(root, "GoatTV/Resources/android_strings.json")))["en"]
known = {re.sub(r"%\d\$@", "%@", v) for v in android.values()}
extra = json.load(open(os.path.join(root, "GoatTV/Resources/translations.json")))
src = "".join(open(f).read() for f in glob.glob(os.path.join(root, "GoatTV/**/*.swift"), recursive=True))
missing = sorted({m.group(1).replace("\\n", "\n") for m in re.finditer(r'(?<![A-Za-z_])t\("((?:[^"\\]|\\.)*)",', src)} - known - set(extra.get("es", {})))
print("\n".join(missing) if missing else "Tudo traduzido.")
