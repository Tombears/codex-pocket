package dev.codexpocket;

import android.content.*;
import android.security.keystore.*;
import android.util.Base64;
import org.json.*;
import java.security.*;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import java.util.*;
import java.util.concurrent.*;

final class Store {
    private final SharedPreferences prefs;
    private final SecretKey key;
    private JSONObject data;
    private final ScheduledThreadPoolExecutor disk=new ScheduledThreadPoolExecutor(1,r->{Thread t=new Thread(r,"codex-store");t.setDaemon(true);return t;});
    private ScheduledFuture<?> pendingSave;
    private String visibleChatId="";
    private boolean uiDirty=true;
    private final Map<String,StringBuilder> visibleDeltas=new LinkedHashMap<>();
    Runnable changed=()->{};
    java.util.function.Consumer<Exception> saveFailed=e->{};
    Store(Context c) throws Exception {
        disk.setRemoveOnCancelPolicy(true);
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
            if(ms!=null)for(int j=0;j<ms.length();j++){
                JSONObject message=ms.getJSONObject(j);if(message.optString("kind").equals("approval"))message.put("resolved",true);
                JSONArray images=message.optJSONArray("remoteImages");if(images!=null)for(int k=0;k<images.length();k++){JSONObject image=images.optJSONObject(k);if(image!=null&&Set.of("loading","pending").contains(image.optString("status")))image.put("status","failed");}
            }
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
    private void scheduleSave(){
        if(disk.isShutdown())return;
        if(pendingSave!=null)pendingSave.cancel(false);
        pendingSave=disk.schedule(()->{try{flush();}catch(Exception e){saveFailed.accept(e);}},650,TimeUnit.MILLISECONDS);
    }
    synchronized void close(){disk.shutdownNow();flush();}
    private void changedState(){uiDirty=true;visibleDeltas.clear();changed.run();}
    synchronized void touch(){scheduleSave();changedState();}
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
    synchronized JSONObject chatForRemote(String hostId,String remoteId){
        if(remoteId.isEmpty())return null;
        JSONArray chats=data.optJSONArray("chats");
        for(int i=0;i<chats.length();i++){
            JSONObject chat=chats.optJSONObject(i);
            if(hostId.equals(chat.optString("hostId"))&&remoteId.equals(chat.optString("remoteId")))return chat;
        }
        return null;
    }
    synchronized void showChat(String id){visibleChatId=id;uiDirty=true;visibleDeltas.clear();}
    synchronized JSONObject uiUpdate(boolean force){
        if(force||uiDirty){
            JSONObject state=uiState(true);
            uiDirty=false;visibleDeltas.clear();
            return Json.obj("type","state","state",state);
        }
        if(visibleDeltas.isEmpty())return null;
        JSONArray deltas=new JSONArray();
        visibleDeltas.forEach((id,text)->deltas.put(Json.obj("id",id,"text",text.toString())));
        visibleDeltas.clear();
        return Json.obj("type","messageDeltas","chatId",visibleChatId,"deltas",deltas);
    }
    synchronized JSONObject uiState(boolean includeMessages){
        JSONArray hosts=new JSONArray(),chats=new JSONArray();
        JSONArray sourceHosts=data.optJSONArray("hosts"),sourceChats=data.optJSONArray("chats");
        for(int i=0;i<sourceHosts.length();i++){
            JSONObject host=sourceHosts.optJSONObject(i);
            JSONObject safe=fieldsExcept(host,Set.of("password","privateKey","passphrase"));
            putValue(safe,"hasSecret",!host.optString("password").isEmpty()||!host.optString("privateKey").isEmpty());
            hosts.put(safe);
        }
        for(int i=0;i<sourceChats.length();i++){
            JSONObject chat=sourceChats.optJSONObject(i);
            JSONObject copy=fieldsExcept(chat,Set.of("messages"));
            if(includeMessages&&visibleChatId.equals(chat.optString("id"))){
                JSONArray messages=chat.optJSONArray("messages");
                try{putValue(copy,"messages",messages==null?new JSONArray():new JSONArray(messages.toString()));}
                catch(JSONException e){throw new IllegalStateException(e);}
            }
            chats.put(copy);
        }
        return Json.obj("hosts",hosts,"chats",chats,"theme",data.optString("theme","dark"),
                "approvalMode",data.optString("approvalMode","on-request"),"visibleChatId",visibleChatId);
    }
    private static JSONObject fieldsExcept(JSONObject source,Set<String> excluded){
        JSONObject copy=new JSONObject();
        for(Iterator<String> keys=source.keys();keys.hasNext();){String name=keys.next();if(!excluded.contains(name))putValue(copy,name,source.opt(name));}
        return copy;
    }
    private static void putValue(JSONObject o,String k,Object v){try{o.put(k,v);}catch(JSONException e){throw new IllegalStateException(e);}}
    static JSONObject find(JSONArray arr,String id){for(int i=0;i<arr.length();i++){JSONObject o=arr.optJSONObject(i);if(o.optString("id").equals(id))return o;}return null;}
    synchronized JSONObject prepareHost(JSONObject input)throws Exception{
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
        h.put("host",SshAddress.normalize(h.optString("host")));
        if(h.optString("name").isBlank()||h.optString("host").isBlank()||h.optString("user").isBlank())throw new Exception("请填写名称、地址和用户名");
        if(h.optInt("port")<1||h.optInt("port")>65535)throw new Exception("端口应为 1–65535");
        if(!Set.of("password","key").contains(h.optString("auth")))throw new Exception("请选择认证方式");
        Json.command(h.optString("cwd"),h.optString("codex","codex"));
        if(old!=null&&(!old.optString("host").equals(h.optString("host"))||old.optInt("port")!=h.optInt("port")))h.remove("fingerprint");
        if(h.optString(h.optString("auth").equals("key")?"privateKey":"password").isBlank())throw new Exception("请填写认证凭据");
        return h;
    }
    synchronized JSONObject saveHost(JSONObject input)throws Exception{
        JSONObject h=prepareHost(input);String id=h.getString("id");h.put("keepConnected",false);
        JSONArray hs=data.getJSONArray("hosts");remove(hs,id);hs.put(h);flush();changedState();return h;
    }
    synchronized void keepConnected(String id,boolean value){JSONObject h=host(id);if(h!=null&&h.optBoolean("keepConnected")!=value){put(h,"keepConnected",value);flush();}}
    synchronized Set<String> wantedConnections(){Set<String> ids=new HashSet<>();JSONArray hs=data.optJSONArray("hosts");for(int i=0;i<hs.length();i++){JSONObject h=hs.optJSONObject(i);if(h.optBoolean("keepConnected"))ids.add(h.optString("id"));}return ids;
    }
    synchronized JSONObject newChat(String hostId,String cwd)throws Exception{
        JSONObject host=host(hostId);if(host==null)throw new Exception("请先添加虚拟机");
        JSONObject chat=Json.obj("id",UUID.randomUUID().toString(),"hostId",hostId,"cwd",cwd,"title","新聊天","messages",new JSONArray(),"status","idle","updatedAt",System.currentTimeMillis());
        chat.put("approvalMode",data.optString("approvalMode","on-request"));data.getJSONArray("chats").put(chat);touch();return chat;
    }
    synchronized void put(JSONObject o,String k,Object value){try{o.put(k,value);}catch(Exception e){throw new IllegalStateException(e);}touch();}
    synchronized JSONObject message(JSONObject chat,String id,String role,String kind,String text){
        JSONArray messages=chat.optJSONArray("messages");JSONObject m=find(messages,id);
        if(m==null){m=Json.obj("id",id,"role",role,"kind",kind,"text",text);messages.put(m);}
        else try {m.put("text",text);}catch(Exception ignored){}
        touch();return m;
    }
    synchronized void append(JSONObject chat,String id,String role,String kind,String delta){
        if(delta.isEmpty())return;
        JSONObject m=find(chat.optJSONArray("messages"),id);
        if(m==null){message(chat,id,role,kind,delta);return;}
        putValue(m,"text",m.optString("text")+delta);
        scheduleSave();
        if(visibleChatId.equals(chat.optString("id"))){
            if(!uiDirty)visibleDeltas.computeIfAbsent(id,key->new StringBuilder()).append(delta);
            changed.run();
        }
    }
    synchronized void deleteChat(String id){remove(data.optJSONArray("chats"),id);flush();changedState();}
    synchronized void deleteHost(String id){
        remove(data.optJSONArray("hosts"),id);JSONArray cs=data.optJSONArray("chats");
        for(int i=cs.length()-1;i>=0;i--)if(cs.optJSONObject(i).optString("hostId").equals(id))cs.remove(i);
        flush();changedState();
    }
    synchronized void historyStale(String hostId){try{if(HistoryRefresh.invalidate(data.getJSONArray("chats"),hostId))touch();}catch(JSONException e){throw new IllegalStateException(e);}}
    synchronized void theme(String v){if(!v.equals("dark")&&!v.equals("light"))return;put(data,"theme",v);}
    synchronized String approvalMode(JSONObject chat){return ApprovalMode.require(chat.optString("approvalMode",data.optString("approvalMode","on-request")));}
    synchronized void approvalMode(String chatId,String mode){
        ApprovalMode.require(mode);
        JSONObject chat=chat(chatId);
        if(!chatId.isEmpty()&&chat==null)throw new IllegalArgumentException("聊天不存在");
        Object previousDefault=data.opt("approvalMode"),previousChat=chat==null?null:chat.opt("approvalMode");
        putValue(data,"approvalMode",mode);
        if(chat!=null)putValue(chat,"approvalMode",mode);
        try{flush();}catch(RuntimeException e){
            putValue(data,"approvalMode",previousDefault);
            if(chat!=null)putValue(chat,"approvalMode",previousChat);
            throw e;
        }
        changedState();
    }
    static void remove(JSONArray arr,String id){for(int i=arr.length()-1;i>=0;i--)if(arr.optJSONObject(i).optString("id").equals(id))arr.remove(i);}
}
