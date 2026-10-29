package dev.codexpocket;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import android.os.Build;
import java.text.SimpleDateFormat;
import java.util.*;

final class DiagnosticLog extends SQLiteOpenHelper {
    private final Context context;
    DiagnosticLog(Context c){super(c,"diagnostics.db",null,1);context=c.getApplicationContext();prune();}
    @Override public void onCreate(SQLiteDatabase db){db.execSQL("CREATE TABLE events (id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, level TEXT, tag TEXT, message TEXT)");db.execSQL("CREATE INDEX events_ts ON events(ts)");}
    @Override public void onUpgrade(SQLiteDatabase db,int oldVersion,int newVersion){}
    synchronized void prune(){SQLiteDatabase db=getWritableDatabase();db.delete("events","ts < ?",new String[]{Long.toString(LogPolicy.cutoff(System.currentTimeMillis()))});db.execSQL("DELETE FROM events WHERE id NOT IN (SELECT id FROM events ORDER BY id DESC LIMIT "+LogPolicy.MAX_ROWS+")");}
    synchronized void record(String level,String tag,String message){
        ContentValues values=new ContentValues();values.put("ts",System.currentTimeMillis());values.put("level",level);values.put("tag",tag);values.put("message",LogPolicy.safe(message));getWritableDatabase().insertOrThrow("events",null,values);prune();
    }
    synchronized String exportText(){
        prune();String version="unknown";try{version=context.getPackageManager().getPackageInfo(context.getPackageName(),0).versionName;}catch(Exception ignored){}
        StringBuilder text=new StringBuilder("Codex Pocket diagnostics\nVersion: "+version+"\nAndroid: "+Build.VERSION.RELEASE+" (API "+Build.VERSION.SDK_INT+")\nDevice: "+Build.MANUFACTURER+" "+Build.MODEL+"\nRetention: last 24 hours\nPrivate keys, passwords and chat content are not logged.\n\n");
        SimpleDateFormat format=new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z",Locale.ROOT);
        try(Cursor cursor=getReadableDatabase().query("events",new String[]{"ts","level","tag","message"},"ts >= ?",new String[]{Long.toString(LogPolicy.cutoff(System.currentTimeMillis()))},null,null,"ts ASC,id ASC")){
            while(cursor.moveToNext())text.append(format.format(new Date(cursor.getLong(0)))).append(" [").append(cursor.getString(1)).append("] ").append(cursor.getString(2)).append(" ").append(cursor.getString(3)).append('\n');
        }
        return text.toString();
    }
}
