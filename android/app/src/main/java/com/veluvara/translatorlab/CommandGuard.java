package com.veluvara.translatorlab;
import java.util.LinkedHashMap;
final class CommandGuard {
    private final LinkedHashMap<String,Boolean> seen=new LinkedHashMap<>();
    private long last=Long.MIN_VALUE;
    synchronized boolean accept(String action,String id,long issued,long now) {
        if(!"self_test".equals(action) || id==null || !id.matches("[a-zA-Z0-9-]{8,64}") || issued<now-60 || issued>now+10 || seen.containsKey(id)) return false;
        if(last!=Long.MIN_VALUE && now-last<10) return false;
        last=now; seen.put(id,true);
        if(seen.size()>128) seen.remove(seen.keySet().iterator().next());
        return true;
    }
}
