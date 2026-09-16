"""Create small lossless phone test fixtures from the captured website permutation."""
from pathlib import Path
import struct
import zlib

root = Path(__file__).resolve().parent.parent
row = next(line for line in (root / 'core/src/test/resources/website-vectors.tsv').read_text().splitlines()
           if line.startswith('127\t83\t'))
w, h, forward, _ = row.split('\t')
w, h = int(w), int(h)
mapping = list(map(int, forward.split(',')))
pixels = []
for y in range(h):
    for x in range(w):
        if (x - 96) ** 2 + (y - 21) ** 2 < 121:
            rgb = (237, 189, 126)
        elif y > 68 - x // 5:
            rgb = (100, 135, 108)
        elif y > 38 + abs(x - 43) // 2:
            rgb = (147, 169, 148)
        else:
            rgb = (205 + y // 4, 225 + y // 5, 228)
        pixels.append(bytes((*rgb, 255)))


def png(path, values):
    def chunk(kind, data):
        return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data))
    rows = b''.join(b'\0' + b''.join(values[y*w:(y+1)*w]) for y in range(h))
    path.write_bytes(b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', w, h, 8, 6, 0, 0, 0))
                     + chunk(b'IDAT', zlib.compress(rows)) + chunk(b'IEND', b''))


folder = root / 'samples'
folder.mkdir(exist_ok=True)
png(folder / 'original.png', pixels)
mixed = [pixels[i] for i in mapping]
png(folder / 'mixed-once.png', mixed)
png(folder / 'mixed-twice.png', [mixed[i] for i in mapping])
print('Created original / mixed-once / mixed-twice PNG test fixtures.')
