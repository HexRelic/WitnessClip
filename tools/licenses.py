#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 HexRelic
"""Reads `group:artifact:version` lines on stdin, downloads each artifact's POM from Google
Maven or Maven Central, and prints a Markdown table of the licences declared there.
Usage:  ./gradlew :app:dependencies --configuration releaseRuntimeClasspath  -> list -> this script"""
import sys, re, urllib.request, concurrent.futures as cf
REPOS = [("Google Maven", "https://dl.google.com/dl/android/maven2/"), ("Maven Central", "https://repo1.maven.org/maven2/")]
def fetch(g, a, v):
    for name, base in REPOS:
        url = f"{base}{g.replace('.', '/')}/{a}/{v}/{a}-{v}.pom"
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "license-check"}), timeout=30) as r:
                return name, url, r.read().decode("utf-8", "replace")
        except Exception:
            continue
    return None, None, None
def licences(g, a, v, depth=0):
    repo, url, pom = fetch(g, a, v)
    if pom is None: return None, None, []
    body = re.sub(r"<!--.*?-->", "", pom, flags=re.S)
    found = re.findall(r"<license>\s*(.*?)</license>", body, flags=re.S)
    out = []
    for f in found:
        n = re.search(r"<name>(.*?)</name>", f, re.S); u = re.search(r"<url>(.*?)</url>", f, re.S)
        out.append(((n.group(1).strip() if n else "?"), (u.group(1).strip() if u else "")))
    if not out and depth < 3:
        p = re.search(r"<parent>(.*?)</parent>", body, re.S)
        if p:
            pg = re.search(r"<groupId>(.*?)</groupId>", p.group(1)).group(1); pa = re.search(r"<artifactId>(.*?)</artifactId>", p.group(1)).group(1); pv = re.search(r"<version>(.*?)</version>", p.group(1)).group(1)
            _, _, out = licences(pg, pa, pv, depth + 1)
    return repo, url, out
rows = [l.strip().split(":") for l in sys.stdin if l.strip()]
with cf.ThreadPoolExecutor(12) as ex:
    res = list(ex.map(lambda r: licences(*r), rows))
print("| Artifact | Version | Licence declared in POM | Source |\n|---|---|---|---|")
bad = 0
for (g, a, v), (repo, url, lic) in zip(rows, res):
    if not lic: bad += 1
    text = "; ".join(f"[{n}]({u})" if u else n for n, u in lic) or "**NOT FOUND**"
    print(f"| `{g}:{a}` | {v} | {text} | [{repo}]({url}) |" if url else f"| `{g}:{a}` | {v} | **POM NOT FOUND** | |")
print(f"\n{len(rows)} artifacts, {bad} without a declared licence.", file=sys.stderr)
