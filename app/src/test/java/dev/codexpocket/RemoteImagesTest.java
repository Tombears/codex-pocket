package dev.codexpocket;

import org.json.*;
import org.junit.Test;
import java.io.*;
import java.util.*;
import static org.junit.Assert.*;

public class RemoteImagesTest {
    @Test public void markdownPathsResolveAgainstTheRemoteWorkspace()throws Exception{
        JSONArray images=RemoteImages.references(Json.obj("type","agentMessage","text","![猫](sandbox:/mnt/data/cat.png)\n[查看](./out/../cat.webp)\n![空格](<images/my cat.jpg>)\n![重复](sandbox:/mnt/data/cat.png)"),"/srv/project");
        assertEquals(3,images.length());assertEquals("/mnt/data/cat.png",images.getJSONObject(0).getString("path"));
        assertEquals("/srv/project/cat.webp",images.getJSONObject(1).getString("path"));assertEquals("/srv/project/images/my cat.jpg",images.getJSONObject(2).getString("path"));
    }
    @Test public void arbitraryUrlsAndNonRasterFilesAreNotRemoteDownloads(){
        for(String source:List.of("https://example.com/a.png","file://other-host/a.png","javascript:alert(1)","/home/key.txt","/tmp/a.svg","/tmp/a.png?key=x","/tmp/a%00.png"))assertNull(source,RemoteImages.path(source,"/srv"));
        assertNull(RemoteImages.path("relative.png",""));assertEquals("/tmp/a.png",RemoteImages.path("file:///tmp/a.png",""));
    }
    @Test public void nativeGeneratedImagesPreferTheirSavedRemotePath()throws Exception{
        JSONObject generated=Json.obj("type","imageGeneration","savedPath","/root/.codex/generated_images/cat.png","result","not-a-download-url","status","completed");
        assertEquals("/root/.codex/generated_images/cat.png",RemoteImages.references(generated,"/root").getJSONObject(0).getString("path"));assertTrue(RemoteImages.inline(generated).isEmpty());
        assertEquals("/tmp/view.png",RemoteImages.references(Json.obj("type","imageView","path","/tmp/view.png"),"/root").getJSONObject(0).getString("path"));
    }
    @Test public void nativeAndMcpInlineImagesAreDecodedWithoutNetworkRequests()throws Exception{
        byte[] expected={1,2,3,4};String encoded=Base64.getEncoder().encodeToString(expected);
        assertArrayEquals(expected,RemoteImages.decodeInline(RemoteImages.inline(Json.obj("type","imageGeneration","result",encoded)).get(0)));
        JSONObject generated=Json.obj("type","imageGeneration","savedPath","/tmp/generated.png","result",encoded);
        assertEquals(1,RemoteImages.references(generated,"/root").length());assertTrue(RemoteImages.references(generated,"/root").getJSONObject(0).getBoolean("inline"));assertEquals(encoded,RemoteImages.inline(generated).get(0));
        JSONObject mcp=Json.obj("type","mcpToolCall","result",Json.obj("content",new JSONArray().put(Json.obj("type","image","mimeType","image/png","data",encoded)).put(Json.obj("type","image","mimeType","image/svg+xml","data",encoded))));
        assertEquals(1,RemoteImages.inline(mcp).size());assertArrayEquals(expected,RemoteImages.decodeInline("data:image/png;base64,"+encoded));
        assertThrows(IOException.class,()->RemoteImages.decodeInline("data:image/svg+xml;base64,"+encoded));assertThrows(IOException.class,()->RemoteImages.decodeInline("https://example.com/image.png"));
    }
    @Test public void eachItemHasABoundedNumberOfImages(){
        StringBuilder text=new StringBuilder();for(int i=0;i<12;i++)text.append("![x](/tmp/").append(i).append(".png)\n");
        assertEquals(4,RemoteImages.references(Json.obj("type","agentMessage","text",text.toString()),"/root").length());
    }
    @Test public void remoteReadsRejectFilesThatGrowPastTheSizeLimit()throws Exception{
        InputStream growing=new InputStream(){long left=RemoteImages.MAX_BYTES+1L;public int read(){return left-->0?0:-1;}public int read(byte[] b,int off,int len){if(left<=0)return -1;int n=(int)Math.min(left,len);left-=n;return n;}};
        assertThrows(IOException.class,()->RemoteImages.readBounded(growing));assertArrayEquals(new byte[]{1,2},RemoteImages.readBounded(new ByteArrayInputStream(new byte[]{1,2})));
    }
}
