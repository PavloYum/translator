package com.veluvara.translatorlab;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.os.*;
import android.speech.tts.*;
import com.google.mlkit.common.model.RemoteModelManager;
import com.google.mlkit.nl.translate.*;
import org.json.*;
import java.util.*;

/** User-started connection to the paired Pi; never records or plays audio. */
public final class DiagnosticsService extends Service {
    static final String STOP="stop", RECONNECT="reconnect", TEST="test";
    private final Handler main=new Handler(Looper.getMainLooper());
    private MqttDiagnostics mqtt;
    private Translator translator;
    private TextToSpeech tts;
    private boolean alive=false, ready=false, testing=false, foreground=false;
    private long connectionEpoch=0;
    private long epoch=0;
    private Runnable timeout;
    private SharedPreferences prefs;
    @Override public void onCreate() {
        super.onCreate(); alive=true; prefs=getSharedPreferences("voice",MODE_PRIVATE);
        getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel("diagnostics","Связь с Raspberry Pi",NotificationManager.IMPORTANCE_LOW));
        tts=new TextToSpeech(this,result -> { if(alive) ready=result==TextToSpeech.SUCCESS; });
    }
    private Notification notification(String message) {
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,DiagnosticsService.class).setAction(STOP),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this,"diagnostics").setSmallIcon(R.drawable.ic_status)
            .setContentTitle("Translator · связь с Pi").setContentText(message).setContentIntent(open)
            .setOngoing(true).setOnlyAlertOnce(true).addAction(new Notification.Action.Builder(null,"Отключить",stop).build()).build();
    }
    private void status(String value) {
        if(!alive) return;
        prefs.edit().putString("mqtt_status",value).putLong("mqtt_status_at",System.currentTimeMillis()).apply();
        getSystemService(NotificationManager.class).notify(41,notification(value));
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        String action=intent==null?"":intent.getAction();
        if(STOP.equals(action) || !prefs.getBoolean("mqtt_enabled",false)) {
            prefs.edit().putBoolean("mqtt_enabled",false).apply(); stopSelf(); return START_NOT_STICKY;
        }
        if(!foreground) {
        if(Build.VERSION.SDK_INT>=34) startForeground(41,notification("Подключение к Raspberry Pi…"),ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        else startForeground(41,notification("Подключение к Raspberry Pi…"));
        foreground=true; }
        if(RECONNECT.equals(action)) { cancelTest(); if(mqtt!=null) mqtt.close(); mqtt=null; }
        if(mqtt==null) {
            try {
                String password=SecretStore.load(this); if(password.isEmpty()) throw new IllegalStateException();
                final long connection=++connectionEpoch;
                mqtt=new MqttDiagnostics(prefs.getString("mqtt_user","translator-phone"),password,
                    value -> main.post(() -> {if(connection==connectionEpoch)status(value);}),id -> main.post(() -> {if(connection==connectionEpoch)selfTest(id,true);}));
                status("Подключение к Raspberry Pi…");
            } catch(Exception e) { status("Введите MQTT-доступ в настройках"); stopSelf(); return START_NOT_STICKY; }
        }
        if(TEST.equals(action)) selfTest("local-"+UUID.randomUUID(),false);
        return START_STICKY;
    }
    private int offlineVoices() {
        if(!ready || tts==null || tts.getVoices()==null) return 0;
        int count=0; for(Voice voice:tts.getVoices()) if(!voice.isNetworkConnectionRequired() && voice.getLocale().getLanguage().equals("de")) count++;
        return count;
    }
    private void cancelTest() {
        epoch++; testing=false;
        if(timeout!=null) main.removeCallbacks(timeout);
        if(translator!=null) { translator.close(); translator=null; }
    }
    private void selfTest(String id,boolean remote) {
        if(!alive) return;
        JSONObject report=new JSONObject();
        try { report.put("request_id",id).put("version","0.6.0-preview").put("timestamp",System.currentTimeMillis()/1000)
            .put("tts_ready",ready).put("offline_voices_for_target",offlineVoices()).put("voice_target","de")
            .put("microphone_tested",false).put("background_service",true).put("app_visible",MainActivity.UI_VISIBLE);
            if(testing) { report.put("status","busy"); if(remote && mqtt!=null) mqtt.reply(report); return; }
        } catch(JSONException e) { return; }
        testing=true; long request=++epoch; MqttDiagnostics channel=mqtt;
        java.util.function.Consumer<String> finish=result -> {
            if(!alive || request!=epoch) return;
            cancelTest();
            try {
                report.put("status",result);
                prefs.edit().putString("mqtt_report",report.toString()).putLong("mqtt_report_at",System.currentTimeMillis()).apply();
                if(remote && channel==mqtt && channel!=null) channel.reply(report);
            } catch(JSONException ignored) {}
        };
        timeout=() -> finish.accept("timeout"); main.postDelayed(timeout,20000);
        RemoteModelManager.getInstance().getDownloadedModels(TranslateRemoteModel.class).addOnSuccessListener(models -> {
            if(!alive || request!=epoch) return;
            Set<String> installed=new TreeSet<>();for(TranslateRemoteModel model:models) installed.add(model.getLanguage());
            try { report.put("installed_models",new JSONArray(installed)); } catch(JSONException ignored) {}
            if(!installed.contains("ru") || !installed.contains("de")) { finish.accept("models_missing"); return; }
            translator=Translation.getClient(new TranslatorOptions.Builder().setSourceLanguage("ru").setTargetLanguage("de").build());
            long started=SystemClock.elapsedRealtime();
            translator.translate("Здравствуйте. Где находится вокзал?").addOnSuccessListener(result -> {
                if(!alive || request!=epoch) return;
                try { report.put("translation_ms",SystemClock.elapsedRealtime()-started).put("result_nonempty",!result.trim().isEmpty()); } catch(JSONException ignored) {}
                finish.accept(result.trim().isEmpty()?"empty_result":(ready && offlineVoices()>0?"ok":"limited"));
            }).addOnFailureListener(error -> finish.accept("translation_error"));
        }).addOnFailureListener(error -> finish.accept("models_error"));
    }
    @Override public void onDestroy() {
        alive=false; cancelTest(); if(mqtt!=null) mqtt.close(); if(tts!=null) tts.shutdown();
        prefs.edit().putString("mqtt_status","Фоновый сервис остановлен").putLong("mqtt_status_at",System.currentTimeMillis()).apply();
        stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
