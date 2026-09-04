"""Writes real .litematic files, so the Java reader can be run against them."""
import gzip, struct, sys, os

def b(t): return struct.pack('>b', t)
def i(v): return struct.pack('>i', v)
def s(v):
    raw = v.encode('utf-8')
    return struct.pack('>H', len(raw)) + raw

def tag(t, name, payload): return b(t) + s(name) + payload

def comp(entries):
    out = b''.join(entries)
    return out + b(0)

def cint(name, v): return tag(3, name, i(v))
def cstr(name, v): return tag(8, name, s(v))
def ccomp(name, entries): return tag(10, name, comp(entries))
def clonga(name, vals):
    return tag(12, name, i(len(vals)) + b''.join(struct.pack('>q', v) for v in vals))
def clist(name, kind, items, count=None):
    return tag(9, name, b(kind) + i(len(items) if count is None else count)
               + b''.join(items))

def xyz(name, x, y, z): return ccomp(name, [cint('x', x), cint('y', y), cint('z', z)])

def bits_for(n):
    bits = 2
    while (1 << bits) < n: bits += 1
    return bits

def pack(indices, bits):
    """Spanning packing: entry i occupies bits [i*bits, (i+1)*bits) of one long stream."""
    big = 0
    for n, v in enumerate(indices):
        big |= (v & ((1 << bits) - 1)) << (n * bits)
    count = (len(indices) * bits + 63) // 64
    out = []
    for j in range(count):
        word = (big >> (64 * j)) & 0xFFFFFFFFFFFFFFFF
        out.append(word - (1 << 64) if word >= (1 << 63) else word)
    return out

def palette_tag(entries):
    items = []
    for name, props in entries:
        parts = [cstr('Name', name)]
        if props:
            parts.append(ccomp('Properties', [cstr(k, v) for k, v in props.items()]))
        items.append(comp(parts))
    return clist('BlockStatePalette', 10, items)

def region(name, pos, size, palette, indices, bits=None, override_longs=None):
    bits = bits or bits_for(len(palette))
    longs = override_longs if override_longs is not None else pack(indices, bits)
    return ccomp(name, [
        xyz('Position', *pos), xyz('Size', *size),
        palette_tag(palette),
        clonga('BlockStates', longs),
        clist('Entities', 0, []), clist('TileEntities', 0, []),
    ])

def write(path, regions, name='test', author='me'):
    root = comp([
        cint('Version', 6), cint('SubVersion', 1), cint('MinecraftDataVersion', 3953),
        ccomp('Metadata', [cstr('Name', name), cstr('Author', author),
                           xyz('EnclosingSize', 1, 1, 1), cint('RegionCount', len(regions))]),
        ccomp('Regions', regions),
    ])
    data = b(10) + s('') + root
    with gzip.open(path, 'wb') as f: f.write(data)
    return path
