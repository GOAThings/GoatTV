#!/usr/bin/env python3
"""Importa os textos do GoatTV Android (res/values*/strings_ui.xml) para GoatTV/Resources/android_strings.json.

Uso: scripts/import-android-strings.py <pasta res do GoatTV Android>
Os formatos do Android (%1$s, %2$d) passam a %1$@, %2$@ (ver Strings.fill).
"""
import json, os, re, sys, html

LANGS = {"values": "en", "values-es": "es", "values-pt-rPT": "pt-PT", "values-pt-rBR": "pt-BR", "values-fr": "fr",
         "values-de": "de", "values-it": "it", "values-ru": "ru", "values-tr": "tr", "values-in": "id", "values-hi": "hi",
         "values-ar": "ar", "values-zh-rCN": "zh-CN", "values-ja": "ja", "values-ko": "ko"}

def clean(text):
    text = text.strip()
    if text.startswith('"') and text.endswith('"'):
        text = text[1:-1]
    text = text.replace("\\'", "'").replace('\\"', '"').replace("\\n", "\n").replace("\\@", "@").replace("\\?", "?")
    text = html.unescape(text)
    text = re.sub(r"%(\d+)\$[sd]", r"%\1$@", text)
    text = re.sub(r"%[sd]", "%@", text)
    return text

res = sys.argv[1]
out = {}
for folder, lang in LANGS.items():
    path = os.path.join(res, folder, "strings_ui.xml")
    xml = open(path, encoding="utf-8").read()
    out[lang] = {m.group(1): clean(m.group(2)) for m in re.finditer(r'<string name="([a-z0-9_]+)"[^>]*>(.*?)</string>', xml, re.S)}
    # Plurais: nome_one, nome_other (e os outros que a língua tiver).
    for m in re.finditer(r'<plurals name="([a-z0-9_]+)">(.*?)</plurals>', xml, re.S):
        for q in re.finditer(r'<item quantity="([a-z]+)">(.*?)</item>', m.group(2), re.S):
            out[lang][m.group(1) + "_" + q.group(1)] = clean(q.group(2))
dest = os.path.join(os.path.dirname(__file__), "..", "GoatTV", "Resources", "android_strings.json")
with open(dest, "w", encoding="utf-8") as f:
    json.dump(out, f, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
print(f"{len(out)} línguas, {len(out['en'])} textos")
