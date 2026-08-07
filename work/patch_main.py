import re

p = r'D:\Claw\solcesto_port\unpacked\scripts\main.js'
s = open(p, encoding='utf-8').read()

# 1. force non-worker mode: runtime runs on main thread,
#    video element plays natively instead of ImageBitmap frame copying
old1 = 'this._useWorker=e.useWorker'
assert s.count(old1) == 1, s.count(old1)
s = s.replace(old1, 'this._useWorker=false')

# 2. disable video frame copy path entirely (postImageBitmaps=false)
old2 = 'this._postImageBitmaps=this._isRuntimeInWorker||a'
assert s.count(old2) == 1, s.count(old2)
s = s.replace(old2, 'this._postImageBitmaps=false')

open(p, 'w', encoding='utf-8').write(s)
print('main.js patched: useWorker=false, postImageBitmaps=false')
