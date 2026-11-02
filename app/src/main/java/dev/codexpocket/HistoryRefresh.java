package dev.codexpocket;

import org.json.*;

final class HistoryRefresh {
    static boolean invalidate(JSONArray chats,String hostId)throws JSONException{
        boolean changed=false;
        for(int i=0;i<chats.length();i++){
            JSONObject c=chats.getJSONObject(i);
            if(!hostId.equals(c.optString("hostId"))||c.optString("remoteId").isEmpty())continue;
            c.put("needsHistory",true);c.put("historyEpoch",c.optLong("historyEpoch")+1);changed=true;
        }
        return changed;
    }
    static void keepUploads(JSONArray previous,JSONArray latest)throws JSONException{
        for(int i=0;i<previous.length();i++){
            JSONObject m=previous.getJSONObject(i);
            if(m.optString("kind").equals("file")&&Store.find(latest,m.optString("id"))==null)latest.put(m);
        }
    }
}
