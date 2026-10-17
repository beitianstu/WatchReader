package com.watchreader.fanqie;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.net.URLDecoder;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Desktop-compatible wire signature. No native library or remote signer. */
public final class DesktopSigner {
    private static final byte[] KEY=hex("ac1adaae95a7af94a5114ab3b3a97dd80050aa0a39314c40528caec95256c28c");
    private static final byte[] SALT=hex("f281616f"), PREFIX=hex("f2f7fcfff2f7fcff");
    private static final SecureRandom RNG=new SecureRandom();
    public static byte[] utf(String s){return s.getBytes(StandardCharsets.UTF_8);}
    public static byte[] hex(String s){byte[] r=new byte[s.length()/2];for(int i=0;i<r.length;i++)r[i]=(byte)Integer.parseInt(s.substring(i*2,i*2+2),16);return r;}
    public static String hex(byte[] v){StringBuilder b=new StringBuilder();for(byte x:v)b.append(String.format(Locale.ROOT,"%02x",x&255));return b.toString();}
    public static byte[] join(byte[]... arrays){ByteArrayOutputStream b=new ByteArrayOutputStream();for(byte[] a:arrays)b.write(a,0,a.length);return b.toByteArray();}
    public static byte[] md5(byte[] x)throws Exception{return MessageDigest.getInstance("MD5").digest(x);}
    public static byte[] aes(byte[] value,byte[] key,byte[] iv,boolean encrypt)throws Exception {
        Cipher c=Cipher.getInstance("AES/CBC/PKCS5Padding");c.init(encrypt?Cipher.ENCRYPT_MODE:Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new IvParameterSpec(iv));return c.doFinal(value);
    }
    public static byte[] unblob(String b64,byte[] key)throws Exception {
        byte[] b=Base64.getDecoder().decode(b64);if(b.length<32||b.length%16!=0)throw new IllegalArgumentException("无效的加密内容");
        return aes(Arrays.copyOfRange(b,16,b.length),key,Arrays.copyOf(b,16),false);
    }
    private static byte[] pad(byte[] x){int n=16-x.length%16;byte[] r=Arrays.copyOf(x,x.length+n);Arrays.fill(r,x.length,r.length,(byte)n);return r;}
    private static long[] words(byte[] x){long[] w=new long[x.length/8];ByteBuffer b=ByteBuffer.wrap(x).order(ByteOrder.LITTLE_ENDIAN);for(int i=0;i<w.length;i++)w[i]=b.getLong();return w;}
    private static byte[] wordBytes(long... v){ByteBuffer b=ByteBuffer.allocate(v.length*8).order(ByteOrder.LITTLE_ENDIAN);for(long x:v)b.putLong(x);return b.array();}
    private static byte[] var(long v){v&=0xffffffffL;ByteArrayOutputStream b=new ByteArrayOutputStream();while(v>=128){b.write((int)(v&127)|128);v>>>=7;}b.write((int)v);return b.toByteArray();}
    private static final class Proto {
        ByteArrayOutputStream b=new ByteArrayOutputStream();void write(byte[] x){b.write(x,0,x.length);}
        Proto n(int field,long v){write(var(field<<3));write(var(v));return this;}
        Proto s(int field,String v){return bytes(field,utf(v));}
        Proto bytes(int field,byte[] v){write(var((field<<3)|2));write(var(v.length));write(v);return this;}
        byte[] get(){return b.toByteArray();}
    }
    private static boolean white(char c){return "\t\n\u000b\f\r \u0085\u00a0\u1680\u2000\u2001\u2002\u2003\u2004\u2005\u2006\u2007\u2008\u2009\u200a\u2028\u2029\u202f\u205f\u3000".indexOf(c)>=0;}
    private static boolean blank(String x){for(int i=0;i<x.length();i++)if(!white(x.charAt(i)))return false;return true;}
    private static String trim(String s,String fallback){int a=0,b=s.length();while(a<b&&white(s.charAt(a)))a++;while(b>a&&white(s.charAt(b-1)))b--;return a==b?fallback:s.substring(a,b);}
    private static String form(String s){byte[] input=utf(s);ByteArrayOutputStream b=new ByteArrayOutputStream();for(int i=0;i<input.length;i++){int c=input[i]&255;if(c=='+')c=' ';else if(c=='%'&&i+2<input.length){int a=Character.digit((char)input[i+1],16),d=Character.digit((char)input[i+2],16);if(a>=0&&d>=0){c=a*16+d;i+=2;}}b.write(c);}return new String(b.toByteArray(),StandardCharsets.UTF_8);}
    public static byte[] payload(String query,byte[] body,long ts,boolean raw,long random)throws Exception {return payload(query,body,ts,raw,random,"0","6.8.1.32");}
    public static byte[] payload(String query,byte[] body,long ts,boolean raw,long random,String device,String version)throws Exception {
        device=trim(device,"0");version=trim(version,"6.8.1.32");
        for(String p:query.split("&")){String[] a=p.split("=",2);String k=form(a[0]),v=a.length==2?form(a[1]):"";
            if(!blank(v)){if(k.equals("device_id"))device=v;else if(k.equals("version_name"))version=v;}}
        byte[] bh=body.length==0?new byte[16]:(raw?md5(body):utf(hex(md5(body))));
        return new Proto().n(1,0x40401252).n(2,2).n(3,raw?random%0x7fffffffL:random&0x7fffffffL)
            .s(4,"1967").s(5,device).s(6,"1611921764").s(7,version).s(8,"v04.04.05-ov-android")
            .n(9,0x08080a40).bytes(10,new byte[8]).n(11,0).n(12,ts<<1).bytes(13,Arrays.copyOf(sm3(bh),6))
            .bytes(14,Arrays.copyOf(sm3(query.isEmpty()?new byte[16]:utf(query)),6))
            .bytes(15,new Proto().n(1,1).n(2,1).n(3,1).n(7,0xc792ecccL).get()).bytes(16,new byte[0])
            .s(20,"none").n(21,738).bytes(23,new Proto().s(1,"NX551J").n(2,8196).n(4,0x80e0d800L).get()).n(25,2).get();
    }
    public static String argus(String query,byte[] body,long ts,boolean raw,long random)throws Exception {
        return argus(query,body,ts,raw,random,"0","6.8.1.32");
    }
    public static String argus(String query,byte[] body,long ts,boolean raw,long random,String device,String version)throws Exception {
        long[] first=words(sm3(join(KEY,SALT,KEY))),keys=Arrays.copyOf(first,72);
        for(int i=4;i<72;i++){long t=Long.rotateRight(keys[i-1],3)^keys[i-3];keys[i]=0xfffffffffffffffcL^((0x3dc94c3a046d678bL>>>((i-4)%62))&1)^keys[i-4]^t^Long.rotateRight(t,1);}
        byte[] value=pad(payload(query,body,ts,raw,random,device,version));
        for(int p=0;p<value.length;p+=16){long[] w=words(Arrays.copyOfRange(value,p,p+16));long low=w[0],high=w[1];
            for(long k:keys){long n=low^(Long.rotateLeft(high,1)&Long.rotateLeft(high,8))^Long.rotateLeft(high,2)^k;low=high;high=n;}
            System.arraycopy(wordBytes(low,high),0,value,p,16);}
        for(int i=0;i<value.length;i++)value[i]^=PREFIX[i%8];value=join(PREFIX,value);
        for(int i=0,j=value.length-1;i<j;i++,j--){byte t=value[i];value[i]=value[j];value[j]=t;}
        byte[] encrypted=aes(join(hex("a66ead9f7701d00c18"),value,utf("ao")),md5(Arrays.copyOf(KEY,16)),md5(Arrays.copyOfRange(KEY,16,32)),true);
        return Base64.getEncoder().encodeToString(join(Arrays.copyOf(SALT,2),encrypted));
    }
    public static String ladon(long ts,byte[] nonce)throws Exception {
        long[] w=words(utf(hex(md5(join(nonce,utf("1967")))))),keys=new long[34];keys[0]=w[0];
        ArrayDeque<Long> q=new ArrayDeque<>();q.add(w[1]);q.add(w[2]);q.add(w[3]);
        for(int i=0;i<33;i++){long n=(Long.rotateRight(q.remove(),8)+keys[i])^i;q.add(n);keys[i+1]=Long.rotateLeft(keys[i],3)^n;}
        byte[] b=pad(utf(ts+"-1611921764-3019"));
        for(int i=0;i<b.length;i+=16){long[] x=words(Arrays.copyOfRange(b,i,i+16));long l=x[0],r=x[1];for(long k:keys){r=(Long.rotateRight(r,8)+l)^k;l=Long.rotateLeft(l,3)^r;}System.arraycopy(wordBytes(l,r),0,b,i,16);}
        return Base64.getEncoder().encodeToString(join(nonce,b));
    }
    public static Map<String,String> sign(String query,byte[] body)throws Exception {
        if(body==null)body=new byte[0];long ts=System.currentTimeMillis()/1000;byte[] nonce=new byte[4];RNG.nextBytes(nonce);
        Map<String,String> h=new LinkedHashMap<>();h.put("x-khronos",Long.toString(ts));h.put("x-argus",argus(query,body,ts,body.length>0,RNG.nextInt()&0xffffffffL));h.put("x-ladon",ladon(ts,nonce));
        if(body.length>0)h.put("x-ss-stub",hex(md5(body)).toUpperCase(Locale.ROOT));return h;
    }
    /** GB/T 32905-2016; arithmetic deliberately wraps at 32 bits. */
    public static byte[] sm3(byte[] input){
        int n=((input.length+9+63)/64)*64;byte[] b=Arrays.copyOf(input,n);b[input.length]=(byte)128;
        long bits=input.length*8L;for(int i=0;i<8;i++)b[n-1-i]=(byte)(bits>>>(8*i));
        int[] v={0x7380166f,0x4914b2b9,0x172442d7,0xda8a0600,0xa96f30bc,0x163138aa,0xe38dee4d,0xb0fb0e4e};
        for(int p=0;p<n;p+=64){int[] w=new int[68];for(int j=0;j<16;j++)w[j]=ByteBuffer.wrap(b,p+j*4,4).getInt();
            for(int j=16;j<68;j++){int x=w[j-16]^w[j-9]^Integer.rotateLeft(w[j-3],15);w[j]=x^Integer.rotateLeft(x,15)^Integer.rotateLeft(x,23)^Integer.rotateLeft(w[j-13],7)^w[j-6];}
            int a=v[0],c=v[2],d=v[3],e=v[4],f=v[5],g=v[6],h=v[7],bb=v[1];
            for(int j=0;j<64;j++){int a12=Integer.rotateLeft(a,12),ss1=Integer.rotateLeft(a12+e+Integer.rotateLeft(j<16?0x79cc4519:0x7a879d8a,j),7),ss2=ss1^a12;
                int ff=j<16?a^bb^c:(a&bb)|(a&c)|(bb&c),gg=j<16?e^f^g:(e&f)|(~e&g);
                int t1=ff+d+ss2+(w[j]^w[j+4]),t2=gg+h+ss1+w[j];d=c;c=Integer.rotateLeft(bb,9);bb=a;a=t1;h=g;g=Integer.rotateLeft(f,19);f=e;e=t2^Integer.rotateLeft(t2,9)^Integer.rotateLeft(t2,17);}
            int[] r={a,bb,c,d,e,f,g,h};for(int j=0;j<8;j++)v[j]^=r[j];}
        ByteBuffer result=ByteBuffer.allocate(32);for(int x:v)result.putInt(x);return result.array();
    }
}
