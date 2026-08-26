#!/usr/bin/env python3
"""Build BrightControl's launcher mark.

Part of the unified Bright* icon set. Every mark in the collection is drawn on
the same 108x108 adaptive-icon canvas, inside the same 18..90 safe zone, at the
same two stroke weights, in white on black and nothing else. The Light Phone
III panel is black and white; a mark with a mid-tone in it dithers.

Edit MARK below and re-run. The vector outputs need nothing but the standard
library. The raster outputs need Pillow and cairosvg, and are skipped with a
message if those are missing, because the vectors are what actually ship on
API 26 and up.

    python3 tools/icon_mark.py
"""

import os
import re

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# ---- the mark ---------------------------------------------------------------
# Each entry is (path data, stroke width, even-odd fill). A stroke width of 0
# means the path is filled instead of stroked.

MARK = [
    ('M48.66,34.73 L50.45,20.19 A34.00,34.00 0 0 1 57.55,20.19 L59.34,34.73 A20.00,20.00 0 0 1 63.85,36.59 L75.40,27.58 A34.00,34.00 0 0 1 80.42,32.60 L71.41,44.15 A20.00,20.00 0 0 1 73.27,48.66 L87.81,50.45 A34.00,34.00 0 0 1 87.81,57.55 L73.27,59.34 A20.00,20.00 0 0 1 71.41,63.85 L80.42,75.40 A34.00,34.00 0 0 1 75.40,80.42 L63.85,71.41 A20.00,20.00 0 0 1 59.34,73.27 L57.55,87.81 A34.00,34.00 0 0 1 50.45,87.81 L48.66,73.27 A20.00,20.00 0 0 1 44.15,71.41 L32.60,80.42 A34.00,34.00 0 0 1 27.58,75.40 L36.59,63.85 A20.00,20.00 0 0 1 34.73,59.34 L20.19,57.55 A34.00,34.00 0 0 1 20.19,50.45 L34.73,48.66 A20.00,20.00 0 0 1 36.59,44.15 L27.58,32.60 A34.00,34.00 0 0 1 32.60,27.58 L44.15,36.59 A20.00,20.00 0 0 1 48.66,34.73 Z', 5, False),
    ('M42.00,54.00 A12.00,12.00 0 1 1 66.00,54.00 A12.00,12.00 0 1 1 42.00,54.00 Z', 4, False),
]

# Where the mark is written, and at what viewport. 108 is the adaptive-icon
# canvas; 240 is the LightOS splash mark, which is the only place a LightOS
# tool can show a mark of its own.
TARGETS = [
    ('app/src/main/res/drawable/ic_launcher_foreground.xml', 108),
]

# Legacy rasters: (path, pixels, circular mask, inset, transparent plate).
# Inset shrinks the mark inside the plate - a legacy square icon gets no
# launcher mask, so it needs the margin the mask would otherwise have given it.
# A transparent plate is for an adaptive foreground layer, which is composited
# over the plate rather than carrying one of its own.
RASTERS = [
    ('app/src/main/res/mipmap-hdpi/ic_launcher.png', 72, False, 0.72, False),
    ('app/src/main/res/mipmap-hdpi/ic_launcher_round.png', 72, True, 0.72, False),
    ('app/src/main/res/mipmap-mdpi/ic_launcher.png', 48, False, 0.72, False),
    ('app/src/main/res/mipmap-mdpi/ic_launcher_round.png', 48, True, 0.72, False),
    ('app/src/main/res/mipmap-xhdpi/ic_launcher.png', 96, False, 0.72, False),
    ('app/src/main/res/mipmap-xhdpi/ic_launcher_round.png', 96, True, 0.72, False),
    ('app/src/main/res/mipmap-xxhdpi/ic_launcher.png', 144, False, 0.72, False),
    ('app/src/main/res/mipmap-xxhdpi/ic_launcher_round.png', 144, True, 0.72, False),
    ('app/src/main/res/mipmap-xxxhdpi/ic_launcher.png', 192, False, 0.72, False),
    ('app/src/main/res/mipmap-xxxhdpi/ic_launcher_round.png', 192, True, 0.72, False),
]

# Files that are the same in every app: the black plate, and the adaptive-icon
# wrapper that points the launcher at the plate and the mark.
STATIC = [
    ('app/src/main/res/drawable/ic_launcher_background.xml', '<?xml version="1.0" encoding="utf-8"?>\n<!-- Solid black plate. The whole set is black and white; nothing else belongs here. -->\n<vector xmlns:android="http://schemas.android.com/apk/res/android"\n    android:width="108dp"\n    android:height="108dp"\n    android:viewportWidth="108"\n    android:viewportHeight="108">\n    <path\n        android:pathData="M0,0 H108 V108 H0 Z"\n        android:fillColor="#000000" />\n</vector>\n'),
    ('app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml', '<?xml version="1.0" encoding="utf-8"?>\n<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n    <background android:drawable="@drawable/ic_launcher_background" />\n    <foreground android:drawable="@drawable/ic_launcher_foreground" />\n    <monochrome android:drawable="@drawable/ic_launcher_foreground" />\n</adaptive-icon>\n'),
    ('app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml', '<?xml version="1.0" encoding="utf-8"?>\n<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n    <background android:drawable="@drawable/ic_launcher_background" />\n    <foreground android:drawable="@drawable/ic_launcher_foreground" />\n    <monochrome android:drawable="@drawable/ic_launcher_foreground" />\n</adaptive-icon>\n'),
]

STROKE = ('        android:fillColor="#00000000"\n'
          '        android:strokeColor="#FFFFFF"\n'
          '        android:strokeWidth="%g"\n'
          '        android:strokeLineCap="round"\n'
          '        android:strokeLineJoin="round" />')

HEADER = '''<?xml version="1.0" encoding="utf-8"?>
<!--
  BrightControl launcher mark. One of the unified Bright* set: 108 canvas, 18..90
  safe zone, white on black, no greys and no colour anywhere.

  Generated by tools/icon_mark.py - edit the geometry there, not here.
-->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="%(vp)sdp"
    android:height="%(vp)sdp"
    android:viewportWidth="%(vp)s"
    android:viewportHeight="%(vp)s">
%(paths)s
</vector>
'''


def scale_path(d, k):
    """Multiply every number in a path by k.

    Safe on this data because every path is absolute and uniformly scaled, so
    arc rx/ry scale with everything else. The large-arc and sweep flags are 0
    or 1 and a naive pass would scale them into nonsense, so each arc command
    is matched whole and its three flag fields copied through untouched."""
    if k == 1.0:
        return d
    num = re.compile(r'-?\d*\.?\d+')
    arc = re.compile(r'A\s*(-?[\d.]+)\s*,?\s*(-?[\d.]+)\s+(-?[\d.]+)\s+([01])\s*,?\s*([01])\s+')

    def one(s):
        return ('%.3f' % (float(s) * k)).rstrip('0').rstrip('.')

    def plain(s):
        return num.sub(lambda m: one(m.group(0)), s)

    out, i = [], 0
    for m in arc.finditer(d):
        out.append(plain(d[i:m.start()]))
        out.append('A%s,%s %s %s %s ' % (one(m.group(1)), one(m.group(2)),
                                         m.group(3), m.group(4), m.group(5)))
        i = m.end()
    out.append(plain(d[i:]))
    return ''.join(out)


def render(vp):
    k = vp / 108.0
    body = []
    for d, w, even in MARK:
        pd = scale_path(d, k)
        if w == 0:
            ft = '\n        android:fillType="evenOdd"' if even else ''
            body.append('    <path\n        android:pathData="%s"\n'
                        '        android:fillColor="#FFFFFF"%s />' % (pd, ft))
        else:
            body.append('    <path\n        android:pathData="%s"\n%s'
                        % (pd, STROKE % (w * k)))
    return HEADER % {'vp': vp, 'paths': '\n'.join(body)}


def svg(inset=1.0, transparent=False):
    m = (1 - inset) * 54
    s = ['<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108">']
    if not transparent:
        s.append('<rect width="108" height="108" fill="#000000"/>')
    s += [
         '<g transform="translate(%.3f,%.3f) scale(%s)">' % (m, m, inset)]
    for d, w, even in MARK:
        if w == 0:
            fr = ' fill-rule="evenodd"' if even else ''
            s.append('<path d="%s" fill="#FFFFFF"%s/>' % (d, fr))
        else:
            s.append('<path d="%s" fill="none" stroke="#FFFFFF" stroke-width="%s" '
                     'stroke-linecap="round" stroke-linejoin="round"/>' % (d, w))
    s.append('</g></svg>')
    return ''.join(s)


def write(rel, text):
    p = os.path.join(ROOT, rel)
    os.makedirs(os.path.dirname(p), exist_ok=True)
    open(p, 'w').write(text)
    print('wrote', rel)


def rasters():
    try:
        import io
        import cairosvg
        from PIL import Image, ImageDraw
    except ImportError:
        print('Pillow/cairosvg not installed - skipped the rasters. The adaptive '
              'icon is what ships on API 26 and up.')
        return
    for rel, px, round_, inset, transparent in RASTERS:
        raw = cairosvg.svg2png(bytestring=svg(inset, transparent).encode(),
                               output_width=px * 4, output_height=px * 4)
        im = Image.open(io.BytesIO(raw)).convert('RGBA')
        if round_:
            mask = Image.new('L', im.size, 0)
            ImageDraw.Draw(mask).ellipse([0, 0, im.size[0] - 1, im.size[1] - 1], fill=255)
            im.putalpha(mask)
        im = im.resize((px, px), Image.LANCZOS)
        p = os.path.join(ROOT, rel)
        os.makedirs(os.path.dirname(p), exist_ok=True)
        im.save(p, 'WEBP' if rel.endswith('.webp') else 'PNG')
        print('wrote', rel)


if __name__ == '__main__':
    for rel, vp in TARGETS:
        write(rel, render(vp))
    for rel, text in STATIC:
        write(rel, text)
    rasters()
