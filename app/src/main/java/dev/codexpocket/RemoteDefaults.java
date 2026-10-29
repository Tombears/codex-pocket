package dev.codexpocket;

import org.json.*;
import java.util.HashSet;
import java.util.Set;

/** Migrate only the old template defaults; keep explicit project paths and resumed threads. */
final class RemoteDefaults {
    static boolean migrate(JSONObject data)throws JSONException{
        boolean changed=false;Set<String> migrated=new HashSet<>();
        JSONArray hosts=data.optJSONArray("hosts"),chats=data.optJSONArray("chats");
        if(hosts==null)return false;
        for(int i=0;i<hosts.length();i++){
            JSONObject h=hosts.getJSONObject(i);if(h.optInt("pathDefaultsVersion")>=1)continue;
            if(h.optString("cwd").equals("/home/ubuntu")){h.put("cwd","");migrated.add(h.optString("id"));}
            if(h.optString("codex").equals("codex"))h.put("codex","");
            h.put("pathDefaultsVersion",1);changed=true;
        }
        if(chats!=null)for(int i=0;i<chats.length();i++){
            JSONObject c=chats.getJSONObject(i);
            if(migrated.contains(c.optString("hostId"))&&c.optString("remoteId").isEmpty()&&c.optString("cwd").equals("/home/ubuntu"))c.put("cwd","");
        }
        return changed;
    }
}
