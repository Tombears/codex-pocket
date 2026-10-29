package dev.codexpocket;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.*;
import android.webkit.*;
import android.view.*;
import java.io.*;
import org.json.*;

public final class MainActivity extends Activity {
    private static final String ORIGIN="https://app.codexpocket.local/";
    private WebView web;
    private CodexService service;
    private boolean pageReady,bound;
    private String imageDraftKey="";
    private String exportImageId="";
    private android.net.Uri pendingExportUri;
    private java.util.List<android.net.Uri> pendingImages;
    private final CodexService.Listener listener=this::deliver;
    private final ServiceConnection connection=new ServiceConnection(){
        public void onServiceConnected(ComponentName name,IBinder binder){service=((CodexService.LocalBinder)binder).service();service.listen(listener);if(pendingImages!=null){service.importImages(pendingImages,imageDraftKey);pendingImages=null;}if(pendingExportUri!=null){service.exportImage(pendingExportUri,exportImageId);pendingExportUri=null;exportImageId="";}if(pageReady)deliver(Json.obj("type","state","state",service.snapshot()));}
        public void onServiceDisconnected(ComponentName name){service=null;}
    };
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);
        if(saved!=null)imageDraftKey=saved.getString("imageDraftKey","");
        if(saved!=null){exportImageId=saved.getString("exportImageId","");String pending=saved.getString("pendingExportUri","");if(!pending.isEmpty())pendingExportUri=android.net.Uri.parse(pending);}
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},4);
        Intent intent=new Intent(this,CodexService.class);startForegroundService(intent);bound=bindService(intent,connection,BIND_AUTO_CREATE);
        web=new WebView(this);web.setBackgroundColor(0xff17181b);
        WebSettings s=web.getSettings();s.setJavaScriptEnabled(true);s.setDomStorageEnabled(false);s.setAllowFileAccess(false);s.setAllowContentAccess(false);s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);s.setSupportMultipleWindows(false);s.setJavaScriptCanOpenWindowsAutomatically(false);
        web.addJavascriptInterface(new Bridge(),"Android");
        web.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest r){return true;}
            @Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest r){
                String url=r.getUrl().toString();
                if(!url.startsWith(ORIGIN))return empty();
                String path=r.getUrl().getPath();if(path==null||path.contains(".."))return empty();
                if(path.startsWith("/images/")){
                    if(!path.matches("/images/[a-f0-9-]{36}/original")||service==null)return empty();
                    try{Attachments.Original original=service.originalImage(path.split("/")[2]);WebResourceResponse response=new WebResourceResponse(original.mime(),null,new ByteArrayInputStream(original.bytes()));response.setResponseHeaders(java.util.Map.of("Cache-Control","no-store","X-Content-Type-Options","nosniff"));return response;}catch(Exception|OutOfMemoryError e){return empty();}
                }
                if(path.equals("/"))path="/index.html";
                try{String mime=path.endsWith(".js")?"application/javascript":path.endsWith(".css")?"text/css":path.endsWith(".svg")?"image/svg+xml":"text/html";
                    WebResourceResponse res=new WebResourceResponse(mime,"UTF-8",getAssets().open("ui"+path));
                    res.setResponseHeaders(java.util.Map.of("Content-Security-Policy","default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'none'; frame-src 'none'; base-uri 'none'; form-action 'none'","X-Content-Type-Options","nosniff"));return res;
                }catch(IOException e){return empty();}
            }
            @Override public void onPageFinished(WebView v,String url){if(url.startsWith(ORIGIN)){pageReady=true;if(service!=null)deliver(Json.obj("type","state","state",service.snapshot()));}}
        });
        web.setWebChromeClient(new WebChromeClient());
        setContentView(web);web.loadUrl(ORIGIN);
    }
    private WebResourceResponse empty(){return new WebResourceResponse("text/plain","UTF-8",new ByteArrayInputStream(new byte[0]));}
    private void deliver(JSONObject event){runOnUiThread(()->{
        if(event.optString("type").equals("imageExportReady")){
            if(!exportImageId.isEmpty())return;
            exportImageId=event.optString("id");try{Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT);intent.addCategory(Intent.CATEGORY_OPENABLE);intent.setType(event.optString("mime"));intent.putExtra(Intent.EXTRA_TITLE,event.optString("name","图片.png"));startActivityForResult(intent,22);}catch(Exception e){exportImageId="";deliver(Json.obj("type","toast","message","无法打开保存位置选择器"));}return;
        }
        if(pageReady&&web!=null)web.evaluateJavascript("window.receive("+event.toString()+")",null);
    });}
    public final class Bridge {
        @JavascriptInterface public void action(String json){
            try{JSONObject a=new JSONObject(json);if(a.optString("type").equals("copy")){android.content.ClipboardManager c=(android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);c.setPrimaryClip(ClipData.newPlainText("Codex",a.optString("text")));return;}
                if(a.optString("type").equals("exportLogs")){runOnUiThread(()->{Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT);intent.addCategory(Intent.CATEGORY_OPENABLE);intent.setType("text/plain");intent.putExtra(Intent.EXTRA_TITLE,"CodexPocket-log-"+new java.text.SimpleDateFormat("yyyyMMdd-HHmmss",java.util.Locale.ROOT).format(new java.util.Date())+".txt");startActivityForResult(intent,20);});return;}
                if(a.optString("type").equals("pickImages")){imageDraftKey=a.optString("draftKey");runOnUiThread(()->{Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);intent.addCategory(Intent.CATEGORY_OPENABLE);intent.setType("image/*");intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true);startActivityForResult(intent,21);});return;}
                if(a.optString("type").equals("backgroundSettings")){runOnUiThread(()->{try{Intent i=new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,android.net.Uri.parse("package:"+getPackageName()));startActivity(i);}catch(Exception e){startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,android.net.Uri.parse("package:"+getPackageName())));}});return;}
                if(a.optString("type").equals("openLink")){android.net.Uri uri=android.net.Uri.parse(a.optString("url"));if(("https".equals(uri.getScheme())||"http".equals(uri.getScheme()))&&uri.getHost()!=null)runOnUiThread(()->{try{startActivity(new Intent(Intent.ACTION_VIEW,uri).addCategory(Intent.CATEGORY_BROWSABLE));}catch(Exception e){deliver(Json.obj("type","toast","message","没有可打开链接的浏览器"));}});return;}
                if(a.optString("type").equals("theme")){runOnUiThread(()->{boolean light=a.optString("value").equals("light");getWindow().setStatusBarColor(light?0xfff7f7f8:0xff17181b);getWindow().setNavigationBarColor(light?0xfff7f7f8:0xff17181b);getWindow().getDecorView().setSystemUiVisibility(light?View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR:0);});}
                if(service!=null)service.action(a);
            }catch(Exception e){deliver(Json.obj("type","toast","message","请求无法解析"));}
        }
    }
    @Override public void onActivityResult(int requestCode,int resultCode,Intent data){super.onActivityResult(requestCode,resultCode,data);
        if(requestCode==22){if(resultCode==RESULT_OK&&data!=null&&data.getData()!=null){if(service!=null){service.exportImage(data.getData(),exportImageId);exportImageId="";}else pendingExportUri=data.getData();}else exportImageId="";return;}
        if(resultCode!=RESULT_OK||data==null)return;if(requestCode==20&&service!=null&&data.getData()!=null)service.exportLogs(data.getData());if(requestCode==21){java.util.List<android.net.Uri> uris=new java.util.ArrayList<>();if(data.getClipData()!=null){for(int i=0;i<Math.min(4,data.getClipData().getItemCount());i++)uris.add(data.getClipData().getItemAt(i).getUri());}else if(data.getData()!=null)uris.add(data.getData());if(service!=null)service.importImages(uris,imageDraftKey);else pendingImages=uris;}}
    @Override public void onSaveInstanceState(Bundle out){out.putString("imageDraftKey",imageDraftKey);out.putString("exportImageId",exportImageId);if(pendingExportUri!=null)out.putString("pendingExportUri",pendingExportUri.toString());super.onSaveInstanceState(out);}
    @Override public void onBackPressed(){web.evaluateJavascript("window.back()",null);}
    @Override public void onResume(){super.onResume();if(service!=null)service.update();}
    @Override public void onDestroy(){pageReady=false;if(service!=null)service.unlisten(listener);if(bound)unbindService(connection);if(web!=null){web.removeJavascriptInterface("Android");web.destroy();web=null;}super.onDestroy();}
}
