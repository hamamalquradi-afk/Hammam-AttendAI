#!/usr/bin/env python3
from pathlib import Path
import re, sys, xml.etree.ElementTree as ET
ROOT=Path(__file__).resolve().parents[1]
SRC=ROOT/'android/app/src/main/java'
RES=ROOT/'android/app/src/main/res'
errors=[]; notes=[]
def ok(cond,msg):
    if not cond: errors.append(msg)

def read(p): return p.read_text(errors='ignore')
# Build/package/version
build=read(ROOT/'android/app/build.gradle.kts')
ok('applicationId = "com.hammam.attendai"' in build,'applicationId mismatch')
ok('minSdk = 24' in build,'minSdk != 24')
ok('compileSdk = 35' in build,'compileSdk != 35')
ok('targetSdk = 35' in build,'targetSdk != 35')
ok('buildConfigField("int", "DATABASE_VERSION", "4")' in build,'BuildConfig DB version != 4')
# Resources XML/parity/references
str_files=[RES/'values/strings.xml',RES/'values-en/strings.xml']
sets=[]
for p in str_files:
    try: tree=ET.parse(p)
    except Exception as e: errors.append(f'XML invalid {p}: {e}'); continue
    sets.append({x.attrib['name'] for x in tree.getroot() if x.tag=='string'})
if len(sets)==2: ok(sets[0]==sets[1],f'string parity mismatch default-only={sets[0]-sets[1]} en-only={sets[1]-sets[0]}')
refs=set()
kt_files=list(SRC.rglob('*.kt'))
for p in kt_files: refs.update(re.findall(r'R\.string\.([A-Za-z0-9_]+)',read(p)))
if sets: ok(not(refs-sets[0]),f'missing strings {sorted(refs-sets[0])}')
# Package path consistency
for p in kt_files:
    txt=read(p); m=re.search(r'^package\s+([\w.]+)',txt,re.M)
    if not m: errors.append(f'missing package: {p}'); continue
    pkg=m.group(1); expected=Path(*pkg.split('.'))
    rel=p.relative_to(SRC)
    ok(rel.parent.as_posix().endswith(expected.as_posix()),f'package/path mismatch {p}: {pkg}')
# duplicate top-level declarations
seen={}
for p in kt_files:
    for m in re.finditer(r'^(?:data\s+|sealed\s+|enum\s+|abstract\s+)?(?:class|object|interface)\s+([A-Za-z_][A-Za-z0-9_]*)',read(p),re.M):
        seen.setdefault(m.group(1),[]).append(str(p.relative_to(ROOT)))
for name,ps in seen.items(): ok(len(ps)==1,f'duplicate top-level {name}: {ps}')
# entities/table uniqueness and DB registration
entities=[]
for p in (SRC/'com/hammam/attendai/data/local/entity').glob('*.kt'):
    txt=read(p)
    entities += re.findall(r'@Entity\(tableName="([^"]+)"[^)]*\)\s+(?:data\s+)?class\s+([A-Za-z0-9_]+)',txt,re.S)
tables=[x[0] for x in entities]; ok(len(tables)==len(set(tables)),f'duplicate entity tables: {[x for x in set(tables) if tables.count(x)>1]}')
db=read(SRC/'com/hammam/attendai/data/local/HammamDatabase.kt')
ok('version = 4' in db,'Room database version != 4')
registered=set(re.findall(r'([A-Za-z0-9_]+)::class',db.split('version = 4')[0]))
for table,cls in entities: ok(cls in registered,f'entity not registered in @Database: {cls}/{table}')
ok('MIGRATION_1_2' in db and 'MIGRATION_2_3' in db and 'MIGRATION_3_4' in db,'migration declaration missing')
ok('arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)' in db,'migration registration path incomplete')
ok('fallbackToDestructiveMigration' not in ''.join(read(p) for p in kt_files),'destructive migration found')
# Migration 3->4 duplicate ADD columns/tables against entity names
m34=db.split('MIGRATION_3_4',1)[1]
for col in ['weeklyScheduleId','actorRole']: ok(m34.count(f'ADD COLUMN {col}')==1,f'Migration3->4 duplicate/missing column {col}')
for table in ['user_scopes','user_permission_grants','device_replacement_requests','weekly_timetable_versions']: ok(m34.count(f'CREATE TABLE IF NOT EXISTS {table}')==1,f'Migration3->4 duplicate/missing table {table}')
# DAO method availability for dao.foo calls
core=read(SRC/'com/hammam/attendai/data/local/dao/CoreDao.kt')
declared=set(re.findall(r'\bfun\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(',core))
used=set()
for p in kt_files:
    if p.name=='CoreDao.kt': continue
    used.update(re.findall(r'\bdao\.([A-Za-z_][A-Za-z0-9_]*)\s*\(',read(p)))
# only flag names absent globally; false positives are unlikely because project uses CoreDao as dao
for name in sorted(used-declared): errors.append(f'dao call missing in CoreDao: {name}')
# Routes enum duplicate values
main=read(SRC/'com/hammam/attendai/MainActivity.kt')
for enum in ['Tab','DetailRoute']:
    m=re.search(rf'enum class {enum}\{{([^}}]+)\}}',main)
    ok(bool(m),f'{enum} enum missing')
    if m:
        vals=[x.strip() for x in m.group(1).split(',') if x.strip()]; ok(len(vals)==len(set(vals)),f'duplicate {enum} routes')
# Manifest + referenced components/resources
manifest=ET.parse(RES.parent/'AndroidManifest.xml').getroot(); ns='{http://schemas.android.com/apk/res/android}'
app=manifest.find('application'); ok(app is not None,'application missing')
if app is not None:
    for tag in ['activity','service','provider']:
        for node in app.findall(tag):
            name=node.attrib.get(ns+'name',''); exported=node.attrib.get(ns+'exported')
            ok(exported is not None,f'{tag} missing android:exported: {name}')
            if name.startswith('.'):
                cls=name[1:]; candidates=list(SRC.rglob(cls.split('.')[-1]+'.kt')); ok(bool(candidates),f'manifest class missing: {name}')
    provider=app.find('provider')
    ok(provider is not None,'FileProvider missing')
ok((RES/'xml/file_paths.xml').exists(),'file_paths.xml missing')
ok((RES/'xml/data_extraction_rules.xml').exists(),'data_extraction_rules.xml missing')
# WorkManager worker refs
alltxt='\n'.join(read(p) for p in kt_files)
worker_defs=set(re.findall(r'^class\s+([A-Za-z0-9_]+Worker)\b',alltxt,re.M))
worker_refs=set(re.findall(r'(?:PeriodicWorkRequestBuilder|OneTimeWorkRequestBuilder)<([A-Za-z0-9_]+Worker)>',alltxt))
for w in worker_refs: ok(w in worker_defs,f'WorkManager class ref missing: {w}')
# Feature flags
for flag in ['BLE_ATTENDANCE','QR_ATTENDANCE','NFC_ATTENDANCE','AI_ASSISTANT','WHATSAPP','EMAIL','AUTO_REPORTS','CLOUD_SYNC']:
    ok(flag in alltxt,f'feature flag missing: {flag}')
# Critical wiring callers
critical={
'StudentBleAdvertiser':['StudentPresenceService.kt'],
'DeviceEnrollmentRepository':['AppContainer.kt'],
'StudentCsv':['AdminOperationsViewModel.kt'],
'PinHasher':['AppLockManager.kt'],
'TeacherReportSettingEntity':['AdminOperationsViewModel.kt','ReportRepository.kt'],
'DataIntegrityRepository':['AppContainer.kt','AdminOperationsViewModel.kt'],
'DatabaseMigrationGuard':['AppContainer.kt'],
}
for symbol,expected in critical.items():
    locations=[p.name for p in kt_files if symbol in read(p)]
    for e in expected: ok(e in locations,f'critical wiring missing {symbol} caller {e}; found={locations}')
# DI-exposed implementations are intentionally referenced by property, not class name, from callers.
property_wiring={
'backupManager':['AdminOperationsViewModel.kt'],
'aiProviderManager':['AdminOperationsViewModel.kt','MainViewModel.kt'],
'appLock':['MainViewModel.kt'],
'devices':['StudentModeViewModel.kt'],
}
for prop,expected in property_wiring.items():
    locations=[p.name for p in kt_files if f'container.{prop}' in read(p)]
    for e in expected: ok(e in locations,f'critical property wiring missing container.{prop} caller {e}; found={locations}')
# Common Compose API misuse caught in prior code
screens=read(SRC/'com/hammam/attendai/ui/Screens.kt')
ok(not re.search(r'FilterChip\([^,]+,\s*\{[^}]*=it\}',screens),'invalid FilterChip onClick(Boolean) pattern')
# Local DI architecture note
if 'com.google.dagger:hilt' not in build and '@HiltAndroidApp' not in alltxt: notes.append('DI=manual AppContainer; Hilt binding checks N/A for current architecture')
print('STATIC_REGRESSION='+('PASS' if not errors else 'FAIL'))
for n in notes: print('NOTE',n)
for e in errors: print('ERROR',e)
sys.exit(1 if errors else 0)
