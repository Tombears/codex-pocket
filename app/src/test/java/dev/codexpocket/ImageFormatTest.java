package dev.codexpocket;

import org.junit.Test;
import java.io.IOException;
import static org.junit.Assert.*;

public class ImageFormatTest {
    @Test public void formatUsesImageBytesInsteadOfTheServerFileName()throws Exception{
        assertEquals("image/png",ImageFormat.mime(new byte[]{(byte)137,80,78,71,13,10,26,10}));
        assertEquals("image/jpeg",ImageFormat.mime(new byte[]{(byte)255,(byte)216,(byte)255}));
        assertEquals("image/gif",ImageFormat.mime("GIF89a".getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
        assertEquals("image/webp",ImageFormat.mime("RIFF0000WEBP".getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
        assertThrows(IOException.class,()->ImageFormat.mime("<svg/>".getBytes()));
    }
    @Test public void exportedNameMatchesTheActualFormat(){
        assertEquals("dog.png",ImageFormat.fileName("dog.jpg","image/png"));assertEquals("dog.webp",ImageFormat.fileName("dog","image/webp"));
        assertFalse(ImageFormat.fileName("../../dog.png","image/png").contains("/"));assertEquals("图片.gif",ImageFormat.fileName("","image/gif"));
    }
}
