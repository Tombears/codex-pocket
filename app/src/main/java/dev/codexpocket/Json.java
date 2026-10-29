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
        if((!cwd.isEmpty()&&!cwd.startsWith("/")) || cwd.indexOf('\0')>=0) throw new IllegalArgumentException("工作目录须留空自动识别，或填写 Linux 绝对路径");
        if(!executable.isEmpty()&&!executable.matches("[A-Za-z0-9_./+@-]+")) throw new IllegalArgumentException("Codex 路径不能包含参数或 shell 语法");
        String directory=cwd.isEmpty()?"\"$HOME\"":quoteShell(cwd);
        String script="export PATH=\"$HOME/.local/bin:$HOME/.npm-global/bin:$HOME/.volta/bin:$HOME/.bun/bin:$HOME/.asdf/shims:/usr/local/bin:$PATH\"; "+
                "if ! cd -- "+directory+"; then printf '%s\\n' "+quoteShell("工作目录不存在或无权限："+(cwd.isEmpty()?"用户主目录":cwd))+" >&2; exit 72; fi; ";
        if(executable.isEmpty()||executable.equals("codex")){
            script+="cp_bin=$(type -P codex 2>/dev/null || true); "+
                    "if [ -z \"$cp_bin\" ]; then for cp_candidate in \"$HOME\"/.nvm/versions/node/*/bin/codex; do if [ -x \"$cp_candidate\" ]; then cp_bin=\"$cp_candidate\"; fi; done; fi; ";
        }else script+="cp_bin="+quoteShell(executable)+"; ";
        script+="if [ -z \"$cp_bin\" ] || [ ! -x \"$cp_bin\" ]; then printf '%s\\n' '未找到 Codex，请先在服务器安装，或在高级设置指定可执行文件路径' >&2; exit 127; fi; "+
                "export PATH=\"$(dirname -- \"$cp_bin\"):$PATH\"; printf '__CP_CWD__=%s\\n__CP_BIN__=%s\\n' \"$PWD\" \"$cp_bin\" >&2; exec \"$cp_bin\" app-server";
        return "bash -lc "+quoteShell(script);
    }
}
