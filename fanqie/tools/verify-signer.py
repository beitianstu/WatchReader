"""Compare Java port with original executable's stored synthetic vectors."""
import argparse,json,os,pathlib,subprocess
root=pathlib.Path(__file__).resolve().parents[1]
parser=argparse.ArgumentParser();parser.add_argument('--fixture',type=pathlib.Path,required=True);args=parser.parse_args()
fixture=args.fixture
jdk=pathlib.Path(os.environ['JAVA_HOME'])/'bin'
dest=root/'test-results'/'jvm';dest.mkdir(parents=True,exist_ok=True)
source=dest/'SignerVectors.java'
source.write_text('''import com.watchreader.fanqie.DesktopSigner;import java.io.*;import java.nio.charset.StandardCharsets;
public class SignerVectors {public static void main(String[] args)throws Exception {
BufferedReader r=new BufferedReader(new InputStreamReader(System.in,StandardCharsets.UTF_8));String line;
while((line=r.readLine())!=null){String[] a=line.split("\\t",-1);String q=new String(DesktopSigner.hex(a[0]),StandardCharsets.UTF_8);byte[] b=DesktopSigner.hex(a[1]);long ts=Long.parseLong(a[2]),rand=Long.parseLong(a[4]);boolean raw=a[3].equals("1");String d=new String(DesktopSigner.hex(a[5]),StandardCharsets.UTF_8),v=new String(DesktopSigner.hex(a[6]),StandardCharsets.UTF_8);
System.out.println(DesktopSigner.argus(q,b,ts,raw,rand,d,v)+"\\t"+DesktopSigner.ladon(ts,DesktopSigner.hex(a[7]))+"\\t"+DesktopSigner.hex(DesktopSigner.payload(q,b,ts,raw,rand,d,v)));}}}''',encoding='utf8')
subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','--release','11','-d',str(dest),str(root/'android/java/com/watchreader/fanqie/DesktopSigner.java'),str(source)],check=True)
vectors=json.loads(fixture.read_text('utf8'))['vectors']
selected=[v for v in vectors if v.get('payload_aid',3019)==3019 and bytes.fromhex(v['query_hex']).decode('utf8','replace').encode().hex()==v['query_hex']]
lines=['\t'.join([v['query_hex'],v['body_hex'],str(v['timestamp']),'1' if v['raw_md5'] else '0',str(v['random_word']),v['device_id'].encode().hex(),v['version_name'].encode().hex(),v['first_nonce_hex']]) for v in selected]
r=subprocess.run([str(jdk/'java.exe'),'-cp',str(dest),'SignerVectors'],input='\n'.join(lines)+'\n',capture_output=True,text=True,encoding='utf8',check=True)
fail=[]
for v,line in zip(selected,r.stdout.splitlines()):
 a,l,p=line.split('\t')
 if a!=v['native_headers']['x-argus'] or l!=v['native_headers']['x-ladon'] or p not in v['native_protobuf_hex']:fail.append(v['name'])
report={'vectors':len(selected),'outputs':len(r.stdout.splitlines()),'failed':fail,'excluded':len(vectors)-len(selected),'origin':'original executable synthetic vectors; UTF-8 query and payload_aid=3019 profile'}
(root/'test-results'/'java-signer.json').write_text(json.dumps(report,indent=2),encoding='utf8')
print(json.dumps(report));assert not fail and report['outputs']==len(selected)
