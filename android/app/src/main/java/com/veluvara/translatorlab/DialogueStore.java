package com.veluvara.translatorlab;

import android.content.Context;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.ArrayList;
import java.util.List;

/** Private app storage; call only on the journal executor. */
final class DialogueStore extends SQLiteOpenHelper {
    DialogueStore(Context context){super(context.getApplicationContext(),"dialogues.db",null,1);}
    @Override public void onConfigure(SQLiteDatabase db){try(Cursor c=db.rawQuery("PRAGMA secure_delete=ON",null)){c.moveToFirst();}}
    @Override public void onCreate(SQLiteDatabase db){
        db.execSQL("CREATE TABLE phrases (id INTEGER PRIMARY KEY AUTOINCREMENT, session TEXT NOT NULL, request TEXT NOT NULL UNIQUE, created INTEGER NOT NULL, source_lang TEXT NOT NULL, target_lang TEXT NOT NULL, original TEXT NOT NULL, translated TEXT NOT NULL)");
        db.execSQL("CREATE INDEX phrases_session ON phrases(session,id)");
    }
    @Override public void onUpgrade(SQLiteDatabase db,int oldVersion,int newVersion){throw new IllegalStateException("Unsupported journal schema");}
    void save(String session,String request,long created,String from,String to,String original,String translated){
        ContentValues v=new ContentValues();v.put("session",session);v.put("request",request);v.put("created",created);v.put("source_lang",from);v.put("target_lang",to);v.put("original",original);v.put("translated",translated);
        if(getWritableDatabase().insertWithOnConflict("phrases",null,v,SQLiteDatabase.CONFLICT_IGNORE)<0)throw new IllegalStateException("Journal insert failed");
    }
    List<Entry> page(long before){
        List<Entry> result=new ArrayList<>();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT id,session,created,source_lang,target_lang,original,translated FROM phrases WHERE id < ? ORDER BY id DESC LIMIT 50",new String[]{Long.toString(before)})){
            while(c.moveToNext())result.add(new Entry(c.getLong(0),c.getString(1),c.getLong(2),c.getString(3),c.getString(4),c.getString(5),c.getString(6)));
        }return result;
    }
    void delete(String session){getWritableDatabase().delete("phrases",session==null?null:"session = ?",session==null?null:new String[]{session});}
    static final class Entry {
        final long id,created;final String session,from,to,original,translated;
        Entry(long id,String session,long created,String from,String to,String original,String translated){this.id=id;this.session=session;this.created=created;this.from=from;this.to=to;this.original=original;this.translated=translated;}
    }
}
