package dev.codexpocket;

import java.util.function.LongSupplier;

/** Cancelled or expired connection attempts cannot revive the UI. */
final class ConnectionProgress {
    record Snapshot(String status,String phase,String error) {}
    private final LongSupplier clock;
    private long generation,deadline;
    private String status="offline",phase="",error="";
    ConnectionProgress(){this(System::nanoTime);}
    ConnectionProgress(LongSupplier clock){this.clock=clock;}
    synchronized long begin(){generation++;status="connecting";error="";phase="准备 SSH";deadline=clock.getAsLong()+30_000_000_000L;return generation;}
    synchronized boolean stage(long id,String name){
        if(!active(id))return false;
        phase=name;return true;
    }
    synchronized boolean active(long id){return id==generation&&status.equals("connecting");}
    synchronized boolean current(long id){return id==generation;}
    synchronized boolean online(long id){if(!active(id))return false;status="online";phase="";return true;}
    synchronized boolean fail(long id,String message){
        if(id!=generation||(!status.equals("connecting")&&!status.equals("online")))return false;
        status="error";error=message;return true;
    }
    synchronized void failCurrent(String message){fail(generation,message);}
    synchronized boolean expire(long id){
        if(!active(id)||clock.getAsLong()<deadline)return false;
        status="error";error="连接超时（30秒），当前阶段："+phase+"。请检查手机网络、服务器地址和连接设置后重试";return true;
    }
    synchronized void cancel(){generation++;status="offline";phase="";error="";}
    synchronized Snapshot snapshot(){return new Snapshot(status,phase,error);}
}
