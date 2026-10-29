package dev.codexpocket;

import android.app.*;
import android.content.*;
import android.os.*;
import android.net.Uri;
import android.net.ConnectivityManager;
import android.net.Network;
import java.nio.charset.StandardCharsets;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;

public final class CodexService extends Service {
    final class LocalBinder extends Binder { CodexService service(){return CodexService.this;} }
    interface Listener{void receive(JSONObject event);}
    private final IBinder binder=new LocalBinder();
    final ConcurrentHashMap<String,CodexEngine> engines=new ConcurrentHashMap<>();
    final ConcurrentHashMap<String,JSONObject> trustPrompts=new ConcurrentHashMap<>();
    final ConcurrentHashMap<String,CodexEngine> tests=new ConcurrentHashMap<>();
    private final ReconnectPolicy reconnect=new ReconnectPolicy();
    private final Object connectionControl=new Object();
    private PowerManager.WakeLock wakeLock;
    private ConnectivityManager connectivity;
    private volatile boolean destroyed;
    private long wakeRenewAt;
    private final ConnectivityManager.NetworkCallback networks=new ConnectivityManager.NetworkCallback(){
        @Override public void onAvailable(Network network){reconnect.networkAvailable();main.post(()->maintainConnections());}
        @Override public void onLost(Network network){if(destroyed)return;for(CodexEngine e:engines.values())try{workers.submit(e::networkLost);}catch(RejectedExecutionException ignored){}}
    };
    private final ExecutorService workers=Executors.newCachedThreadPool();
    private final ExecutorService imageWorkers=Executors.newSingleThreadExecutor();
    private final Set<String> imageJobs=ConcurrentHashMap.newKeySet();
    private final Handler main=new Handler(Looper.getMainLooper());
    Store store;
    DiagnosticLog diagnostics;
    private Attachments attachments;
    private synchronized Attachments attachments()throws Exception{if(attachments==null)attachments=new Attachments(this);return attachments;}
    void importImages(java.util.List<Uri> uris,String draftKey){workers.submit(()->{JSONArray selected=new JSONArray();for(Uri uri:uris.subList(0,Math.min(4,uris.size())))try{selected.put(attachments().add(uri));}catch(Exception|OutOfMemoryError ex){event(Json.obj("type","toast","message","无法添加图片："+ex.getMessage()));}event(Json.obj("type","imagesSelected","draftKey",draftKey,"images",selected));});}
    private JSONObject remoteImage(String chatId,String messageId,int index){
        JSONObject c=store.chat(chatId);if(c==null)return null;JSONObject m=Store.find(c.optJSONArray("messages"),messageId);
        JSONArray images=m==null?null:m.optJSONArray("remoteImages");return images==null?null:images.optJSONObject(index);
    }
    void receiveRemoteImages(CodexEngine engine,String chatId,String messageId,List<String> inline){
        JSONObject c=store.chat(chatId);if(c==null)return;JSONObject m=Store.find(c.optJSONArray("messages"),messageId);JSONArray images=m==null?null:m.optJSONArray("remoteImages");
        int inlineIndex=0;
        if(images!=null)for(int i=0;i<images.length();i++){JSONObject image=images.optJSONObject(i);if(image==null)continue;String data=image.optBoolean("inline")&&inlineIndex<inline.size()?inline.get(inlineIndex++):null;if(image.optString("status").equals("pending"))loadRemoteImage(engine,chatId,messageId,i,false,data);}
    }
    private void loadRemoteImage(CodexEngine engine,String chatId,String messageId,int index,boolean open,String inline){
        JSONObject image=remoteImage(chatId,messageId,index);if(image==null)return;String job=chatId+"/"+messageId+"/"+index;
        if(!imageJobs.add(job))return;store.put(image,"status","loading");String path=image.optString("path");
        imageWorkers.submit(()->{
            try{
                if(inline==null&&path.isEmpty())throw new java.io.IOException("图片缓存已过期，请刷新远程记录");
                byte[] bytes;
                if(inline!=null)try{bytes=RemoteImages.decodeInline(inline);}catch(java.io.IOException ex){if(path.isEmpty())throw ex;bytes=engine.readImage(path);}else bytes=engine.readImage(path);
                JSONObject preview=attachments().add(bytes);JSONObject current=remoteImage(chatId,messageId,index);
                if(current==null||!path.equals(current.optString("path"))){attachments().remove(preview.optString("id"));return;}
                for(String key:new String[]{"id","preview","width","height"})store.put(current,key,preview.opt(key));store.put(current,"status","ready");
                if(open)event(Json.obj("type","remoteImageOpened","chatId",chatId,"name",current.optString("name"),"preview",attachments().fullPreview(current.optString("id"))));
            }catch(Exception|OutOfMemoryError ex){JSONObject current=remoteImage(chatId,messageId,index);if(current!=null&&path.equals(current.optString("path")))store.put(current,"status","failed");if(open)event(Json.obj("type","toast","message","图片加载失败，请检查服务器连接后重试"));}
            finally{imageJobs.remove(job);update();}
        });
    }
    String initError;
    private volatile Listener listener;
    private boolean updatePending;
    @Override public void onCreate(){
        super.onCreate();
        NotificationManager manager=getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("connection","SSH 与 Codex 连接",NotificationManager.IMPORTANCE_LOW));
        manager.createNotificationChannel(new NotificationChannel("attention","Codex 需要确认",NotificationManager.IMPORTANCE_DEFAULT));
        startForeground(1,notification("服务器连接将在后台保持",false));
        try{diagnostics=new DiagnosticLog(this);log("INFO","app","service started");}catch(Exception e){android.util.Log.w("CodexPocket","Diagnostics unavailable");}
        main.postDelayed(new Runnable(){public void run(){if(diagnostics!=null)try{diagnostics.prune();}catch(Exception ignored){}main.postDelayed(this,60*60*1000L);}},60*60*1000L);
        try{store=new Store(this);store.changed=this::update;}catch(Exception e){initError="无法读取本地加密数据，请勿清除应用数据；先保留安装并检查系统密钥库。";}
        wakeLock=getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"CodexPocket:connections");wakeLock.setReferenceCounted(false);
        connectivity=getSystemService(ConnectivityManager.class);try{connectivity.registerDefaultNetworkCallback(networks);}catch(Exception e){log("WARN","network",e.getClass().getSimpleName());}
        main.post(new Runnable(){public void run(){if(destroyed)return;maintainConnections();main.postDelayed(this,5000);}});
    }
    private Notification notification(String text,boolean attention){
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this,attention?"attention":"connection").setSmallIcon(dev.codexpocket.R.drawable.ic_app).setContentTitle("Codex Pocket").setContentText(text).setContentIntent(open).setOngoing(!attention).setAutoCancel(attention).build();
    }
    void notifyAttention(){getSystemService(NotificationManager.class).notify(2,notification("任务正在等待你的回应",true));}
    void log(String level,String tag,String message){if(diagnostics!=null)try{diagnostics.record(level,tag,message);}catch(Exception ignored){}}
    void exportLogs(Uri uri){workers.submit(()->{try{if(diagnostics==null)throw new java.io.IOException("日志暂不可用");try(java.io.OutputStream out=getContentResolver().openOutputStream(uri,"wt")){if(out==null)throw new java.io.IOException("无法写入所选文件");out.write(diagnostics.exportText().getBytes(StandardCharsets.UTF_8));}event(Json.obj("type","toast","message","已导出最近 24 小时日志"));}catch(Exception e){event(Json.obj("type","toast","message","日志导出失败："+e.getMessage()));}});}
    @Override public int onStartCommand(Intent intent,int flags,int startId){return START_STICKY;}
    void connected(String id){synchronized(connectionControl){CodexEngine e=engines.get(id);if(!destroyed&&e!=null&&e.connection.snapshot().status().equals("online")){store.keepConnected(id,true);reconnect.succeeded(id);}}}
    private void disconnect(String id){synchronized(connectionControl){store.keepConnected(id,false);reconnect.forget(id);CodexEngine e=engines.remove(id);if(e!=null)e.dispose();trustPrompts.remove(id);}}
    private void maintainConnections(){
        if(destroyed||store==null)return;Set<String> wanted=store.wantedConnections();
        boolean active=!wanted.isEmpty()||!tests.isEmpty()||engines.values().stream().anyMatch(e->e.connection.snapshot().status().equals("connecting"));
        if(active&&(!wakeLock.isHeld()||SystemClock.elapsedRealtime()>=wakeRenewAt)){if(wakeLock.isHeld())wakeLock.release();wakeLock.acquire(10*60*1000L);wakeRenewAt=SystemClock.elapsedRealtime()+5*60*1000L;}else if(!active&&wakeLock.isHeld())wakeLock.release();
        if(connectivity.getActiveNetwork()==null)return;
        for(String id:wanted){
            JSONObject h=store.host(id);if(h==null||h.optString("fingerprint").isEmpty())continue;
            CodexEngine e=engine(id);String status=e.connection.snapshot().status();
            if(status.equals("online")&&e.transportAlive()||status.equals("connecting"))continue;
            if(!reconnect.begin(id,SystemClock.elapsedRealtime()))continue;
            workers.submit(()->{try{if(destroyed||!store.wantedConnections().contains(id)){reconnect.forget(id);return;}log("INFO","reconnect/"+id,"Reconnecting transport");e.connect();}catch(Exception|LinkageError ex){reconnect.failed(id,SystemClock.elapsedRealtime());log("WARN","reconnect/"+id,ex.getClass().getSimpleName()+": "+ex.getMessage());}finally{update();}});
        }
    }
    private void testConnection(JSONObject action){
        String id=action.optString("testId");if(!id.matches("[A-Za-z0-9-]{1,80}"))return;
        cancelTests();
        try{
            JSONObject preview=store.prepareHost(action.getJSONObject("host"));CodexEngine e=new CodexEngine(this,"test-"+id,preview);tests.put(id,e);update();
            workers.submit(()->{try{if(tests.get(id)!=e)return;e.connect();if(tests.get(id)==e)event(Json.obj("type","connectionTestResult","testId",id,"ok",true,"cwd",e.runtimeCwd,"codex",e.runtimeCodex));}catch(Exception|LinkageError ex){if(tests.get(id)==e)event(Json.obj("type","connectionTestResult","testId",id,"ok",false,"error",ex.getMessage()));}finally{tests.remove(id,e);e.dispose();update();}});
        }catch(Exception ex){event(Json.obj("type","connectionTestResult","testId",id,"ok",false,"error",ex.getMessage()));}
    }
    private void cancelTests(){for(var entry:tests.entrySet())if(tests.remove(entry.getKey(),entry.getValue()))entry.getValue().dispose();}
    @Override public IBinder onBind(Intent intent){return binder;}
    void listen(Listener l){listener=l;update();}
    void unlisten(Listener l){if(listener==l)listener=null;}
    void event(JSONObject e){
        if(e.optString("type").equals("trust"))trustPrompts.put(e.optString("hostId"),e);
        main.post(()->{Listener l=listener;if(l!=null)l.receive(e);});
    }
    synchronized void update(){
        if(updatePending)return;updatePending=true;
        main.postDelayed(()->{synchronized(this){updatePending=false;}if(store!=null)event(Json.obj("type","state","state",snapshot()));},100);
    }
    JSONObject snapshot(){
        if(store==null)return Json.obj("error",initError);
        JSONObject out=store.snapshot(),connections=new JSONObject();
        for(var e:engines.entrySet())try{ConnectionProgress.Snapshot progress=e.getValue().connection.snapshot();connections.put(e.getKey(),Json.obj("status",progress.status(),"phase",progress.phase(),"error",progress.error(),"cwd",e.getValue().runtimeCwd,"codex",e.getValue().runtimeCodex,"busyChat",e.getValue().busyChat));}catch(Exception ignored){}
        try{JSONObject progress=new JSONObject();for(var entry:tests.entrySet()){var s=entry.getValue().connection.snapshot();progress.put(entry.getKey(),Json.obj("phase",s.phase(),"status",s.status()));}out.put("connectionTests",progress);out.put("batteryUnrestricted",getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(getPackageName()));out.put("connections",connections);out.put("trustPrompts",new JSONArray(trustPrompts.values()));}catch(Exception ignored){}return out;
    }
    private CodexEngine engine(String id){if(store.host(id)==null)throw new IllegalArgumentException("虚拟机不存在");return engines.computeIfAbsent(id,h->new CodexEngine(this,h));}
    void action(JSONObject a){
        String type=a.optString("type");
        if(type.equals("removeImage")){try{attachments().remove(a.optString("id"));}catch(Exception ignored){}return;}
        if(type.equals("testConnection")){testConnection(a);return;}
        if(type.equals("cancelConnectionTest")){cancelTests();return;}
        if(type.equals("trust")){
            String id=a.optString("hostId");CodexEngine e=id.startsWith("test-")?tests.get(id.substring(5)):engines.get(id);if(e!=null)e.trust(a.optString("fingerprint"),a.optBoolean("accept"));trustPrompts.remove(id);update();return;
        }
        workers.submit(()->{
            try{
                if(store==null)throw new IllegalStateException(initError);
                String hostId=a.optString("hostId"),chatId=a.optString("chatId");
                switch(type){
                    case "saveHost":{
                        JSONObject input=a.getJSONObject("host");String id=input.optString("id");CodexEngine old=engines.get(id);
                        if(old!=null&&!old.busyChat.isEmpty())throw new Exception("任务运行中，请先停止再编辑连接");
                        store.prepareHost(input);disconnect(id);JSONObject h=store.saveHost(input);if(old!=null)engines.remove(id);event(Json.obj("type","hostSaved","hostId",h.optString("id")));break;
                    }
                    case "deleteHost":{
                        CodexEngine e=engines.get(hostId);if(e!=null&&!e.busyChat.isEmpty())throw new Exception("任务运行中，请先停止再删除");
                        disconnect(hostId);engines.remove(hostId);store.deleteHost(hostId);break;
                    }
                    case "newChat":{
                        JSONObject h=store.host(hostId);if(h==null)throw new Exception("请先添加虚拟机");String cwd=a.optString("cwd",h.optString("cwd"));Json.command(cwd,h.optString("codex","codex"));
                        JSONObject c=store.newChat(hostId,cwd);event(Json.obj("type","chatCreated","chatId",c.optString("id"),"hostId",hostId,"state",snapshot()));break;
                    }
                    case "deleteChat":{
                        JSONObject c=store.chat(chatId);if(c==null)break;CodexEngine e=engines.get(c.optString("hostId"));if(e!=null&&e.busyChat.equals(chatId))throw new Exception("任务运行中，请先停止再删除");store.deleteChat(chatId);break;
                    }
                    case "renameChat":{JSONObject c=store.chat(chatId);if(c!=null&&!a.optString("title").isBlank())store.put(c,"title",a.optString("title"));break;}
                    case "connect":engine(hostId).connect();event(Json.obj("type","toast","message","SSH 与 Codex 已连接"));break;
                    case "disconnect":disconnect(hostId);break;
                    case "send":case "steer":{
                        JSONObject c=store.chat(chatId);if(c==null)throw new Exception("请先新建聊天");String text=a.optString("text").strip();Attachments.Batch images=attachments().prepare(a.optJSONArray("images"));if(text.isEmpty()&&images.input().length()==0||text.length()>100000)throw new Exception("请填写消息或选择图片，文字不能超过 100000 字符");CodexEngine e=engine(c.optString("hostId"));if(type.equals("steer"))e.steer(chatId,text,images);else e.send(chatId,text,images);for(int i=0;i<images.previews().length();i++)attachments().remove(images.previews().getJSONObject(i).getString("id"));break;
                    }
                    case "stop":{JSONObject c=store.chat(chatId);if(c!=null)engine(c.optString("hostId")).interrupt(chatId);break;}
                    case "approve":engine(hostId).approve(a.optString("token"),a.optString("decision"),a.optJSONObject("answers"));break;
                    case "syncHistory":engine(hostId).syncHistory();break;
                    case "loadHistory":{JSONObject c=store.chat(chatId);if(c!=null)engine(c.optString("hostId")).loadHistory(chatId);break;}
                    case "theme":store.theme(a.optString("value"));break;
                    case "approvalMode":store.approvalMode(chatId,a.optString("value"));break;
                    case "loadRemoteImage":case "viewRemoteImage":{
                        JSONObject c=store.chat(chatId);if(c==null)throw new Exception("聊天不存在");String messageId=a.optString("messageId");int index=a.optInt("index",-1);JSONObject image=remoteImage(chatId,messageId,index);if(image==null)throw new Exception("图片不存在");
                        if(type.equals("viewRemoteImage")&&!image.optString("id").isEmpty())try{event(Json.obj("type","remoteImageOpened","chatId",chatId,"name",image.optString("name"),"preview",attachments().fullPreview(image.optString("id"))));break;}catch(Exception ignored){}
                        if(image.optBoolean("inline")&&image.optString("path").isEmpty())throw new Exception("图片缓存已过期，请刷新远程记录重新接收");
                        CodexEngine e=engine(c.optString("hostId"));e.connect();loadRemoteImage(e,chatId,messageId,index,type.equals("viewRemoteImage"),null);break;
                    }
                    case "disconnectAll":for(String id:store.wantedConnections())disconnect(id);for(CodexEngine e:engines.values())disconnect(e.hostId);cancelTests();trustPrompts.clear();break;
                    default:throw new IllegalArgumentException("未知操作");
                }
                event(Json.obj("type","actionComplete","action",type,"chatId",chatId));update();
            }catch(Exception|LinkageError e){log("ERROR","action/"+type,e.getClass().getSimpleName()+": "+e.getMessage());event(Json.obj("type","actionError","action",type,"chatId",a.optString("chatId"),"message",e.getMessage()==null?e.getClass().getSimpleName():e.getMessage()));update();}
        });
    }
    @Override public void onDestroy(){destroyed=true;main.removeCallbacksAndMessages(null);if(connectivity!=null)try{connectivity.unregisterNetworkCallback(networks);}catch(Exception ignored){}cancelTests();for(CodexEngine e:engines.values())e.dispose();imageWorkers.shutdownNow();if(wakeLock!=null&&wakeLock.isHeld())wakeLock.release();if(store!=null)store.flush();workers.shutdownNow();log("INFO","app","service stopped");if(diagnostics!=null)diagnostics.close();super.onDestroy();}
}
