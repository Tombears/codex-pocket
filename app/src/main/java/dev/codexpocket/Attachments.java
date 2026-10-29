package dev.codexpocket;

import android.content.*;
import android.graphics.*;
import android.net.Uri;
import android.os.Build;
import android.security.keystore.*;
import android.util.Base64;
import org.json.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.security.KeyStore;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;

/** Picker grants only; encrypted private cache, bounded decoded images, no original EXIF. */
final class Attachments {
    static final int MAX_IMAGES=4;
    private final Context context;
    private final File dir;
    private final javax.crypto.SecretKey key;
    Attachments(Context c)throws Exception{
        context=c;dir=new File(c.getCacheDir(),"attachments");if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("无法准备图片缓存");
        KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);String alias="codexpocket-images-v1";
        if(!ks.containsAlias(alias)){KeyGenerator g=KeyGenerator.getInstance("AES","AndroidKeyStore");g.init(new KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());g.generateKey();}
        key=(javax.crypto.SecretKey)ks.getKey(alias,null);prune();
    }
    synchronized void prune(){File[] files=dir.listFiles();if(files!=null)for(File f:files)if(f.lastModified()<System.currentTimeMillis()-86_400_000L)f.delete();}
    private File file(String id)throws IOException{if(!id.matches("[a-f0-9-]{36}"))throw new IOException("图片标识无效");return new File(dir,id+".bin");}
    synchronized void remove(String id){try{file(id).delete();}catch(IOException ignored){}}
    synchronized JSONObject add(Uri uri)throws Exception{
        prune();byte[] source;try(InputStream in=context.getContentResolver().openInputStream(uri)){if(in==null)throw new IOException("无法读取图片");ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1){if(out.size()+n>32*1024*1024)throw new IOException("原图超过 32 MB，请选择较小的图片");out.write(buf,0,n);}source=out.toByteArray();}
        return add(source,false);
    }
    synchronized JSONObject add(byte[] source)throws Exception{
        return add(source,true);
    }
    private JSONObject add(byte[] source,boolean preserveAlpha)throws Exception{
        prune();if(source.length>RemoteImages.MAX_BYTES)throw new IOException("图片超过 32 MB");
        Bitmap image;
        if(Build.VERSION.SDK_INT>=28){image=ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(source)),(decoder,info,src)->{int w=info.getSize().getWidth(),h=info.getSize().getHeight();double scale=Math.min(1,2048d/Math.max(w,h));decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);decoder.setTargetSize(Math.max(1,(int)(w*scale)),Math.max(1,(int)(h*scale)));});}
        else{BitmapFactory.Options o=new BitmapFactory.Options();o.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(source,0,source.length,o);if(o.outWidth<=0||o.outHeight<=0)throw new IOException("不支持或损坏的图片");o.inSampleSize=1;while(Math.max(o.outWidth,o.outHeight)/o.inSampleSize>2048)o.inSampleSize*=2;o.inJustDecodeBounds=false;image=BitmapFactory.decodeByteArray(source,0,source.length,o);
            if(image!=null){int orientation=new android.media.ExifInterface(new ByteArrayInputStream(source)).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION,1);Matrix m=new Matrix();switch(orientation){case 2:m.setScale(-1,1);break;case 3:m.setRotate(180);break;case 4:m.setScale(1,-1);break;case 5:m.setRotate(90);m.postScale(-1,1);break;case 6:m.setRotate(90);break;case 7:m.setRotate(270);m.postScale(-1,1);break;case 8:m.setRotate(270);break;}Bitmap rotated=Bitmap.createBitmap(image,0,0,image.getWidth(),image.getHeight(),m,true);if(rotated!=image){image.recycle();image=rotated;}}}
        if(image==null)throw new IOException("不支持或损坏的图片");
        if(image.hasAlpha()&&!preserveAlpha){Bitmap opaque=Bitmap.createBitmap(image.getWidth(),image.getHeight(),Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(opaque);canvas.drawColor(Color.WHITE);canvas.drawBitmap(image,0,0,null);image.recycle();image=opaque;}
        try{
            ByteArrayOutputStream out=new ByteArrayOutputStream();Bitmap.CompressFormat format=image.hasAlpha()?Bitmap.CompressFormat.PNG:Bitmap.CompressFormat.JPEG;
            image.compress(format,85,out);
            if(out.size()>2*1024*1024&&format==Bitmap.CompressFormat.JPEG){out.reset();image.compress(format,60,out);}
            while(out.size()>2*1024*1024&&preserveAlpha&&Math.max(image.getWidth(),image.getHeight())>256){
                Bitmap smaller=Bitmap.createScaledBitmap(image,Math.max(1,image.getWidth()/2),Math.max(1,image.getHeight()/2),true);image.recycle();image=smaller;out.reset();image.compress(format,85,out);
            }
            if(out.size()>2*1024*1024)throw new IOException("图片压缩后仍超过 2 MB");String id=UUID.randomUUID().toString();Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key);try(FileOutputStream f=new FileOutputStream(file(id))){f.write(cipher.getIV());f.write(cipher.doFinal(out.toByteArray()));}return preview(id,image);
        }finally{image.recycle();}
    }
    private JSONObject preview(String id,Bitmap image){int w=image.getWidth(),h=image.getHeight();double scale=Math.min(1,180d/Math.max(w,h));Bitmap small=Bitmap.createScaledBitmap(image,Math.max(1,(int)(w*scale)),Math.max(1,(int)(h*scale)),true);ByteArrayOutputStream out=new ByteArrayOutputStream();small.compress(image.hasAlpha()?Bitmap.CompressFormat.PNG:Bitmap.CompressFormat.JPEG,65,out);if(small!=image)small.recycle();return Json.obj("id",id,"name","图片.jpg","preview","data:image/"+(image.hasAlpha()?"png":"jpeg")+";base64,"+Base64.encodeToString(out.toByteArray(),Base64.NO_WRAP),"width",w,"height",h);}
    record Batch(JSONArray input,JSONArray previews){}
    synchronized String fullPreview(String id)throws Exception{
        File f=file(id);if(!f.exists()||f.lastModified()<System.currentTimeMillis()-86_400_000L)throw new IOException("图片缓存已过期，请重新加载");
        if(f.length()>3*1024*1024)throw new IOException("图片缓存过大");
        byte[] raw=Files.readAllBytes(f.toPath());if(raw.length<28)throw new IOException("图片缓存损坏");
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,Arrays.copyOfRange(raw,0,12)));
        byte[] image=cipher.doFinal(Arrays.copyOfRange(raw,12,raw.length));
        return "data:image/"+(image.length>4&&(image[0]&255)==137&&image[1]==80?"png":"jpeg")+";base64,"+Base64.encodeToString(image,Base64.NO_WRAP);
    }
    synchronized Batch prepare(JSONArray refs)throws Exception{
        if(refs==null)return new Batch(new JSONArray(),new JSONArray());if(refs.length()>MAX_IMAGES)throw new IOException("每条消息最多 4 张图片");JSONArray input=new JSONArray(),previews=new JSONArray();Set<String> seen=new HashSet<>();
        for(int i=0;i<refs.length();i++){String id=refs.getJSONObject(i).getString("id");if(!seen.add(id))continue;File f=file(id);if(!f.exists()||f.lastModified()<System.currentTimeMillis()-86_400_000L)throw new IOException("图片已过期，请重新选择");if(f.length()>3*1024*1024)throw new IOException("图片缓存过大");byte[] raw=Files.readAllBytes(f.toPath());Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,Arrays.copyOfRange(raw,0,12)));byte[] jpeg=cipher.doFinal(Arrays.copyOfRange(raw,12,raw.length));input.put(Json.obj("type","image","url","data:image/jpeg;base64,"+Base64.encodeToString(jpeg,Base64.NO_WRAP)));Bitmap image=BitmapFactory.decodeByteArray(jpeg,0,jpeg.length);if(image==null)throw new IOException("图片缓存损坏");try{previews.put(preview(id,image));}finally{image.recycle();}}
        return new Batch(input,previews);
    }
}
