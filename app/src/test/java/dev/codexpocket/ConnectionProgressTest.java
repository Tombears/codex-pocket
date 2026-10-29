package dev.codexpocket;

import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.Assert.*;

public class ConnectionProgressTest {
    @Test public void stalledSshStopsConnectingAndCannotLaterBecomeOnline(){
        AtomicLong time=new AtomicLong();ConnectionProgress p=new ConnectionProgress(time::get);long id=p.begin();
        p.stage(id,"连接 SSH");time.set(30_000_000_000L);
        assertTrue(p.expire(id));assertEquals("error",p.snapshot().status());assertTrue(p.snapshot().error().contains("30秒"));assertFalse(p.online(id));
    }
    @Test public void changingPhasesDoesNotExtendTheThirtySecondDeadline(){
        AtomicLong time=new AtomicLong();ConnectionProgress p=new ConnectionProgress(time::get);long id=p.begin();
        p.stage(id,"连接 SSH");time.set(29_000_000_000L);p.stage(id,"等待确认服务器指纹");
        assertFalse(p.expire(id));time.set(30_000_000_000L);assertTrue(p.expire(id));assertTrue(p.snapshot().error().contains("等待确认服务器指纹"));
    }
    @Test public void oldCallbacksCannotReviveCancelledOrNewerConnections(){
        ConnectionProgress p=new ConnectionProgress(()->0L);long old=p.begin();p.cancel();long current=p.begin();
        assertFalse(p.online(old));assertFalse(p.fail(old,"旧连接失败"));assertFalse(p.expire(old));
        assertTrue(p.online(current));assertEquals("online",p.snapshot().status());assertEquals("",p.snapshot().error());
    }
    @Test public void successfulHandshakeCancelsDeadlineAndRetryClearsError(){
        AtomicLong time=new AtomicLong();ConnectionProgress p=new ConnectionProgress(time::get);long id=p.begin();p.fail(id,"握手失败");
        long retry=p.begin();assertEquals("",p.snapshot().error());assertTrue(p.online(retry));time.set(Long.MAX_VALUE/2);assertFalse(p.expire(retry));
    }
    @Test public void startupErrorKeepsSplitUtf8AndBoundsItsMemory(){
        StderrTail output=new StderrTail(32);byte[] bytes="目录不存在".getBytes(StandardCharsets.UTF_8);
        for(byte b:bytes)output.write(b);assertEquals("目录不存在",output.text());
        byte[] large="x".repeat(100).getBytes(StandardCharsets.UTF_8);output.write(large,0,large.length);assertEquals(32,output.text().length());output.clear();assertEquals("",output.text());
    }
}
