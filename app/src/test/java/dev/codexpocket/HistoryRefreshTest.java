package dev.codexpocket;

import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class HistoryRefreshTest {
    @Test public void reconnectInvalidatesOnlyRemoteChatsOnThatHost()throws Exception{
        JSONObject remote=Json.obj("id","a","hostId","h1","remoteId","r1"),local=Json.obj("id","b","hostId","h1"),other=Json.obj("id","c","hostId","h2","remoteId","r2");
        JSONArray chats=new JSONArray().put(remote).put(local).put(other);
        assertTrue(HistoryRefresh.invalidate(chats,"h1"));assertTrue(remote.getBoolean("needsHistory"));assertEquals(1,remote.getLong("historyEpoch"));
        assertFalse(local.has("needsHistory"));assertFalse(other.has("needsHistory"));
        remote.put("needsHistory",false);HistoryRefresh.invalidate(chats,"h1");assertTrue(remote.getBoolean("needsHistory"));assertEquals(2,remote.getLong("historyEpoch"));
    }
    @Test public void unknownHostDoesNotInvalidateAnyChat()throws Exception{
        assertFalse(HistoryRefresh.invalidate(new JSONArray().put(Json.obj("hostId","h1","remoteId","r1")),"missing"));
    }
    @Test public void latestRemoteItemsReplaceStaleMessagesButRetainUploads()throws Exception{
        JSONArray previous=new JSONArray().put(Json.obj("id","answer","text","stale")).put(Json.obj("id","upload","kind","file","text","uploaded"));
        JSONArray latest=new JSONArray().put(Json.obj("id","answer","text","latest"));
        HistoryRefresh.keepUploads(previous,latest);HistoryRefresh.keepUploads(previous,latest);
        assertEquals(2,latest.length());assertEquals("latest",latest.getJSONObject(0).getString("text"));assertEquals("upload",latest.getJSONObject(1).getString("id"));
    }
}
