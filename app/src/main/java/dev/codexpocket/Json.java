package dev.codexpocket;

import org.json.*;

final class Json {
    static JSONObject obj(Object... pairs) {
        JSONObject o=new JSONObject();
        try { for(int i=0;i<pairs.length;i+=2) o.put((String)pairs[i],pairs[i+1]==null?JSONObject.NULL:pairs[i+1]); }
        catch(JSONException e){throw new IllegalArgumentException(e);}
        return o;
    }
    static JSONObject copy(JSONObject o){ try{return new JSONObject(o.toString());}catch(Exception e){throw new IllegalArgumentException(e);} }
    static String quoteShell(String s){return "'"+s.replace("'","'\"'\"'")+"'";}
    static String command(String cwd,String executable){
        // Only executable and path are accepted, never arbitrary startup shell fragments.
        if(!cwd.startsWith("/") || cwd.indexOf('\0')>=0) throw new IllegalArgumentException("工作目录必须是 Linux 绝对路径");
        if(!executable.matches("[A-Za-z0-9_./+@-]+")) throw new IllegalArgumentException("Codex 路径不能包含参数或 shell 语法");
        return "bash -lc "+quoteShell("export PATH=\"$HOME/.local/bin:$HOME/.npm-global/bin:/usr/local/bin:$PATH\"; cd -- "+quoteShell(cwd)+" && exec "+quoteShell(executable)+" app-server");
    }
}
