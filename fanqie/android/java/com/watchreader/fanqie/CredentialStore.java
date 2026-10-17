package com.watchreader.fanqie;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Base64;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import org.json.*;

/** Account material is only stored in AES-GCM, with a nonexportable Android key. */
public final class CredentialStore {
    private static final String ALIAS="watchreader.fanqie.account.v1";
    private final Context ctx;
    public CredentialStore(Context c){ctx=c.getApplicationContext();}
    private SecretKey key()throws Exception {
        KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);
        if(!ks.containsAlias(ALIAS)){KeyGenerator g=KeyGenerator.getInstance("AES","AndroidKeyStore");g.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());g.generateKey();}
        return (SecretKey)ks.getKey(ALIAS,null);
    }
    public void put(String name,String value)throws Exception {
        Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,key());
        c.updateAAD(name.getBytes(StandardCharsets.UTF_8));byte[] body=c.doFinal(value.getBytes(StandardCharsets.UTF_8));
        String record=Base64.getEncoder().encodeToString(c.getIV())+":"+Base64.getEncoder().encodeToString(body);
        if(!ctx.getSharedPreferences("vault",0).edit().putString(name,record).commit())throw new Exception("账号保存失败");
    }
    public String get(String name)throws Exception {
        String record=ctx.getSharedPreferences("vault",0).getString(name,"");if(record.isEmpty())return "";
        String[] p=record.split(":",2);Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.getDecoder().decode(p[0])));c.updateAAD(name.getBytes(StandardCharsets.UTF_8));return new String(c.doFinal(Base64.getDecoder().decode(p[1])),StandardCharsets.UTF_8);
    }
    public void clear(){ctx.getSharedPreferences("vault",0).edit().clear().commit();}
    public static String parseCookie(String source)throws Exception {
        java.util.LinkedHashMap<String,String> values=new java.util.LinkedHashMap<>();
        java.util.Set<String> allowed=new java.util.HashSet<>(java.util.Arrays.asList("sessionid","sessionid_ss","sid_tt","sid_guard","uid_tt","uid_tt_ss","sid_ucp_v1","ssid_ucp_v1","passport_csrf_token","passport_csrf_token_default","passport_auth_status","passport_auth_status_ss","ttwid","odin_tt","d_ticket"));
        if(source.trim().startsWith("{")){
            JSONArray a=new JSONObject(source).optJSONArray("cookies");if(a==null)throw new Exception("Cookie 文件格式错误");
            for(int i=0;i<a.length();i++){JSONObject o=a.optJSONObject(i);if(o==null)continue;String domain=o.optString("domain").replaceFirst("^\\.","").toLowerCase(java.util.Locale.ROOT);if(!java.util.Arrays.asList("fanqienovel.com","api5-normal.fqnovel.com","fqnovel.com").contains(domain))continue;long expiry=o.optLong("expires_unix",0);if(expiry>0&&expiry<System.currentTimeMillis()/1000)continue;String n=o.optString("name"),v=o.optString("value");if(allowed.contains(n)&&!v.isEmpty())values.put(n,v);}
        } else {
            for(String part:source.split(";")){String[] p=part.trim().split("=",2);if(p.length==2&&allowed.contains(p[0]))values.put(p[0],p[1]);}
        }
        if(!values.containsKey("sessionid")&&!values.containsKey("sessionid_ss")&&!values.containsKey("sid_tt"))throw new Exception("没有可用的登录 Cookie");
        StringBuilder result=new StringBuilder();for(java.util.Map.Entry<String,String> p:values.entrySet()){if((p.getKey()+p.getValue()).contains("\r")||(p.getKey()+p.getValue()).contains("\n"))throw new Exception("Cookie 含无效字符");if(result.length()>0)result.append("; ");result.append(p.getKey()).append('=').append(p.getValue());}return result.toString();
    }
}
