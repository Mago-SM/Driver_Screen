#!/usr/bin/env python3
"""Add mobile sync/live driver endpoints to reviewed upstream versions. Never applies SQL."""
import pathlib, shutil, sys
source = pathlib.Path(__file__).parent
if len(sys.argv) != 2: sys.exit('사용법: python3 apply-integration.py <bus-route-finder 경로>')
root = pathlib.Path(sys.argv[1]).resolve()
server = root / 'server.js'
text = server.read_text()
if 'liveDriverHandler' in text: sys.exit('이미 적용되었습니다.')
originals = [(source/'upstream'/name).read_text() for name in ['server.js','current-server.js']]
# Also recognize the exact previous route-selection integration generated here.
previous = []
for original in originals:
    s = "import { createRouteSelectionHandler, readRouteCatalog } from './route-selection-service.js';\n" + original
    s = s.replace('const root =', 'const routeSelectionHandler = createRouteSelectionHandler({ db, loadCatalog: readRouteCatalog });\nconst root =',1)
    s = s.replace("  if (url.pathname.startsWith('/api/auth/'))", "  if (url.pathname === '/api/driver/route-snapshot') return routeSelectionHandler(req, res);\n  if (url.pathname.startsWith('/api/auth/'))",1)
    previous.append(s)
if text in previous: base = originals[previous.index(text)]
elif text in originals: base = text
else: sys.exit('검토한 원본/이전 생성본과 다릅니다. backend/server.js와 수동 비교하세요. 변경하지 않았습니다.')
backup = root / 'server.js.before-mobile-driver'
if backup.exists(): sys.exit('백업이 이미 존재합니다. 수동 확인하세요.')
names = ['mobile-reservations.js','live-driver-service.js','jinju-provider.js','migrations/001-mobile-reservations.sql']
for name in names:
    target = root/name
    if target.exists() and target.read_bytes() != (source/name).read_bytes(): sys.exit(f'{name}에 기존 변경이 있습니다. 덮어쓰지 않습니다.')
imports = "import { createMobileHandler } from './mobile-reservations.js';\nimport { createLiveDriverHandler } from './live-driver-service.js';\nimport { createJinjuProvider } from './jinju-provider.js';\n"
updated = imports + base
updated = updated.replace('const root =', 'const mobileHandler = createMobileHandler({ db, requireUser });\nconst liveDriverHandler = createLiveDriverHandler({ db, provider: createJinjuProvider() });\nconst root =',1)
updated = updated.replace("  if (url.pathname.startsWith('/api/auth/'))", "  if (url.pathname === '/api/mobile/reservations') return mobileHandler(req, res);\n  if (['/api/driver/routes','/api/driver/vehicles','/api/driver/route-snapshot'].includes(url.pathname)) return liveDriverHandler(req, res);\n  if (url.pathname.startsWith('/api/auth/'))",1)
shutil.copy2(server,backup)
for name in names:
    (root/name).parent.mkdir(parents=True,exist_ok=True)
    shutil.copy2(source/name,root/name)
server.write_text(updated)
print('코드 적용 완료. SQL 마이그레이션과 .env 설정 후 재시작하세요. DB와 .env는 변경하지 않았습니다.')
