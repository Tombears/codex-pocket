package dev.codexpocket;

import java.net.IDN;
import java.net.InetAddress;

final class SshAddress {
    static String normalize(String value){
        String host=value==null?"":value.trim();
        if(host.startsWith("[")&&host.endsWith("]"))host=host.substring(1,host.length()-1);
        if(host.isEmpty())throw new IllegalArgumentException("请填写服务器 IP 或域名");
        if(host.matches("[0-9.]+")){
            String[] parts=host.split("\\.",-1);if(parts.length!=4)throw invalidIp();
            StringBuilder result=new StringBuilder();
            for(String p:parts){if(p.isEmpty()||p.length()>3||Integer.parseInt(p)>255)throw invalidIp();if(result.length()>0)result.append('.');result.append(Integer.parseInt(p));}
            return result.toString();
        }
        if(host.contains(":")){
            try{if(InetAddress.getByName(host).getAddress().length==16)return host;}catch(Exception ignored){}
            throw new IllegalArgumentException("IPv6 地址格式不正确；SSH 端口请单独填写");
        }
        if(host.contains("/")||host.contains("@")||host.chars().anyMatch(Character::isWhitespace))throw new IllegalArgumentException("这里只填写 IP 或域名，不要包含协议、用户名或路径");
        try{host=IDN.toASCII(host);}catch(Exception e){throw new IllegalArgumentException("服务器域名格式不正确");}
        if(host.length()>253)throw new IllegalArgumentException("服务器域名过长");
        for(String part:host.split("\\.",-1))if(!part.matches("[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?"))throw new IllegalArgumentException("服务器域名格式不正确");
        return host;
    }
    private static IllegalArgumentException invalidIp(){return new IllegalArgumentException("IPv4 地址需要四段数字，每段为 0–255，请检查是否漏写了点");}
}
