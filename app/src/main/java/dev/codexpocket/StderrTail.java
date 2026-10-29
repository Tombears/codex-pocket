package dev.codexpocket;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Keep remote startup errors bounded without corrupting multi-byte UTF-8 text. */
final class StderrTail extends OutputStream {
    private final int limit;
    private byte[] bytes=new byte[0];
    StderrTail(int limit){this.limit=limit;}
    @Override public synchronized void write(int b){write(new byte[]{(byte)b},0,1);}
    @Override public synchronized void write(byte[] data,int offset,int length){
        int keep=Math.min(bytes.length,Math.max(0,limit-length));
        int take=Math.min(length,limit);
        byte[] next=new byte[keep+take];
        System.arraycopy(bytes,bytes.length-keep,next,0,keep);
        System.arraycopy(data,offset+length-take,next,keep,take);bytes=next;
    }
    synchronized void clear(){Arrays.fill(bytes,(byte)0);bytes=new byte[0];}
    synchronized String text(){return new String(bytes,StandardCharsets.UTF_8);}
}
