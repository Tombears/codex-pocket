package dev.codexpocket;

import com.jcraft.jsch.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;

final class CodexEngine {
    final String hostId;
    private final CodexService owner;
    private final Store store;
    private Session ssh;
    private ChannelExec channel;
    private volatile Rpc rpc;
    private final Set<String> loaded=ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String,Pending> approvals=new ConcurrentHashMap<>();
    private volatile CompletableFuture<Boolean> trust;
    private volatile String candidateFingerprint;
    final ConnectionProgress connection=new ConnectionProgress();
    private final ReentrantLock connectLock=new ReentrantLock();
    private static final ScheduledExecutorService DEADLINES=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"ssh-connect-deadline");t.setDaemon(true);return t;});
    private volatile ScheduledFuture<?> watchdog;
    volatile String busyChat="";
    private volatile String turnId="";
    private volatile boolean closing;
    private final StderrTail stderr=new StderrTail(4000);
    volatile String runtimeCwd="",runtimeCodex="";
    record Pending(Object id,String method,JSONObject params,String chatId){}
    CodexEngine(CodexService owner,String hostId){this.owner=owner;this.hostId=hostId;this.store=owner.store;}
    private void phase(long attempt,String label)throws IOException{
        if(!connection.stage(attempt,label))throw new IOException("连接已取消或超时");owner.update();
    }
    private void requireActive(long attempt)throws IOException{if(!connection.active(attempt))throw new IOException("连接已取消或超时");}
    void connect()throws Exception{
        if(!connectLock.tryLock())throw new IOException("该虚拟机正在连接，可先取消连接再重试");
        long attempt=0;
        try{
            if(rpc!=null&&ssh!=null&&ssh.isConnected()&&channel!=null&&channel.isConnected()&&connection.snapshot().status().equals("online"))return;
            close();closing=false;stderr.clear();runtimeCwd="";runtimeCodex="";attempt=connection.begin();owner.update();
            final long token=attempt;
            watchdog=DEADLINES.scheduleAtFixedRate(()->{
                if(connection.expire(token)){closing=true;owner.trustPrompts.remove(hostId);owner.update();cleanupTransport();}
            },1,1,TimeUnit.SECONDS);
            JSONObject h=store.host(hostId);if(h==null)throw new IOException("虚拟机不存在");
            phase(attempt,"加载 SSH 密钥");
            // Use the full BC provider for Ed25519/modern OpenSSH keys on Android.
            synchronized(CodexEngine.class){
                if(!(Security.getProvider("BC") instanceof org.bouncycastle.jce.provider.BouncyCastleProvider)){
                    Security.removeProvider("BC");Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider());
                }
            }
            JSch.setConfig("prefer_known_host_key_types","no");
            // Android does not expose all desktop JDK EdEC/XDH APIs.
            JSch.setConfig("xdh","com.jcraft.jsch.bc.XDH");
            JSch.setConfig("keypairgen.eddsa","com.jcraft.jsch.bc.KeyPairGenEdDSA");
            JSch.setConfig("ssh-ed25519","com.jcraft.jsch.bc.SignatureEd25519");
            JSch.setConfig("ssh-ed448","com.jcraft.jsch.bc.SignatureEd448");
            JSch jsch=new JSch();
            jsch.setHostKeyRepository(new HostKeyRepository(){
                public int check(String name,byte[] key){
                    try{
                        String fp="SHA256:"+android.util.Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(key),android.util.Base64.NO_WRAP|android.util.Base64.NO_PADDING);
                        String saved=h.optString("fingerprint");
                        if(!saved.isEmpty())return saved.equals(fp)?OK:CHANGED;
                        phase(token,"等待确认服务器指纹");
                        trust=new CompletableFuture<>();candidateFingerprint=fp;
                        owner.event(Json.obj("type","trust","hostId",hostId,"name",h.optString("name"),"address",name,"fingerprint",fp));
                        boolean yes=trust.get(30,TimeUnit.SECONDS);trust=null;owner.trustPrompts.remove(hostId);
                        if(yes){phase(token,"验证 SSH 身份");store.put(h,"fingerprint",fp);store.flush();return OK;}
                    }catch(Exception ignored){}return NOT_INCLUDED;
                }
                public void add(HostKey key,UserInfo ui){}
                public void remove(String host,String type){}
                public void remove(String host,String type,byte[] key){}
                public String getKnownHostsRepositoryID(){return "Codex Pocket pinned hosts";}
                public HostKey[] getHostKey(){return new HostKey[0];}
                public HostKey[] getHostKey(String host,String type){return new HostKey[0];}
            });
            if(h.optString("auth").equals("key")){
                byte[] key=h.optString("privateKey").getBytes(StandardCharsets.UTF_8);
                byte[] pass=h.optString("passphrase").isEmpty()?null:h.optString("passphrase").getBytes(StandardCharsets.UTF_8);
                try{jsch.addIdentity("device-key",key,null,pass);}finally{Arrays.fill(key,(byte)0);if(pass!=null)Arrays.fill(pass,(byte)0);}
            }
            ssh=jsch.getSession(h.optString("user"),h.optString("host"),h.optInt("port",22));
            ssh.setConfig("StrictHostKeyChecking","yes");
            ssh.setConfig("PreferredAuthentications",h.optString("auth").equals("key")?"publickey":"password");
            if(h.optString("auth").equals("password"))ssh.setPassword(h.optString("password"));
            phase(attempt,"连接 SSH 并验证身份");
            ssh.setServerAliveInterval(15000);ssh.setServerAliveCountMax(3);ssh.setTimeout(30000);ssh.connect(30000);
            requireActive(attempt);phase(attempt,"自动识别目录与 Codex");
            channel=(ChannelExec)ssh.openChannel("exec");
            channel.setCommand(Json.command(h.optString("cwd"),h.optString("codex","codex")));
            channel.setPty(false);
            InputStream stdout=channel.getInputStream();OutputStream stdin=channel.getOutputStream();
            channel.setErrStream(stderr);
            channel.connect(30000);requireActive(attempt);phase(attempt,"等待 Codex 握手");
            rpc=new Rpc(stdout,stdin,this::onEvent,e->{if(!closing&&connection.current(token)){fail(e);cleanupTransport();}});
            rpc.request("initialize",Json.obj("clientInfo",Json.obj("name","codex_pocket","title","Codex Pocket","version","0.1.1")),30);
            requireActive(attempt);
            rpc.send(Json.obj("method","initialized","params",new JSONObject()));
            for(String line:stderr.text().split("\n")){
                if(line.startsWith("__CP_CWD__="))runtimeCwd=line.substring(11);
                if(line.startsWith("__CP_BIN__="))runtimeCodex=line.substring(11);
            }
            if(!connection.online(attempt))throw new IOException("连接已取消或超时");owner.update();
        }catch(Exception|LinkageError e){
            String label=connection.snapshot().phase();
            String message=(label.isEmpty()?"":label+"失败：")+errorMessage(e);
            if(connection.current(attempt)){closing=true;connection.fail(attempt,message);owner.update();cleanupTransport();}
            throw new IOException(message,e);
        }finally{
            ScheduledFuture<?> timer=watchdog;watchdog=null;if(timer!=null)timer.cancel(false);
            connectLock.unlock();
        }
    }
    private String errorMessage(Throwable e){
        String message=e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();
        if(message.toLowerCase(Locale.ROOT).contains("hostkey")||message.contains("HostKey"))return "SSH 主机密钥未获信任或已发生变化，请在云控制台核对后再处理";
        if(message.contains("Auth fail"))return "SSH 登录失败，请检查用户名、密码或私钥";
        if(e instanceof LinkageError)return "当前 Android 加密组件不兼容（"+e.getClass().getSimpleName()+"）："+message;
        if(!stderr.text().isBlank())message+="\n"+stderr.text();
        return message;
    }
    void trust(String fingerprint,boolean yes){CompletableFuture<Boolean> f=trust;if(f!=null&&Objects.equals(candidateFingerprint,fingerprint))f.complete(yes);}
    synchronized void send(String chatId,String text)throws Exception{
        if(!busyChat.isEmpty())throw new IOException("这台虚拟机已有任务运行，请先等待或停止");
        connect();JSONObject chat=store.chat(chatId);if(chat==null||!chat.optString("hostId").equals(hostId))throw new IOException("聊天与虚拟机不匹配");
        busyChat=chatId;turnId="";store.put(chat,"status","working");
        try{
            String remote=chat.optString("remoteId");
            JSONObject h=store.host(hostId);
            JSONObject params=Json.obj("approvalPolicy","on-request","approvalsReviewer","user","sandbox","workspace-write");
            if(!chat.optString("cwd").isBlank())params.put("cwd",chat.optString("cwd"));
            if(!remote.isEmpty())params.put("threadId",remote);
            JSONObject result;
            if(!loaded.contains(chatId)){
                result=rpc.request(remote.isEmpty()?"thread/start":"thread/resume",params);
                remote=result.getJSONObject("thread").getString("id");store.put(chat,"remoteId",remote);
                String resolvedCwd=result.getJSONObject("thread").optString("cwd",runtimeCwd);
                if(!resolvedCwd.isBlank())store.put(chat,"cwd",resolvedCwd);loaded.add(chatId);
            }
            store.message(chat,"user-"+UUID.randomUUID(),"user","text",text);
            if(chat.optString("title").equals("新聊天"))store.put(chat,"title",text.substring(0,Math.min(28,text.length())));
            store.put(chat,"updatedAt",System.currentTimeMillis());
            JSONObject turnParams=Json.obj("threadId",remote,"input",new JSONArray().put(Json.obj("type","text","text",text)),"approvalPolicy","on-request");
            if(!chat.optString("cwd").isBlank())turnParams.put("cwd",chat.optString("cwd"));
            String model=h.optString("model");if(!model.isBlank())turnParams.put("model",model);
            JSONObject r=rpc.request("turn/start",turnParams);
            // A very short turn can complete before the request response is read.
            if(busyChat.equals(chatId))turnId=r.optJSONObject("turn").optString("id");
        }catch(Exception e){store.put(chat,"status","error");store.message(chat,"err-"+UUID.randomUUID(),"system","error",errorMessage(e));busyChat="";turnId="";throw e;}
    }
    void interrupt(String chatId)throws Exception{
        if(!busyChat.equals(chatId)||rpc==null)throw new IOException("当前聊天没有运行中的任务");
        if(turnId.isEmpty())throw new IOException("任务正在启动，稍后再停止");
        JSONObject c=store.chat(chatId);rpc.request("turn/interrupt",Json.obj("threadId",c.optString("remoteId"),"turnId",turnId));
    }
    private JSONObject chatFor(String remote){
        JSONObject snapshot=store.snapshot();JSONArray chats=snapshot.optJSONArray("chats");
        for(int i=0;i<chats.length();i++){JSONObject c=chats.optJSONObject(i);if(c.optString("hostId").equals(hostId)&&c.optString("remoteId").equals(remote))return store.chat(c.optString("id"));}
        return null;
    }
    private void onEvent(JSONObject event){
        try{
            String method=event.optString("method");JSONObject p=event.optJSONObject("params");if(p==null)p=new JSONObject();
            JSONObject c=chatFor(p.optString("threadId"));
            if(event.has("id")){
                if(c!=null&&(method.equals("item/commandExecution/requestApproval")||method.equals("item/fileChange/requestApproval")||method.equals("item/tool/requestUserInput"))){
                    String token=UUID.randomUUID().toString();approvals.put(token,new Pending(event.get("id"),method,p,c.optString("id")));
                    JSONObject m=store.message(c,token,"system","approval",p.optString("command",p.optString("reason","Codex 需要你的确认")));
                    store.put(m,"approvalType",method);store.put(m,"params",p);owner.notifyAttention();
                }else{
                    if(method.equals("item/permissions/requestApproval"))rpc.send(Json.obj("id",event.get("id"),"result",Json.obj("permissions",new JSONObject())));
                    else if(method.equals("mcpServer/elicitation/request"))rpc.send(Json.obj("id",event.get("id"),"result",Json.obj("action","decline","content",null)));
                    else rpc.send(Json.obj("id",event.get("id"),"error",Json.obj("code",-32601,"message","This client does not support this interactive request")));
                    if(c!=null)store.message(c,"unsupported-"+UUID.randomUUID(),"system","error","此交互类型首版尚不支持，已拒绝："+method);
                }return;
            }
            if(method.equals("serverRequest/resolved")){
                Object request=p.opt("requestId");if(request!=null)for(var a:approvals.entrySet())if(a.getValue().id.toString().equals(request.toString()))resolveLocal(a.getKey(),"已结束");return;
            }
            if(c==null)return;
            if(method.equals("turn/started")){
                JSONObject t=p.optJSONObject("turn");if(t!=null){busyChat=c.optString("id");turnId=t.optString("id");store.put(c,"status","working");}
            }else if(method.equals("item/agentMessage/delta"))store.append(c,p.optString("itemId"),"assistant","text",p.optString("delta"));
            else if(method.equals("item/commandExecution/outputDelta"))store.append(c,p.optString("itemId"),"tool","command",p.optString("delta"));
            else if(method.equals("item/started")||method.equals("item/completed")){
                JSONObject item=p.optJSONObject("item");if(item!=null)recordItem(c,item,method.endsWith("completed"));
            }else if(method.equals("turn/completed")){
                JSONObject t=p.optJSONObject("turn");String status=t==null?"completed":t.optString("status","completed");
                store.put(c,"status",status);store.put(c,"updatedAt",System.currentTimeMillis());
                if(t!=null&&!t.isNull("error"))store.message(c,"err-"+UUID.randomUUID(),"system","error",t.optJSONObject("error").optString("message","Codex 执行失败"));
                resolveAll(c.optString("id"));if(busyChat.equals(c.optString("id"))){busyChat="";turnId="";}owner.update();
            }else if(method.equals("error"))store.message(c,"err-"+UUID.randomUUID(),"system","error",p.optJSONObject("error")==null?p.toString():p.optJSONObject("error").optString("message"));
        }catch(Exception e){owner.event(Json.obj("type","toast","message","协议事件处理失败："+e.getMessage()));}
    }
    private void recordItem(JSONObject c,JSONObject item,boolean completed)throws Exception{
        String type=item.optString("type"),id=item.optString("id");
        if(type.equals("agentMessage")){
            if(!item.optString("text").isEmpty())store.message(c,id,"assistant","text",item.optString("text"));
        }else if(type.equals("commandExecution")){
            JSONObject existing=Store.find(c.optJSONArray("messages"),id);
            String output=completed?item.optString("aggregatedOutput",existing==null?"":existing.optString("text")):"";
            JSONObject m=store.message(c,id,"tool","command",output);store.put(m,"command",item.optString("command"));store.put(m,"status",item.optString("status"));if(!item.isNull("exitCode"))store.put(m,"exitCode",item.optInt("exitCode"));
        }else if(type.equals("fileChange")){
            JSONObject m=store.message(c,id,"tool","files",item.optJSONArray("changes")==null?"":item.optJSONArray("changes").toString(2));store.put(m,"status",item.optString("status"));
        }else if(type.equals("userMessage")){
            JSONArray content=item.optJSONArray("content");StringBuilder text=new StringBuilder();if(content!=null)for(int i=0;i<content.length();i++)text.append(content.optJSONObject(i).optString("text"));
            JSONArray messages=c.optJSONArray("messages");
            // turn/start is also echoed as a userMessage item by app-server.
            boolean reconciled=false;
            for(int i=messages.length()-1;i>=0;i--){JSONObject m=messages.optJSONObject(i);if(m.optString("id").startsWith("user-")&&m.optString("text").equals(text.toString())){store.put(m,"id",id);reconciled=true;break;}}
            if(!reconciled)store.message(c,id,"user","text",text.toString());
        }
    }
    void approve(String token,String decision,JSONObject answers)throws Exception{
        Pending p=approvals.get(token);if(p==null||rpc==null)throw new IOException("该请求已结束或连接已断开");
        JSONObject c=store.chat(p.chatId);if(c==null||!c.optString("remoteId").equals(p.params.optString("threadId")))throw new IOException("审批会话不匹配");
        JSONObject result;
        if(p.method.equals("item/tool/requestUserInput"))result=Json.obj("answers",answers==null?new JSONObject():answers);
        else {if(!Set.of("accept","decline").contains(decision))throw new IOException("无效审批决定");result=Json.obj("decision",decision);}
        rpc.send(Json.obj("id",p.id,"result",result));resolveLocal(token,decision.equals("accept")?"已允许":"已回应");
    }
    private void resolveLocal(String token,String label){Pending p=approvals.remove(token);if(p==null)return;JSONObject c=store.chat(p.chatId);if(c==null)return;JSONObject m=Store.find(c.optJSONArray("messages"),token);if(m!=null){store.put(m,"resolved",true);store.put(m,"resolution",label);}}
    private void resolveAll(String chatId){for(var e:approvals.entrySet())if(chatId==null||e.getValue().chatId.equals(chatId))resolveLocal(e.getKey(),"请求已结束");}
    synchronized void syncHistory()throws Exception{
        connect();String cursor=null;int count=0;
        do{
            JSONObject p=Json.obj("limit",50,"sortKey","updated_at","sourceKinds",new JSONArray().put("cli").put("vscode").put("appServer").put("exec"));if(cursor!=null)p.put("cursor",cursor);
            JSONObject r=rpc.request("thread/list",p);JSONArray rows=r.optJSONArray("data");
            if(rows==null)break;
            for(int i=0;i<rows.length();i++){
                JSONObject row=rows.getJSONObject(i);if(chatFor(row.optString("id"))!=null)continue;
                JSONObject c=store.newChat(hostId,row.optString("cwd",store.host(hostId).optString("cwd")));
                store.put(c,"remoteId",row.optString("id"));store.put(c,"title",row.optString("name",row.optString("preview","远程聊天")));store.put(c,"updatedAt",row.optLong("updatedAt")*1000);store.put(c,"needsHistory",true);count++;
            }
            cursor=r.isNull("nextCursor")?null:r.optString("nextCursor",null);
        }while(cursor!=null&&!cursor.isEmpty());
        owner.event(Json.obj("type","toast","message","已导入 "+count+" 个远程会话"));
    }
    synchronized void loadHistory(String chatId)throws Exception{
        connect();JSONObject c=store.chat(chatId);if(c==null||c.optString("remoteId").isEmpty())return;
        if(!busyChat.isEmpty())throw new IOException("请等待当前任务结束后刷新远程记录");
        JSONArray items=new JSONArray();String cursor=null;
        try{
            do{
                JSONObject p=Json.obj("threadId",c.optString("remoteId"),"limit",100,"sortDirection","asc");if(cursor!=null)p.put("cursor",cursor);
                JSONObject r=rpc.request("thread/items/list",p);JSONArray rows=r.optJSONArray("data");
                if(rows!=null)for(int i=0;i<rows.length();i++)items.put(rows.getJSONObject(i).getJSONObject("item"));
                cursor=r.isNull("nextCursor")?null:r.optString("nextCursor",null);
            }while(cursor!=null&&!cursor.isEmpty());
        }catch(Exception e){
            if(!e.getMessage().contains("method")&&!e.getMessage().contains("supported"))throw e;
            JSONObject r=rpc.request("thread/read",Json.obj("threadId",c.optString("remoteId"),"includeTurns",true));
            JSONArray turns=r.getJSONObject("thread").optJSONArray("turns");if(turns!=null)for(int i=0;i<turns.length();i++){JSONArray a=turns.getJSONObject(i).optJSONArray("items");if(a!=null)for(int j=0;j<a.length();j++)items.put(a.getJSONObject(j));}
        }
        store.put(c,"messages",new JSONArray());for(int i=0;i<items.length();i++)recordItem(c,items.getJSONObject(i),true);store.put(c,"needsHistory",false);
    }
    private void fail(Exception e){String id=busyChat;if(!id.isEmpty()){JSONObject c=store.chat(id);if(c!=null){store.put(c,"status","interrupted");store.message(c,"lost-"+UUID.randomUUID(),"system","error",errorMessage(e)+"\n重新连接后可继续此会话；请先核对服务器上的执行结果。");}}else owner.event(Json.obj("type","toast","message",errorMessage(e)));connection.failCurrent(errorMessage(e));owner.update();}
    void close(){
        connection.cancel();
        ScheduledFuture<?> timer=watchdog;if(timer!=null)timer.cancel(false);
        cleanupTransport();owner.update();
    }
    private void cleanupTransport(){
        owner.trustPrompts.remove(hostId);
        closing=true;CompletableFuture<Boolean> f=trust;if(f!=null)f.complete(false);trust=null;
        Rpc r=rpc;rpc=null;if(r!=null)r.close();if(channel!=null)channel.disconnect();if(ssh!=null)ssh.disconnect();channel=null;ssh=null;loaded.clear();
        String id=busyChat;busyChat="";turnId="";resolveAll(null);
        if(!id.isEmpty()){JSONObject c=store.chat(id);if(c!=null&&c.optString("status").equals("working"))store.put(c,"status","interrupted");}
    }
}
