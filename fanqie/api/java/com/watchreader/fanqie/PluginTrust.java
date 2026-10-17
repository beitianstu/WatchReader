package com.watchreader.fanqie;

import android.content.Context;
import android.content.pm.*;
import java.security.MessageDigest;

/** Mutual certificate pins: the existing host and plugin retain their upgrade identities. */
public final class PluginTrust {
    public static final String HOST="com.watchreader",PLUGIN="com.watchreader.fanqie",AUTHORITY=PLUGIN+".source";
    public static final String HOST_CERT="7928083b86fab05e32d55bf78154b37b27b2ce3114bfd47e94fea3a99f31af99";
    public static final String PLUGIN_CERT="69ad4949bb1c394c69b8a173b8d45b0b0368b4f54a7d939b5a4f5b2b35c62bd8";
    public static boolean trusted(Context context,String name,String pin){try{
        PackageInfo info=context.getPackageManager().getPackageInfo(name,PackageManager.GET_SIGNING_CERTIFICATES);
        if(info.signingInfo==null||info.signingInfo.hasMultipleSigners())return false;
        for(Signature signature:info.signingInfo.getApkContentsSigners()){
            byte[] bytes=MessageDigest.getInstance("SHA-256").digest(signature.toByteArray());StringBuilder hex=new StringBuilder();for(byte b:bytes)hex.append(String.format(java.util.Locale.ROOT,"%02x",b&255));if(pin.equals(hex.toString()))return true;
        }
    }catch(Exception ignored){}return false;}
    public static boolean host(Context context){return trusted(context,HOST,HOST_CERT);}
    public static boolean plugin(Context context){return trusted(context,PLUGIN,PLUGIN_CERT);}
    public static void enforceHost(Context context,int uid){try{if(context.getPackageManager().getPackageUid(HOST,0)==uid&&host(context))return;}catch(Exception ignored){}throw new SecurityException("Only the paired WatchReader host can use this data source");}
    private PluginTrust(){}
}
