package dev.codexpocket;

import java.util.*;

/** Backoff applies only to transport reconnection, never to user tasks. */
final class ReconnectPolicy {
    private final Map<String,Integer> failures=new HashMap<>();
    private final Map<String,Long> next=new HashMap<>();
    private final Set<String> running=new HashSet<>();
    synchronized boolean begin(String id,long now){if(running.contains(id)||now<next.getOrDefault(id,0L))return false;running.add(id);return true;}
    synchronized void succeeded(String id){running.remove(id);failures.remove(id);next.remove(id);}
    synchronized void failed(String id,long now){running.remove(id);int n=Math.min(7,failures.getOrDefault(id,0)+1);failures.put(id,n);next.put(id,now+Math.min(300_000L,5000L*(1L<<(n-1))));}
    synchronized void forget(String id){running.remove(id);failures.remove(id);next.remove(id);}
    synchronized void networkAvailable(){next.clear();}
}
