#!/usr/bin/env python3
"""Gera guide.xml para a lista de demonstração (demo.m3u), com programas à volta da hora atual.

Uso: make-guide.py <pasta da demo>. Os títulos são de filmes abertos da Blender Foundation.
"""
import datetime
import re
import sys
from xml.sax.saxutils import escape, quoteattr

SHOWS = [
    ("Tears of Steel", "A group of scientists tries to save the world from robots in Amsterdam."),
    ("Sintel", "A young woman searches for the baby dragon she raised."),
    ("Elephants Dream", "Two men explore a strange, endless machine."),
    ("Big Buck Bunny", "A giant rabbit takes on three bullying rodents."),
    ("Cosmos Laundromat", "A suicidal sheep meets a mysterious salesman."),
    ("Spring", "A shepherd girl and her dog face ancient spirits."),
    ("Agent 327", "A secret agent goes undercover in a barbershop."),
    ("Caminandes", "A llama tries to get to the other side of a fence."),
]

folder = sys.argv[1]
m3u = open(f"{folder}/demo.m3u", encoding="utf-8").read()
channels = re.findall(r'tvg-id="([^"]+)" tvg-name="([^"]+)" tvg-logo="([^"]+)"', m3u)

now = datetime.datetime.now(datetime.timezone.utc).replace(minute=0, second=0, microsecond=0)
fmt = lambda t: t.strftime("%Y%m%d%H%M%S +0000")
out = ['<?xml version="1.0" encoding="UTF-8"?>', '<tv generator-info-name="goattv-demo">']
for cid, name, logo in channels:
    out.append(f'  <channel id={quoteattr(cid)}><display-name>{escape(name)}</display-name><icon src={quoteattr(logo)}/></channel>')
for i, (cid, _, _) in enumerate(channels):
    t = now - datetime.timedelta(hours=6)
    k = i
    while t < now + datetime.timedelta(hours=18):
        title, desc = SHOWS[k % len(SHOWS)]
        length = datetime.timedelta(minutes=(30, 60, 90)[(k + i) % 3])
        out.append(f'  <programme start="{fmt(t)}" stop="{fmt(t + length)}" channel={quoteattr(cid)}>'
                   f'<title>{escape(title)}</title><desc>{escape(desc)}</desc></programme>')
        t += length
        k += 1
out.append("</tv>")
open(f"{folder}/guide.xml", "w", encoding="utf-8").write("\n".join(out) + "\n")
print(f"guide.xml: {len(channels)} canais")
