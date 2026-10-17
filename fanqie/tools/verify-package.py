"""Check the delivered APK without printing any account material."""
import argparse,hashlib,json,os,pathlib,subprocess,zipfile
p=argparse.ArgumentParser();p.add_argument('apk',type=pathlib.Path);p.add_argument('--debug',action='store_true');p.add_argument('--host',action='store_true');p.add_argument('--cookie-file',type=pathlib.Path);args=p.parse_args()
bt=pathlib.Path(os.environ.get('ANDROID_HOME') or os.environ['ANDROID_SDK_ROOT'])/'build-tools'/'34.0.0'
r=subprocess.run([str(bt/'aapt2.exe'),'dump','xmltree',str(args.apk),'--file','AndroidManifest.xml'],capture_output=True,check=True)
manifest=r.stdout.decode('utf8','replace')
expected='com.watchreader' if args.host else 'com.watchreader.fanqie'
checks={'package_id':('package="'+expected+'"') in manifest,'backup_disabled':False,'cleartext_disabled':False,'debuggable_expected':False}
for line in manifest.splitlines():
 if 'android:allowBackup(' in line:checks['backup_disabled']='=false' in line or '=0x0' in line
 if 'android:usesCleartextTraffic(' in line:checks['cleartext_disabled']='=false' in line or '=0x0' in line
 if 'android:debuggable(' in line:checks['debuggable_expected']=('=true' in line or '=0xffffffff' in line)==args.debug
with zipfile.ZipFile(args.apk) as z:
 files=z.namelist();data=b'\n'.join(z.read(n) for n in files)
 checks['no_test_runner']=not any(name in data for name in (b'PluginInstrumentation',b'ProgressUiInstrumentation',b'LibraryUiInstrumentation',b'CrownScrollInstrumentation',b'ParallelDownloadInstrumentation',b'NativeParallelInstrumentation'))
 checks['no_cookie_file']=not any('cookie' in n.lower() for n in files)
 checks['no_pc_proxy']=b'10.0.2.2' not in data and b'127.0.0.1' not in data
 # Values are only compared in memory; never print them or their lengths.
 values=[]
 if args.cookie_file:
  values=[x.get('value','').encode('utf8') for x in json.loads(args.cookie_file.read_text('utf8')).get('cookies',[]) if len(x.get('value',''))>=12]
 checks['no_embedded_account_values']=not any(value in data for value in values) if args.cookie_file else None
 checks['architecture_independent']=not any(n.startswith('lib/') for n in files)
 if args.host:
  checks['no_downloader_backend']=not any(name in data for name in (b'Lcom/watchreader/fanqie/DesktopSigner;',b'Lcom/watchreader/fanqie/FanqiePlugin;',b'Lcom/watchreader/fanqie/CredentialStore;'))
 else:
  checks['no_independent_reader']=not any(name in data for name in (b'Lcom/watchreader/ReaderView;',b'Lcom/watchreader/ReaderActivity;',b'Lcom/watchreader/Paginator;'))
  checks['no_launcher']='android.intent.category.LAUNCHER' not in manifest
checks['signature_valid']=subprocess.run([str(bt/'apksigner.bat'),'verify',str(args.apk)],capture_output=True).returncode==0
report={'file':args.apk.name,'bytes':args.apk.stat().st_size,'sha256':hashlib.sha256(args.apk.read_bytes()).hexdigest(),'checks':checks}
kind='host' if args.host else 'plugin'
out=pathlib.Path(__file__).resolve().parents[1]/'test-results'/('package-'+kind+('-debug.json' if args.debug else '-release.json'))
out.parent.mkdir(parents=True,exist_ok=True)
out.write_text(json.dumps(report,indent=2),encoding='utf8');print(json.dumps(report));assert all(v for v in checks.values() if v is not None)
