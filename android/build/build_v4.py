"""Build v4 APK: replace dex + assets in base.apk, then zipalign + sign."""
import zipfile, os, subprocess, sys

ROOT = r'D:\Claw\solcesto_port\android'
BUILD = os.path.join(ROOT, 'build')
OLD = os.path.join(BUILD, 'base.apk')
TMP = os.path.join(BUILD, 'base_v16_tmp.apk')
ALIGNED = os.path.join(BUILD, 'aligned_v16.apk')
FINAL = r'D:\Claw\solcesto_port\SolCesto-Mobile-v16.apk'
DEX = os.path.join(BUILD, 'dex', 'classes.dex')
WWW = os.path.join(ROOT, 'app', 'assets', 'www')
KEYSTORE = os.path.join(BUILD, 'solcesto.keystore')
STORED_EXT = ('.png', '.jpg', '.jpeg', '.webm', '.mp4', '.woff', '.woff2', '.ogg', '.mp3', '.dat', '.zip')

def pick(fn):
    return zipfile.ZIP_STORED if fn.lower().endswith(STORED_EXT) else zipfile.ZIP_DEFLATED

# Step 3: Package - rebuild base.apk with new dex + assets
print("[1/3] Packaging APK...")
keep = []
with zipfile.ZipFile(OLD) as zin:
    for info in zin.infolist():
        # drop old classes.dex and wrong www/www paths
        if info.filename.startswith('assets/www/') or info.filename == 'classes.dex':
            continue
        keep.append(info)

with zipfile.ZipFile(TMP, 'w') as zout:
    with zipfile.ZipFile(OLD) as zin:
        for info in keep:
            zout.writestr(info, zin.read(info.filename))
    # add classes.dex
    zout.write(DEX, 'classes.dex')
    # add assets/www
    count = 0
    for root, dirs, files in os.walk(WWW):
        for f in files:
            full = os.path.join(root, f)
            rel = os.path.relpath(full, WWW).replace('\\', '/')
            arc = 'assets/www/' + rel
            zout.write(full, arc, compress_type=pick(full))
            count += 1
    print(f'  assets packed: {count} files')

os.replace(TMP, OLD)
print(f'  base.apk size: {os.path.getsize(OLD):,} bytes')

# Step 4: zipalign
print("[2/3] Zipaligning...")
subprocess.run([r'D:\android-sdk\build-tools\35.0.1\zipalign.exe', '-v', '-p', '4', OLD, ALIGNED], check=True)
print(f'  aligned size: {os.path.getsize(ALIGNED):,} bytes')

# Step 5: apksigner
print("[3/3] Signing...")
subprocess.run([
    r'D:\Java\jdk17\bin\java.exe', '-jar',
    r'D:\android-sdk\build-tools\35.0.1\lib\apksigner.jar', 'sign',
    '--ks', KEYSTORE,
    '--ks-pass', 'pass:solcesto123',
    '--ks-key-alias', 'solcesto',
    '--key-pass', 'pass:solcesto123',
    '--out', FINAL,
    ALIGNED
], check=True)

# Verify
print(f'\nDone! Final APK: {FINAL}')
print(f'  size: {os.path.getsize(FINAL):,} bytes')
with zipfile.ZipFile(FINAL) as z:
    names = z.namelist()
    print(f'  entries: {len(names)}')
    print(f'  has classes.dex: {"classes.dex" in names}')
    print(f'  has index.html: {"assets/www/index.html" in names}')
    print(f'  has data.json: {"assets/www/data.json" in names}')
    print(f'  has c3runtime: {any("c3runtime.js" in n for n in names)}')
    dex_size = z.getinfo('classes.dex').file_size
    print(f'  dex size: {dex_size} bytes')
