package dev.codexpocket;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

final class ImageFormat {
    static String mime(byte[] data)throws IOException{
        if(data.length>=8&&data[0]==(byte)137&&data[1]==80&&data[2]==78&&data[3]==71&&data[4]==13&&data[5]==10&&data[6]==26&&data[7]==10)return "image/png";
        if(data.length>=3&&data[0]==(byte)255&&data[1]==(byte)216&&data[2]==(byte)255)return "image/jpeg";
        if(data.length>=6){String magic=new String(data,0,6,StandardCharsets.US_ASCII);if(magic.equals("GIF87a")||magic.equals("GIF89a"))return "image/gif";}
        if(data.length>=12&&new String(data,0,4,StandardCharsets.US_ASCII).equals("RIFF")&&new String(data,8,4,StandardCharsets.US_ASCII).equals("WEBP"))return "image/webp";
        throw new IOException("图片格式不支持");
    }
    static String fileName(String name,String mime){
        String extension=switch(mime){case "image/png"->".png";case "image/jpeg"->".jpg";case "image/gif"->".gif";case "image/webp"->".webp";default->throw new IllegalArgumentException("图片格式不支持");};
        String base=name==null?"图片":name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]","_").replaceFirst("(?i)\\.(png|jpe?g|gif|webp)$","");
        if(base.isBlank())base="图片";if(base.length()>100)base=base.substring(0,100);return base+extension;
    }
}
