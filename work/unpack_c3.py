import struct, os, json, re

SRC = r"E:\Games\SolCesto\www\assets.dat"
OUT = r"D:\Claw\solcesto_port\unpacked"

data = open(SRC, 'rb').read()
assert data[:4] == b'c3ab'
assert data[16:20] == b'fdir'

ENTRY_HEADER = 37
pos = 0x20
entries = []
cum = 0
BAD = re.compile(r'[";{}\[\]!=<>\\\x00]')

while pos + ENTRY_HEADER <= len(data):
    flags, offset, size, size_dup, zero, nlen = struct.unpack('>QQQQIB', data[pos:pos+37])
    if nlen <= 0 or nlen > 1024 or pos + 37 + nlen > len(data):
        break
    name = data[pos+37:pos+37+nlen].decode('utf-8', 'replace')
    # strict sanity: cumulative offset, sane size, clean path name
    # allow root files without '/', but reject JS source fragments
    if offset != cum or size <= 0 or BAD.search(name) or ('/' not in name and '.' not in name):
        break
    entries.append((name, offset, size))
    cum += size
    pos += 37 + nlen

data_start = pos
print("entries:", len(entries), "dir_end:", hex(data_start))
print("last 5:", [e[0] for e in entries[-5:]])
print("cum end:", hex(data_start + cum), "file end:", hex(len(data)))
print("tail:", len(data) - (data_start + cum), "bytes")

tail = len(data) - (data_start + cum)
print("tail:", tail, "bytes")
assert tail < 4096, "data region mismatch: unexpected large tail"

os.makedirs(OUT, exist_ok=True)
manifest = []
for name, offset, size in entries:
    fname = name.lstrip('/')
    full = os.path.join(OUT, fname)
    os.makedirs(os.path.dirname(full), exist_ok=True)
    chunk = data[data_start + offset : data_start + offset + size]
    assert len(chunk) == size, name
    with open(full, 'wb') as f:
        f.write(chunk)
    manifest.append({"path": fname, "size": size})

with open(os.path.join(OUT, "_manifest.json"), 'w', encoding='utf-8') as f:
    json.dump({"count": len(entries), "dir_end": data_start, "files": manifest}, f, indent=1, ensure_ascii=False)

print("DONE. extracted:", len(entries), "files to", OUT)
