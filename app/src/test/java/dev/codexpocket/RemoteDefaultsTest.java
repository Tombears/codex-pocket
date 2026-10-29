package dev.codexpocket;

import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class RemoteDefaultsTest {
    @Test public void legacyDefaultsBecomeAutomaticWithoutRepointingRemoteThreads()throws Exception{
        JSONObject h=Json.obj("id","h","user","root","cwd","/home/ubuntu","codex","codex");
        JSONObject local=Json.obj("id","c","hostId","h","cwd","/home/ubuntu");
        JSONObject resumed=Json.obj("id","r","hostId","h","cwd","/home/ubuntu","remoteId","existing");
        JSONObject data=Json.obj("hosts",new JSONArray().put(h),"chats",new JSONArray().put(local).put(resumed));
        assertTrue(RemoteDefaults.migrate(data));assertEquals("",h.getString("cwd"));assertEquals("",h.getString("codex"));assertEquals("",local.getString("cwd"));assertEquals("/home/ubuntu",resumed.getString("cwd"));assertFalse(RemoteDefaults.migrate(data));
    }
    @Test public void explicitPathsAreKept()throws Exception{
        JSONObject h=Json.obj("id","h","cwd","/srv/project","codex","/opt/codex");
        RemoteDefaults.migrate(Json.obj("hosts",new JSONArray().put(h),"chats",new JSONArray()));
        assertEquals("/srv/project",h.getString("cwd"));assertEquals("/opt/codex",h.getString("codex"));
    }
    @Test public void automaticStartupUsesRemoteHomeAndSearchesCommonInstallations(){
        String script=Json.command("","");assertTrue(script.contains("$HOME"));assertTrue(script.contains("type -P codex"));assertTrue(script.contains(".nvm/versions/node"));assertFalse(script.contains("/home/ubuntu"));
    }
}
