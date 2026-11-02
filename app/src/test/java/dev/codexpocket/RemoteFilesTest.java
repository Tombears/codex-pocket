package dev.codexpocket;

import java.io.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class RemoteFilesTest {
    @Test public void resolvesLinksAgainstChatDirectory()throws Exception{
        assertEquals("/srv/out/a b+中.pdf",RemoteFiles.path("../out/a%20b+%E4%B8%AD.pdf#L12","/srv/project"));
        assertEquals("/tmp/report.pdf",RemoteFiles.path("sandbox:/tmp/report.pdf","/srv"));
        assertEquals("/tmp/report.pdf",RemoteFiles.path("file:///tmp/report.pdf","/srv"));
        assertEquals("/tmp/a#b",RemoteFiles.path("/tmp/a%23b","/srv"));
    }
    @Test public void rejectsNonFileUrlsAndControlCharacters()throws Exception{
        for(String path:new String[]{"https://example.com/a","file://host/file","//host/file","javascript:alert(1)","/tmp/a%00b","/tmp/%zz",""}){
            try{RemoteFiles.path(path,"/srv");fail(path);}catch(IOException expected){}
        }
    }
    @Test public void uploadNamesCannotEscapeDestination(){
        assertEquals(".._.._file",RemoteFiles.name("../../file"));assertEquals("file",RemoteFiles.name(".."));
    }
    @Test public void streamsBinaryWithoutChangingBytes()throws Exception{
        byte[] data=new byte[200000];new java.util.Random(8).nextBytes(data);ByteArrayOutputStream out=new ByteArrayOutputStream();RemoteFiles.copy(new ByteArrayInputStream(data),out);assertArrayEquals(data,out.toByteArray());
    }
    @Test public void rejectsOversizedStreamsWithoutBufferingWholeFile()throws Exception{
        InputStream input=new InputStream(){long left=RemoteFiles.MAX_BYTES+1;public int read(){return left-->0?0:-1;}public int read(byte[] b,int off,int len){if(left==0)return -1;int n=(int)Math.min(left,len);left-=n;return n;}};
        try{RemoteFiles.copy(input,OutputStream.nullOutputStream());fail();}catch(IOException expected){assertTrue(expected.getMessage().contains("256 MB"));}
    }
}
