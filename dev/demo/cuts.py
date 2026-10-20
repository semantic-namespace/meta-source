"""Renders the screen of an asciicast at the last moment each marker text is
on it, as a PNG, to check a recording before publishing it.

    python3 dev/demo/cuts.py CAST AGG OUT-DIR "marker text" ...

Each cut is rendered alone (a one-frame cast), so nothing composites: an old
Pillow composites agg's partial GIF frames wrongly when you seek through the
GIF, which looks like a broken recording when it isn't."""
import json, os, re, subprocess, sys

from PIL import Image

cast, agg, out = sys.argv[1], sys.argv[2], sys.argv[3]
os.makedirs(out, exist_ok=True)
lines = open(cast).read().splitlines()
header, events = lines[0], [json.loads(l) for l in lines[1:]]
plain = lambda t: re.sub(r"\x1b\[[0-9;?]*[A-Za-z]", "", t)
for i, marker in enumerate(sys.argv[4:]):
    found = [e for e in events if marker in plain(e[2])]
    if not found:
        print("no frame shows", repr(marker)); continue
    one = os.path.join(out, "cut_%d.cast" % i)
    with open(one, "w") as f:
        f.write(header + "\n" + json.dumps([0.0, "o", found[-1][2]]) + "\n")
    gif = os.path.join(out, "cut_%d.gif" % i)
    subprocess.run([agg, "--font-family", "DejaVu Sans Mono", "--font-size", "14", "--theme", "monokai", one, gif],
                   check=True, capture_output=True)
    png = os.path.join(out, "cut_%d.png" % i)
    Image.open(gif).convert("RGB").save(png)
    print(png, "<-", repr(marker))
