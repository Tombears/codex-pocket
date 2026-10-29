package dev.codexpocket;

import android.app.Activity;
import android.content.*;
import android.os.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class UpgradeAndSshTest {
    @Test public void actualAndroidSshAndSignedUpgradePreserveEncryptedConfiguration()throws Exception{
        var instrumentation=InstrumentationRegistry.getInstrumentation();Context context=instrumentation.getTargetContext();
        boolean write="write".equals(InstrumentationRegistry.getArguments().getString("stage"));
        Store store=new Store(context);
        if(write){
            context.getSharedPreferences("upgrade_test_fixture",Context.MODE_PRIVATE).edit().putInt("baseVersionCode",context.getPackageManager().getPackageInfo(context.getPackageName(),0).versionCode).commit();
            JSONObject host=store.saveHost(Json.obj("id","upgrade-host","name","upgrade fixture","host","192.0.2.1","port",22,"user","root","auth","password","password","fixture-preserved","cwd","","codex",""));
            JSONObject chat=store.newChat(host.getString("id"),"");store.message(chat,"upgrade-message","user","text","upgrade history preserved");store.theme("light");store.flush();
            JSONObject fixture;try(var in=instrumentation.getContext().getAssets().open("ssh-fixture.json")){var bytes=new java.io.ByteArrayOutputStream();byte[] buffer=new byte[4096];int count;while((count=in.read(buffer))!=-1)bytes.write(buffer,0,count);fixture=new JSONObject(bytes.toString("UTF-8"));}
            JSONObject sshHost=store.saveHost(Json.obj("id","ssh-fixture","name","localhost fixture","host","10.0.2.2","port",2222,"user","fixture","auth","key","privateKey",fixture.getString("clientKey"),"cwd","","codex",""));
            store.put(sshHost,"fingerprint",fixture.getString("fingerprint"));store.flush();
            Activity activity=instrumentation.startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            CountDownLatch ready=new CountDownLatch(1);CodexService[] owner=new CodexService[1];
            ServiceConnection binding=new ServiceConnection(){public void onServiceConnected(ComponentName n,IBinder b){owner[0]=((CodexService.LocalBinder)b).service();ready.countDown();}public void onServiceDisconnected(ComponentName n){}};
            assertTrue(context.bindService(new Intent(context,CodexService.class),binding,Context.BIND_AUTO_CREATE));
            assertTrue("Service did not bind",ready.await(15,TimeUnit.SECONDS));
            CodexEngine engine=new CodexEngine(owner[0],"ssh-fixture");
            try{
                engine.connect();assertEquals("online",engine.connection.snapshot().status());assertEquals("/home/fixture",engine.runtimeCwd);engine.close();
                JSONObject draft=Json.obj("id","ssh-fixture","name","unsaved-test-name","host","10.0.2.2","port",2222,"user","fixture","auth","key","privateKey","","cwd","","codex","");
                assertFalse(owner[0].store.prepareHost(draft).getString("privateKey").isEmpty());
                CountDownLatch tested=new CountDownLatch(1);java.util.concurrent.atomic.AtomicReference<JSONObject> result=new java.util.concurrent.atomic.AtomicReference<>();
                CodexService.Listener testListener=e->{if(e.optString("type").equals("connectionTestResult")){result.set(e);tested.countDown();}};owner[0].listen(testListener);
                owner[0].action(Json.obj("type","testConnection","testId","fixture-check","host",draft));assertTrue("Connection test timed out",tested.await(35,TimeUnit.SECONDS));assertTrue(result.get().toString(),result.get().optBoolean("ok"));
                assertEquals("localhost fixture",owner[0].store.host("ssh-fixture").getString("name"));owner[0].unlisten(testListener);
                owner[0].action(Json.obj("type","testConnection","testId","cancel-check","host",draft));owner[0].action(Json.obj("type","cancelConnectionTest","testId","cancel-check"));assertTrue(owner[0].tests.isEmpty());
                owner[0].action(Json.obj("type","connect","hostId","ssh-fixture"));
                await(()->owner[0].engines.containsKey("ssh-fixture")&&owner[0].engines.get("ssh-fixture").connection.snapshot().status().equals("online"),35000);
                CodexEngine kept=owner[0].engines.get("ssh-fixture");assertTrue(owner[0].store.wantedConnections().contains("ssh-fixture"));
                instrumentation.runOnMainSync(()->activity.moveTaskToBack(true));Thread.sleep(12000);assertTrue("Background transport should remain alive",kept.transportAlive());
                Attachments media=new Attachments(context);java.io.File picture=new java.io.File(context.getCacheDir(),"fixture-image.png");android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(32,24,android.graphics.Bitmap.Config.ARGB_8888);bitmap.eraseColor(android.graphics.Color.BLUE);try(var out=new java.io.FileOutputStream(picture)){bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}finally{bitmap.recycle();}
                JSONObject image=media.add(android.net.Uri.fromFile(picture));Attachments.Batch batch=media.prepare(new JSONArray().put(Json.obj("id",image.getString("id"))));assertTrue(batch.input().getJSONObject(0).getString("url").startsWith("data:image/jpeg;base64,"));assertTrue(image.getString("preview").length()<50000);
                JSONObject interrupted=owner[0].store.newChat("ssh-fixture","");kept.send(interrupted.getString("id"),"",batch);assertEquals("图片对话",interrupted.getString("title"));kept.steer(interrupted.getString("id"),"focus on the blue area",batch);assertEquals(2,interrupted.getJSONArray("messages").length());assertTrue(interrupted.getJSONArray("messages").getJSONObject(1).getBoolean("steering"));
                assertThrows(java.io.IOException.class,()->kept.steer("wrong-chat","no",batch));media.remove(image.getString("id"));assertThrows(Exception.class,()->media.prepare(new JSONArray().put(Json.obj("id",image.getString("id")))));picture.delete();kept.networkLost();
                await(()->kept.connection.snapshot().status().equals("online"),35000);assertEquals("interrupted",interrupted.getString("status"));assertEquals("",kept.busyChat);
                owner[0].action(Json.obj("type","disconnect","hostId","ssh-fixture"));await(()->!owner[0].store.wantedConnections().contains("ssh-fixture"),5000);Thread.sleep(6000);assertEquals("offline",kept.connection.snapshot().status());
            }finally{engine.close();owner[0].action(Json.obj("type","disconnect","hostId","ssh-fixture"));context.unbindService(binding);instrumentation.runOnMainSync(activity::finish);}
        }else{
            assertEquals("fixture-preserved",store.host("upgrade-host").getString("password"));
            assertTrue(store.snapshot().toString().contains("upgrade history preserved"));assertEquals("light",store.snapshot().getString("theme"));
            assertEquals(context.getSharedPreferences("upgrade_test_fixture",Context.MODE_PRIVATE).getInt("baseVersionCode",-1)+1,context.getPackageManager().getPackageInfo(context.getPackageName(),0).versionCode);
        }
        String encrypted=context.getSharedPreferences("encrypted_store",Context.MODE_PRIVATE).getString("data","");assertFalse(encrypted.contains("fixture-preserved"));assertFalse(encrypted.contains("upgrade history preserved"));
        DiagnosticLog log=new DiagnosticLog(context);
        try{
            var values=new ContentValues();values.put("ts",System.currentTimeMillis()-LogPolicy.MAX_AGE_MS-1000);values.put("message","expired-log-entry");log.getWritableDatabase().insert("events",null,values);
            log.record("INFO","test","current-log-entry password=must-not-leak");String exported=log.exportText();assertFalse(exported.contains("expired-log-entry"));assertFalse(exported.contains("must-not-leak"));assertTrue(exported.contains("current-log-entry"));
        }finally{log.close();}
    }
    private static void await(java.util.function.BooleanSupplier condition,long timeout)throws Exception{long deadline=SystemClock.elapsedRealtime()+timeout;while(!condition.getAsBoolean()&&SystemClock.elapsedRealtime()<deadline)Thread.sleep(100);assertTrue("Condition timed out",condition.getAsBoolean());}
}
