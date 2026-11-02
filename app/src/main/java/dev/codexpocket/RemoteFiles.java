package dev.codexpocket;

import java.io.*;
import java.net.URLDecoder;
import java.util.ArrayDeque;

final class RemoteFiles {
    static final long MAX_BYTES=256L*1024*1024;
    static String path(String link,String cwd)throws IOException{
        String raw=link;
        if(raw.startsWith("file:///"))raw=raw.substring(7);
        else if(raw.startsWith("sandbox:/"))raw=raw.substring(8);
        else if(raw.matches("(?s)^[a-zA-Z][a-zA-Z0-9+.-]*:.*")||raw.startsWith("//"))throw new IOException("仅支持服务器文件路径");
        raw=raw.split("[?#]",2)[0];
        try{raw=URLDecoder.decode(raw.replace("+","%2B"),"UTF-8");}catch(IllegalArgumentException e){throw new IOException("文件链接编码无效",e);}
        if(raw.isBlank()||raw.chars().anyMatch(c->c<32))throw new IOException("文件路径无效");
        if(!raw.startsWith("/")){if(cwd==null||!cwd.startsWith("/"))throw new IOException("无法确定聊天工作目录");raw=cwd+"/"+raw;}
        ArrayDeque<String> parts=new ArrayDeque<>();for(String p:raw.split("/")){if(p.equals("..")){if(!parts.isEmpty())parts.removeLast();}else if(!p.isEmpty()&&!p.equals("."))parts.add(p);}
        return "/"+String.join("/",parts);
    }
    static String name(String name){
        String clean=name.replaceAll("[\\\\/\\p{Cntrl}]","_");return clean.isBlank()||clean.equals(".")||clean.equals("..")?"file":clean;
    }
    static String literal(String path){return path.replace("\\","\\\\").replace("*","\\*").replace("?","\\?");}
    static void copy(InputStream in,OutputStream out)throws IOException{
        byte[] buffer=new byte[64*1024];long total=0;int n;
        while((n=in.read(buffer))!=-1){if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("文件传输已取消");total+=n;if(total>MAX_BYTES)throw new IOException("文件超过 256 MB");out.write(buffer,0,n);}
    }
}
