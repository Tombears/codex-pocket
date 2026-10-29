package dev.codexpocket;

import org.junit.*;
import org.json.*;
import java.io.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

public class RpcTest {
    private PipedInputStream input;
    private PipedOutputStream server;
    private ByteArrayOutputStream sent;
    private Rpc rpc;
    private BlockingQueue<JSONObject> events;
    @Before public void setup()throws Exception{
        input=new PipedInputStream();server=new PipedOutputStream(input);sent=new ByteArrayOutputStream();events=new LinkedBlockingQueue<>();
        rpc=new Rpc(input,sent,events::add,e->{});
    }
    @After public void cleanup()throws Exception{rpc.close();server.close();input.close();}
    private void reply(String s)throws Exception{server.write((s+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));server.flush();}
    private JSONObject outbound()throws Exception{long end=System.currentTimeMillis()+2000;while(!sent.toString("UTF-8").contains("\n")&&System.currentTimeMillis()<end)Thread.sleep(5);synchronized(sent){return new JSONObject(sent.toString("UTF-8").split("\n")[0]);}}
    @Test public void responseIdsAreMatchedAndUtf8NotificationsRemainIndependent()throws Exception{
        CompletableFuture<JSONObject> result=CompletableFuture.supplyAsync(()->{try{return rpc.request("thread/start",Json.obj("cwd","/tmp"));}catch(Exception e){throw new CompletionException(e);}});
        JSONObject request=outbound();assertEquals("thread/start",request.getString("method"));
        reply("{\"method\":\"item/agentMessage/delta\",\"params\":{\"delta\":\"你好\"}}");
        assertEquals("你好",events.poll(2,TimeUnit.SECONDS).getJSONObject("params").getString("delta"));
        reply(Json.obj("id",request.get("id"),"result",Json.obj("thread",Json.obj("id","t-1"))).toString());
        assertEquals("t-1",result.get(2,TimeUnit.SECONDS).getJSONObject("thread").getString("id"));
    }
    @Test public void serverApprovalIsDeliveredWithoutBlockingReader()throws Exception{
        reply("{\"id\":\"approve-1\",\"method\":\"item/commandExecution/requestApproval\",\"params\":{\"threadId\":\"t\"}}");
        reply("{\"method\":\"turn/started\",\"params\":{}}");
        assertEquals("approve-1",events.poll(2,TimeUnit.SECONDS).getString("id"));
        assertEquals("turn/started",events.poll(2,TimeUnit.SECONDS).getString("method"));
    }
    @Test public void remoteErrorCompletesPendingRequest()throws Exception{
        CompletableFuture<String> result=CompletableFuture.supplyAsync(()->{try{rpc.request("thread/resume",new JSONObject());return "unexpected";}catch(Exception e){return e.getMessage();}});
        JSONObject request=outbound();reply(Json.obj("id",request.get("id"),"error",Json.obj("code",-1,"message","session not found")).toString());
        assertEquals("session not found",result.get(2,TimeUnit.SECONDS));
    }
    @Test public void eofFailsPendingRequestImmediately()throws Exception{
        CompletableFuture<String> result=CompletableFuture.supplyAsync(()->{try{rpc.request("turn/start",new JSONObject());return "unexpected";}catch(Exception e){return e.getMessage();}});
        outbound();server.close();assertEquals("连接已关闭",result.get(2,TimeUnit.SECONDS));
    }
    @Test public void rejectedShellArgumentsCannotBecomeCommands(){
        assertThrows(IllegalArgumentException.class,()->Json.command("relative/path","codex"));
        assertThrows(IllegalArgumentException.class,()->Json.command("/tmp","codex; rm -rf /"));
        String command=Json.command("/tmp/a'$(touch /tmp/pwned)","/opt/codex");
        assertTrue(command.startsWith("bash -lc '"));assertTrue(command.contains("app-server"));assertEquals("'a'\"'\"'b'",Json.quoteShell("a'b"));
    }
}
