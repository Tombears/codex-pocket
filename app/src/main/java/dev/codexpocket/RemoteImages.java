package dev.codexpocket;

import org.json.*;
import java.io.*;
import java.net.URI;
import java.util.*;
import java.util.regex.*;

/** Raster files explicitly referenced by an assistant item, never arbitrary web resources. */
final class RemoteImages {
    static final int MAX_BYTES=32*1024*1024;
    private static final Pattern LINK=Pattern.compile("!?\\[[^\\]\\r\\n]*\\]\\(\\s*(?:<([^>]+)>|([^\\s)]+))(?:\\s+\"[^\"]*\")?\\s*\\)");
    static String path(String reference,String cwd){
        if(reference==null||reference.length()>4096)return null;
        try{
            URI uri=new URI(reference.replace(" ","%20"));
            String scheme=uri.getScheme();
            if(scheme!=null&&!scheme.equals("sandbox")&&!scheme.equals("file"))return null;
            if(uri.getRawAuthority()!=null||uri.getRawQuery()!=null||uri.getRawFragment()!=null)return null;
            String path=uri.getPath();
            if(path==null||path.indexOf('\0')>=0||path.indexOf('\n')>=0||path.indexOf('\r')>=0)return null;
            if(!path.startsWith("/")){if(cwd==null||!cwd.startsWith("/"))return null;path=cwd+"/"+path;}
            path=new URI(null,null,path,null).normalize().getPath();
            if(!path.toLowerCase(Locale.ROOT).matches(".*\\.(png|jpe?g|webp|gif)$"))return null;
            return path;
        }catch(Exception ignored){return null;}
    }
    static JSONArray references(JSONObject item,String cwd){
        LinkedHashSet<String> paths=new LinkedHashSet<>();
        String type=item.optString("type");
        if(type.equals("agentMessage")){
            Matcher links=LINK.matcher(item.optString("text"));
            while(links.find()&&paths.size()<4){String p=path(links.group(1)!=null?links.group(1):links.group(2),cwd);if(p!=null)paths.add(p);}
        }else if(type.equals("imageView")||type.equals("imageGeneration")){
            String p=path(item.optString("path",item.optString("savedPath",item.optString("outputPath"))),cwd);if(p!=null)paths.add(p);
        }
        JSONArray refs=new JSONArray();for(String p:paths)refs.put(Json.obj("path",p,"name",p.substring(p.lastIndexOf('/')+1),"status","pending","inline",type.equals("imageGeneration")&&!generatedData(item).isEmpty()));return refs;
    }
    private static String generatedData(JSONObject item){String data=item.optString("result");return data.startsWith("data:image/")||!data.isEmpty()&&data.matches("[A-Za-z0-9+/=]+")?data:"";}
    static List<String> inline(JSONObject item){
        List<String> images=new ArrayList<>();String type=item.optString("type");
        if(type.equals("imageGeneration")&&!generatedData(item).isEmpty())images.add(generatedData(item));
        JSONObject result=item.optJSONObject("result");JSONArray content=result==null?item.optJSONArray("contentItems"):result.optJSONArray("content");
        if((type.equals("mcpToolCall")||type.equals("dynamicToolCall"))&&content!=null)for(int i=0;i<content.length()&&images.size()<4;i++){
            JSONObject part=content.optJSONObject(i);if(part==null)continue;
            if(part.optString("type").equals("image")&&part.optString("mimeType").matches("image/(png|jpeg|webp|gif)"))images.add(part.optString("data"));
            else if(part.optString("imageUrl").matches("(?s)^data:image/(png|jpeg|webp|gif);base64,.*"))images.add(part.optString("imageUrl"));
        }
        return images;
    }
    static byte[] decodeInline(String image)throws IOException{
        if(image.length()>(MAX_BYTES/3L+1)*4+100)throw new IOException("图片超过 32 MB");
        if(image.startsWith("data:")){
            if(!image.matches("(?s)^data:image/(png|jpeg|webp|gif);base64,.*"))throw new IOException("图片类型不支持");
            image=image.substring(image.indexOf(',')+1);
        }
        try{byte[] bytes=Base64.getDecoder().decode(image);if(bytes.length>MAX_BYTES)throw new IOException("图片超过 32 MB");return bytes;}
        catch(IllegalArgumentException e){throw new IOException("图片数据无效");}
    }
    static byte[] readBounded(InputStream input)throws IOException{
        ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int n;
        while((n=input.read(buffer))!=-1){if(out.size()+n>MAX_BYTES)throw new IOException("图片超过 32 MB");out.write(buffer,0,n);}
        return out.toByteArray();
    }
}
