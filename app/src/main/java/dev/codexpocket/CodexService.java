package dev.codexpocket;

import android.app.*;
import android.content.*;
import android.os.*;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;

public final class CodexService extends Service {
    final class LocalBinder extends Binder { CodexService service(){return CodexService.this;} }
    interface Listener{void receive(JSONObject event);}
    private final IBinder binder=new LocalBinder();
    final ConcurrentHashMap<String,CodexEngine> engines=new ConcurrentHashMap<>();
    final ConcurrentHashMap<String,JSONObject> trustPrompts=new ConcurrentHashMap<>();
    private final ExecutorService workers=Executors.newCachedThreadPool();
    private final Handler main=new Handler(Looper.getMainLooper());
    Store store;
    String initError;
    private volatile Listener listener;
    private boolean updatePending;
    @Override public void onCreate(){
        super.onCreate();
        NotificationManager manager=getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("connection","SSH 与 Codex 连接",NotificationManager.IMPORTANCE_LOW));
        manager.createNotificationChannel(new NotificationChannel("attention","Codex 需要确认",NotificationManager.IMPORTANCE_DEFAULT));
        startForeground(1,notification("服务器连接将在后台保持",false));
        try{store=new Store(this);store.changed=this::update;}catch(Exception e){initError="无法读取本地加密数据，请勿清除应用数据；先保留安装并检查系统密钥库。";}
    }
    private Notification notification(String text,boolean attention){
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this,attention?"attention":"connection").setSmallIcon(dev.codexpocket.R.drawable.ic_app).setContentTitle("Codex Pocket").setContentText(text).setContentIntent(open).setOngoing(!attention).setAutoCancel(attention).build();
    }
    void notifyAttention(){getSystemService(NotificationManager.class).notify(2,notification("任务正在等待你的回应",true));}
    @Override public int onStartCommand(Intent intent,int flags,int startId){return START_NOT_STICKY;}
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
        for(var e:engines.entrySet())try{connections.put(e.getKey(),Json.obj("status",e.getValue().status,"busyChat",e.getValue().busyChat));}catch(Exception ignored){}
        try{out.put("connections",connections);out.put("trustPrompts",new JSONArray(trustPrompts.values()));}catch(Exception ignored){}return out;
    }
    private CodexEngine engine(String id){if(store.host(id)==null)throw new IllegalArgumentException("虚拟机不存在");return engines.computeIfAbsent(id,h->new CodexEngine(this,h));}
    void action(JSONObject a){
        String type=a.optString("type");
        if(type.equals("trust")){
            CodexEngine e=engines.get(a.optString("hostId"));if(e!=null)e.trust(a.optString("fingerprint"),a.optBoolean("accept"));trustPrompts.remove(a.optString("hostId"));update();return;
        }
        workers.submit(()->{
            try{
                if(store==null)throw new IllegalStateException(initError);
                String hostId=a.optString("hostId"),chatId=a.optString("chatId");
                switch(type){
                    case "saveHost":{
                        JSONObject input=a.getJSONObject("host");String id=input.optString("id");CodexEngine old=engines.get(id);
                        if(old!=null&&!old.busyChat.isEmpty())throw new Exception("任务运行中，请先停止再编辑连接");
                        JSONObject h=store.saveHost(input);if(old!=null){old.close();engines.remove(id);}event(Json.obj("type","hostSaved","hostId",h.optString("id")));break;
                    }
                    case "deleteHost":{
                        CodexEngine e=engines.get(hostId);if(e!=null&&!e.busyChat.isEmpty())throw new Exception("任务运行中，请先停止再删除");
                        if(e!=null)e.close();engines.remove(hostId);store.deleteHost(hostId);break;
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
                    case "disconnect":{CodexEngine e=engines.get(hostId);if(e!=null)e.close();trustPrompts.remove(hostId);break;}
                    case "send":{
                        JSONObject c=store.chat(chatId);if(c==null)throw new Exception("请先新建聊天");String text=a.optString("text").strip();if(text.isEmpty()||text.length()>100000)throw new Exception("消息不能为空或超过 100000 字符");engine(c.optString("hostId")).send(chatId,text);break;
                    }
                    case "stop":{JSONObject c=store.chat(chatId);if(c!=null)engine(c.optString("hostId")).interrupt(chatId);break;}
                    case "approve":engine(hostId).approve(a.optString("token"),a.optString("decision"),a.optJSONObject("answers"));break;
                    case "syncHistory":engine(hostId).syncHistory();break;
                    case "loadHistory":{JSONObject c=store.chat(chatId);if(c!=null)engine(c.optString("hostId")).loadHistory(chatId);break;}
                    case "theme":store.theme(a.optString("value"));break;
                    case "disconnectAll":for(CodexEngine e:engines.values())e.close();trustPrompts.clear();break;
                    default:throw new IllegalArgumentException("未知操作");
                }
                event(Json.obj("type","actionComplete","action",type,"chatId",chatId));update();
            }catch(Exception e){event(Json.obj("type","actionError","action",type,"chatId",a.optString("chatId"),"message",e.getMessage()==null?"操作失败":e.getMessage()));update();}
        });
    }
    @Override public void onDestroy(){for(CodexEngine e:engines.values())e.close();if(store!=null)store.flush();workers.shutdownNow();super.onDestroy();}
}
