package dev.codexpocket;

import android.content.*;
import android.os.*;
import android.security.keystore.*;
import android.util.Base64;
import org.json.*;
import java.security.*;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import java.util.*;

final class Store {
    private final SharedPreferences prefs;
    private final SecretKey key;
    private JSONObject data;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Runnable saver=()->flush();
    Runnable changed=()->{};
    Store(Context c) throws Exception {
        prefs=c.getSharedPreferences("encrypted_store",Context.MODE_PRIVATE);
        KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);
        if(!ks.containsAlias("codexpocket-v1")){
            KeyGenerator gen=KeyGenerator.getInstance("AES","AndroidKeyStore");
            gen.init(new KeyGenParameterSpec.Builder("codexpocket-v1",KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build()); gen.generateKey();
        }
        key=(SecretKey)ks.getKey("codexpocket-v1",null);
        String saved=prefs.getString("data",null);
        if(saved==null) data=Json.obj("hosts",new JSONArray(),"chats",new JSONArray(),"theme","dark");
        else {
            byte[] raw=Base64.decode(saved,Base64.NO_WRAP); Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,Arrays.copyOfRange(raw,0,12)));
            data=new JSONObject(new String(cipher.doFinal(Arrays.copyOfRange(raw,12,raw.length)),java.nio.charset.StandardCharsets.UTF_8));
        }
        boolean migrated=RemoteDefaults.migrate(data);
        // A process restart cannot preserve an SSH channel or pending request ids.
        JSONArray chats=data.getJSONArray("chats");
        for(int i=0;i<chats.length();i++){
            JSONObject chat=chats.getJSONObject(i);
            if(chat.optString("status").equals("working"))chat.put("status","interrupted");
            JSONArray ms=chat.optJSONArray("messages");
            if(ms!=null)for(int j=0;j<ms.length();j++)if(ms.getJSONObject(j).optString("kind").equals("approval"))ms.getJSONObject(j).put("resolved",true);
        }
        if(migrated)flush();
    }
    synchronized void flush(){
        try {
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key);
            byte[] body=cipher.doFinal(data.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            byte[] raw=new byte[12+body.length];System.arraycopy(cipher.getIV(),0,raw,0,12);System.arraycopy(body,0,raw,12,body.length);
            if(!prefs.edit().putString("data",Base64.encodeToString(raw,Base64.NO_WRAP)).commit())throw new IllegalStateException("无法保存本地数据");
        }catch(Exception e){throw new IllegalStateException("加密保存失败",e);}
    }
    synchronized void touch(){handler.removeCallbacks(saver);handler.postDelayed(saver,650);changed.run();}
    synchronized JSONObject snapshot(){
        JSONObject out=Json.copy(data);JSONArray hs=out.optJSONArray("hosts");
        for(int i=0;i<hs.length();i++){
            JSONObject h=hs.optJSONObject(i);
            try {h.put("hasSecret",!h.optString("password").isEmpty()||!h.optString("privateKey").isEmpty());}catch(Exception ignored){}
            h.remove("password");h.remove("privateKey");h.remove("passphrase");
        }
        return out;
    }
    synchronized JSONObject host(String id){return find(data.optJSONArray("hosts"),id);}
    synchronized JSONObject chat(String id){return find(data.optJSONArray("chats"),id);}
    static JSONObject find(JSONArray arr,String id){for(int i=0;i<arr.length();i++){JSONObject o=arr.optJSONObject(i);if(o.optString("id").equals(id))return o;}return null;}
    synchronized JSONObject saveHost(JSONObject input)throws Exception{
        String id=input.optString("id",UUID.randomUUID().toString());JSONObject old=host(id);
        JSONObject h=old==null?new JSONObject():Json.copy(old);
        for(String k:new String[]{"name","host","user","cwd","codex","auth","model","password","privateKey","passphrase"}){
            if(input.has(k)){
                String v=input.optString(k);
                if((k.equals("password")||k.equals("privateKey")||k.equals("passphrase"))&&v.isEmpty()&&old!=null)continue;
                h.put(k,v);
            }
        }
        h.put("id",id);h.put("port",input.optInt("port",22));h.put("pathDefaultsVersion",1);
        if(h.optString("name").isBlank()||h.optString("host").isBlank()||h.optString("user").isBlank())throw new Exception("请填写名称、地址和用户名");
        if(h.optInt("port")<1||h.optInt("port")>65535)throw new Exception("端口应为 1–65535");
        if(!Set.of("password","key").contains(h.optString("auth")))throw new Exception("请选择认证方式");
        Json.command(h.optString("cwd"),h.optString("codex","codex"));
        if(old!=null&&(!old.optString("host").equals(h.optString("host"))||old.optInt("port")!=h.optInt("port")))h.remove("fingerprint");
        JSONArray hs=data.getJSONArray("hosts");if(old!=null)remove(hs,id);hs.put(h);flush();changed.run();return h;
    }
    synchronized JSONObject newChat(String hostId,String cwd)throws Exception{
        JSONObject host=host(hostId);if(host==null)throw new Exception("请先添加虚拟机");
        JSONObject chat=Json.obj("id",UUID.randomUUID().toString(),"hostId",hostId,"cwd",cwd,"title","新聊天","messages",new JSONArray(),"status","idle","updatedAt",System.currentTimeMillis());
        data.getJSONArray("chats").put(chat);touch();return chat;
    }
    synchronized void put(JSONObject o,String k,Object value){try{o.put(k,value);}catch(Exception e){throw new IllegalStateException(e);}touch();}
    synchronized JSONObject message(JSONObject chat,String id,String role,String kind,String text){
        JSONArray messages=chat.optJSONArray("messages");JSONObject m=find(messages,id);
        if(m==null){m=Json.obj("id",id,"role",role,"kind",kind,"text",text);messages.put(m);}
        else try {m.put("text",text);}catch(Exception ignored){}
        touch();return m;
    }
    synchronized void append(JSONObject chat,String id,String role,String kind,String delta){
        JSONObject m=find(chat.optJSONArray("messages"),id);message(chat,id,role,kind,(m==null?"":m.optString("text"))+delta);
    }
    synchronized void deleteChat(String id){remove(data.optJSONArray("chats"),id);flush();changed.run();}
    synchronized void deleteHost(String id){
        remove(data.optJSONArray("hosts"),id);JSONArray cs=data.optJSONArray("chats");
        for(int i=cs.length()-1;i>=0;i--)if(cs.optJSONObject(i).optString("hostId").equals(id))cs.remove(i);
        flush();changed.run();
    }
    synchronized void theme(String v){if(!v.equals("dark")&&!v.equals("light"))return;put(data,"theme",v);}
    static void remove(JSONArray arr,String id){for(int i=arr.length()-1;i>=0;i--)if(arr.optJSONObject(i).optString("id").equals(id))arr.remove(i);}
}
