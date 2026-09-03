#!/usr/bin/env python3
"""Reads a headless-browser screenshot and asserts that the guest's tree is actually on it.

The transcript proves the bindings composed and the layout pass measured real glyph boxes. It
still says nothing about pixels, and "Compose laid something out" and "the user sees it" are
different claims. This closes that gap: it decodes the PNG itself and looks for the exact colour
the guest asked for.

The decoder is written out rather than imported because this repository's Python has no imaging
library, and a verification step that silently skips when a dependency is missing is worse than no
verification step -- it reports success for a run that checked nothing.
"""
import struct
import sys
import zlib

path, want_hex, min_pixels = sys.argv[1], sys.argv[2], int(sys.argv[3])


def read_png(path):
    """Decodes a non-interlaced 8-bit truecolour PNG into (width, height, rows of RGB tuples)."""
    data = open(path, 'rb').read()
    if data[:8] != b'\x89PNG\r\n\x1a\n':
        raise SystemExit(f'{path} is not a PNG')
    pos = 8
    header = None
    idat = b''
    while pos < len(data):
        length, kind = struct.unpack('>I4s', data[pos:pos + 8])
        body = data[pos + 8:pos + 8 + length]
        pos += 12 + length
        if kind == b'IHDR':
            header = struct.unpack('>IIBBBBB', body)
        elif kind == b'IDAT':
            idat += body
        elif kind == b'IEND':
            break
    width, height, depth, colour, compression, filt, interlace = header
    if depth != 8 or interlace != 0 or colour not in (2, 6):
        raise SystemExit(f'unsupported PNG: depth={depth} colour={colour} interlace={interlace}')
    channels = 3 if colour == 2 else 4
    raw = zlib.decompress(idat)
    stride = width * channels
    out = []
    previous = bytearray(stride)
    at = 0
    for _ in range(height):
        method = raw[at]
        line = bytearray(raw[at + 1:at + 1 + stride])
        at += 1 + stride
        # The five PNG filter types, applied in reverse. `a` is the pixel to the left, `b` the one
        # above, `c` the one above-left.
        for i in range(stride):
            a = line[i - channels] if i >= channels else 0
            b = previous[i]
            c = previous[i - channels] if i >= channels else 0
            if method == 0:
                pass
            elif method == 1:
                line[i] = (line[i] + a) & 0xFF
            elif method == 2:
                line[i] = (line[i] + b) & 0xFF
            elif method == 3:
                line[i] = (line[i] + ((a + b) >> 1)) & 0xFF
            elif method == 4:
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                pr = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                line[i] = (line[i] + pr) & 0xFF
            else:
                raise SystemExit(f'unknown PNG filter {method}')
        previous = line
        out.append(bytes(line))
    return width, height, channels, out


width, height, channels, rows = read_png(path)
want = tuple(int(want_hex[i:i + 2], 16) for i in (0, 2, 4))

matched = 0
non_white = 0
for row in rows:
    for x in range(width):
        pixel = row[x * channels:x * channels + 3]
        if pixel == bytes(want):
            matched += 1
        if pixel != b'\xff\xff\xff':
            non_white += 1

print(f'  screenshot: {width}x{height}, {non_white} non-white pixels, '
      f'{matched} exactly #{want_hex}')

failures = []
if non_white < min_pixels:
    failures.append(f'only {non_white} non-white pixels; the page is effectively blank')
if matched < 1000:
    failures.append(f'only {matched} pixels of the swatch colour #{want_hex} the guest asked for')
for failure in failures:
    print(f'  FAIL: {failure}')
sys.exit(1 if failures else 0)
