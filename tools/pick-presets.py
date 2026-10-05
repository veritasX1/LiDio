#!/usr/bin/env python3
"""Card ef3a3dfb: picks the Milkdrop presets LiDio ships from projectM's "cream of the crop" pack (public domain, see its
LICENSE.md): per category the smallest presets that need no texture files, so the app stays small. Out: app/src/main/assets/milkdrop.
  tools/pick-presets.py <presets-cream-of-the-crop folder> [per category, default 24]"""
import os, re, shutil, sys
src, per = sys.argv[1], int(sys.argv[2]) if len(sys.argv) > 2 else 24
out = os.path.join(os.path.dirname(__file__), "..", "android", "app", "src", "main", "assets", "milkdrop")
shutil.rmtree(out, ignore_errors=True); os.makedirs(out)
total = 0
for cat in sorted(d for d in os.listdir(src) if os.path.isdir(os.path.join(src, d)) and not d.startswith("!")):
    files = []
    for root, _, names in os.walk(os.path.join(src, cat)):
        for n in names:
            if not n.endswith(".milk"): continue
            p = os.path.join(root, n); text = open(p, encoding="latin-1").read()
            if re.search(r"sampler_(?!main|fc_|fw_|pw_|pc_|noise|blur)", text): continue   # needs its own texture
            files.append((os.path.getsize(p), p))
    files.sort()
    # spread over the sizes instead of only the tiniest (those are often very plain)
    pick = [files[i * len(files) // per] for i in range(min(per, len(files)))]
    os.makedirs(os.path.join(out, cat))
    for _, p in pick:
        name = re.sub(r"[^\w\-. ,()&+']", "_", os.path.basename(p))
        shutil.copy(p, os.path.join(out, cat, name)); total += 1
print(total, "Presets,", sum(os.path.getsize(os.path.join(r, f)) for r, _, fs in os.walk(out) for f in fs) // 1024, "KB")
