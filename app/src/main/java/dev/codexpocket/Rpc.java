package dev.codexpocket;

import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** JSON-lines app-server client. The reader must never wait for a UI response. */
final class Rpc implements AutoCloseable {
    private final BufferedWriter writer;
    private final ConcurrentHashMap<String,CompletableFuture<JSONObject>> pending=new ConcurrentHashMap<>();
    private final AtomicLong seq=new AtomicLong();
    private volatile boolean closed;
    Rpc(InputStream in,OutputStream out,Consumer<JSONObject> events,Consumer<Exception> failure){
        writer=new BufferedWriter(new OutputStreamWriter(out,StandardCharsets.UTF_8));
        Thread reader=new Thread(()->{
            try(BufferedReader r=new BufferedReader(new InputStreamReader(in,StandardCharsets.UTF_8))){
                String line;
                while((line=r.readLine())!=null){
                    if(line.isBlank())continue;
                    JSONObject msg=new JSONObject(line);
                    if(msg.has("method"))events.accept(msg);
                    else if(msg.has("id")){
                        CompletableFuture<JSONObject> f=pending.remove(msg.get("id").toString());
                        if(f!=null){if(msg.has("error"))f.completeExceptionally(new IOException(msg.getJSONObject("error").optString("message")));else f.complete(msg.optJSONObject("result"));}
                    }
                }
                if(!closed)throw new EOFException("SSH / Codex 连接已断开");
            }catch(Exception e){if(!closed){close();failure.accept(e);}}
        },"codex-rpc-reader"); reader.setDaemon(true);reader.start();
    }
    JSONObject request(String method,JSONObject params)throws Exception{
        return request(method,params,60);
    }
    JSONObject request(String method,JSONObject params,long timeoutSeconds)throws Exception{
        long id=seq.incrementAndGet();CompletableFuture<JSONObject> f=new CompletableFuture<>();
        if(closed)throw new IOException("连接已关闭");pending.put(Long.toString(id),f);
        try{send(Json.obj("id",id,"method",method,"params",params));return f.get(timeoutSeconds,TimeUnit.SECONDS);}
        catch(ExecutionException e){throw new IOException(e.getCause().getMessage(),e.getCause());}
        catch(TimeoutException e){throw new IOException("Codex 响应超时："+method,e);}
        finally{pending.remove(Long.toString(id));}
    }
    synchronized void send(JSONObject msg)throws IOException{
        if(closed)throw new IOException("连接已关闭");writer.write(msg.toString());writer.newLine();writer.flush();
    }
    public void close(){
        closed=true;pending.forEach((id,f)->f.completeExceptionally(new IOException("连接已关闭")));pending.clear();
        // The owner closes the SSH channel; closing a locked reader/writer here could block.
    }
}
