package com.veluvara.translatorlab;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.speech.RecognizerIntent;
import android.text.Editable;
import android.text.TextWatcher;
import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.nl.translate.*;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.*;
import android.os.Bundle;
import android.speech.tts.*;
import android.view.View;
import android.widget.*;
import java.io.*;
import java.nio.*;
import java.util.*;

/** Foreground translator: ML Kit text translation and system TTS. */
public class MainActivity extends Activity {
    private LinearLayout body;
    private MqttDiagnostics diagnostics;
    private TextView mqttStatus;
    private boolean visible=false, testing=false;
    private Translator diagnosticTranslator;
    private final android.os.Handler diagnosticHandler=new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable diagnosticTimeout;
    private TextView status;
    private EditText text;
    private Spinner languages, voices, sourceLanguage;
    private TextView output, translationStatus;
    private CheckBox mobileModels;
    private Translator translator;
    private long generation = 0;
    private boolean translating = false;
    private int speechLanguage;
    private SeekBar rate, pitch;
    private TextToSpeech tts;
    private boolean ready = false;
    private volatile boolean recording = false;
    private Thread worker;
    private SharedPreferences prefs;
    private final String[] tags = {"ru-RU", "de-DE", "en-US"};
    private final String[] examples = {"Здравствуйте. Как пройти к вокзалу?", "Guten Tag. Wie komme ich zum Bahnhof?", "Hello. How do I get to the station?"};
    private List<Voice> available = new ArrayList<>();

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("voice", MODE_PRIVATE);
        ScrollView scroll = new ScrollView(this);
        body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL); body.setPadding(32,64,32,32);
        scroll.addView(body); setContentView(scroll);
        label("Translator · переводчик 0.3");
        label("Перевод текста на телефоне. Для первого перевода нужны языковые модели Google; загрузка по Wi-Fi. История текста не сохраняется.");
        label("С какого языка");
        sourceLanguage=new Spinner(this); sourceLanguage.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,tags)); body.addView(sourceLanguage);
        label("На какой язык");
        languages = new Spinner(this); languages.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, tags)); body.addView(languages);
        languages.setSelection(1);
        voices = new Spinner(this);
        text = new EditText(this); text.setText(examples[0]); text.setMinLines(2); text.setSaveEnabled(false); body.addView(text);
        text.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(3000)});
        button("Сказать фразу", this::dictate);
        mobileModels=new CheckBox(this); mobileModels.setText("Разрешить загрузку моделей через мобильный интернет"); body.addView(mobileModels);
        button("Перевести с Google", this::translate);
        button("Поменять направление", () -> { int from=sourceLanguage.getSelectedItemPosition(), to=languages.getSelectedItemPosition(); sourceLanguage.setSelection(to); languages.setSelection(from); });
        translationStatus=label("Введите или продиктуйте текст (до 3000 символов)");
        output=label(""); output.setTextIsSelectable(true); output.setSaveEnabled(false);
        ImageView attribution=new ImageView(this); attribution.setImageResource(com.veluvara.translatorlab.R.drawable.google_translate_attribution);
        attribution.setAdjustViewBounds(true); attribution.setContentDescription("Powered by Google Translate");
        body.addView(attribution,new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,LinearLayout.LayoutParams.WRAP_CONTENT));
        label("Машинный перевод Google Translate через ML Kit. Возможны ошибки.");
        button("О переводе и конфиденциальности", () -> new AlertDialog.Builder(this).setTitle("О переводе")
            .setMessage("Текст переводится на телефоне через Google ML Kit. Модели загружаются из Google; SDK может отправлять служебные сведения. Голосовой ввод обрабатывает системный сервис, который может использовать интернет. Историю текста приложение не сохраняет.\n\nhttps://cloud.google.com/translate\n\nTHIS SERVICE MAY CONTAIN TRANSLATIONS POWERED BY GOOGLE. GOOGLE DISCLAIMS ALL WARRANTIES RELATED TO THE TRANSLATIONS, EXPRESS OR IMPLIED, INCLUDING ANY WARRANTIES OF ACCURACY, RELIABILITY, AND ANY IMPLIED WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.")
            .setPositiveButton("Закрыть",null).show());
        label("Озвучка результата"); body.addView(voices);
        rate = slider("Скорость", "rate"); pitch = slider("Высота тона", "pitch");
        button("Озвучить перевод", this::speak);
        button("Остановить", () -> { if(tts!=null) tts.stop(); recording=false; invalidateTranslation(); translationStatus.setText("Остановлено. Загрузка модели может продолжиться в фоне."); });
        button("Сбросить настройки голоса", () -> { rate.setProgress(50); pitch.setProgress(50); });
        label("Аудиолаборатория — запись WAV для проверки");
        button("Записать образец", this::record);
        button("Удалить образец", () -> {
            if(worker!=null && worker.isAlive()) { status.setText("Сначала остановите запись"); return; }
            File file=new File(getFilesDir(), "sample.wav");
            status.setText(!file.exists() || file.delete() ? "Образец удалён" : "Не удалось удалить файл");
        });
        status=label("Инициализация голоса…");
        label("Удалённая самопроверка MQTT");
        label("Только при открытом приложении. Проверяет модели и тестовую фразу, не включает микрофон и не передаёт ваши тексты.");
        mqttStatus=label("MQTT выключен");
        EditText mqttUser=new EditText(this); mqttUser.setHint("Имя MQTT"); mqttUser.setText(prefs.getString("mqtt_user","translator-phone")); body.addView(mqttUser);
        EditText mqttPassword=new EditText(this); mqttPassword.setHint("Пароль (пусто — оставить сохранённый)");
        mqttPassword.setInputType(129); mqttPassword.setSaveEnabled(false); body.addView(mqttPassword);
        button("Сохранить и включить MQTT", () -> {
            try {
                String password=mqttPassword.getText().toString();
                if(!password.isEmpty()) SecretStore.save(this,password);
                if(SecretStore.load(this).isEmpty()) { mqttStatus.setText("Введите пароль MQTT"); return; }
                prefs.edit().putString("mqtt_user",mqttUser.getText().toString().trim()).putBoolean("mqtt_enabled",true).apply();
                mqttPassword.setText(""); stopDiagnostics(); startDiagnostics();
            } catch(Exception e) { mqttStatus.setText("Не удалось сохранить доступ в Android Keystore"); }
        });
        button("Выключить MQTT", () -> { prefs.edit().putBoolean("mqtt_enabled",false).apply(); stopDiagnostics(); mqttStatus.setText("MQTT выключен"); });
        languages.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onNothingSelected(AdapterView<?> parent) {}
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                invalidateTranslation(); loadVoices();
            }
        });
        voices.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onNothingSelected(AdapterView<?> parent) {}
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if(position<available.size()) prefs.edit().putString(tag()+"voice", available.get(position).getName()).apply();
            }
        });
        sourceLanguage.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onNothingSelected(AdapterView<?> p) {}
            public void onItemSelected(AdapterView<?> p,View v,int pos,long id) { invalidateTranslation(); }
        });
        text.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s,int start,int count,int after) {}
            public void onTextChanged(CharSequence s,int start,int before,int count) { invalidateTranslation(); }
            public void afterTextChanged(Editable e) {}
        });
        tts=new TextToSpeech(this, result -> {
            ready=result==TextToSpeech.SUCCESS;
            if(ready) loadVoices(); else status.setText("Системная озвучка недоступна");
        });
    }
    private void startDiagnostics() {
        if(!visible || diagnostics!=null || !prefs.getBoolean("mqtt_enabled",false)) return;
        try {
            String password=SecretStore.load(this);
            if(password.isEmpty()) { mqttStatus.setText("Нужен пароль MQTT"); return; }
            diagnostics=new MqttDiagnostics(prefs.getString("mqtt_user","translator-phone"),password,
                message -> runOnUiThread(() -> { if(visible) mqttStatus.setText(message); }),
                id -> runOnUiThread(() -> selfTest(id)));
        } catch(Exception e) { mqttStatus.setText("Сохранённый доступ недоступен; введите пароль повторно"); }
    }
    private void stopDiagnostics() {
        if(diagnostics!=null) { diagnostics.close(); diagnostics=null; }
        if(diagnosticTimeout!=null) diagnosticHandler.removeCallbacks(diagnosticTimeout);
        if(diagnosticTranslator!=null) { diagnosticTranslator.close(); diagnosticTranslator=null; }
        testing=false;
    }
    private void selfTest(String id) {
        if(!visible || diagnostics==null) return;
        final MqttDiagnostics channel=diagnostics;
        final org.json.JSONObject report=new org.json.JSONObject();
        try {
            report.put("request_id",id).put("version","0.3.0-preview").put("timestamp",System.currentTimeMillis()/1000)
                .put("tts_ready",ready).put("offline_voices_for_target",available.size()).put("microphone_tested",false);
            if(testing || translating || recording) { report.put("status","busy"); channel.reply(report); return; }
        } catch(org.json.JSONException e) { return; }
        testing=true;
        final boolean[] done={false};
        final java.util.function.Consumer<String> finish=result -> {
            if(done[0] || !visible || diagnostics!=channel) return; done[0]=true; testing=false;
            if(diagnosticTimeout!=null) diagnosticHandler.removeCallbacks(diagnosticTimeout);
            if(diagnosticTranslator!=null) { diagnosticTranslator.close(); diagnosticTranslator=null; }
            if(!visible || diagnostics!=channel) return;
            try { report.put("status",result); channel.reply(report); mqttStatus.setText("Самопроверка: "+result); } catch(Exception ignored) {}
        };
        diagnosticTimeout=() -> finish.accept("timeout"); diagnosticHandler.postDelayed(diagnosticTimeout,20000);
        com.google.mlkit.common.model.RemoteModelManager.getInstance().getDownloadedModels(TranslateRemoteModel.class)
            .addOnSuccessListener(models -> {
                if(done[0] || !visible || diagnostics!=channel) return;
                java.util.Set<String> installed=new java.util.TreeSet<>(); for(TranslateRemoteModel model:models) installed.add(model.getLanguage());
                try { report.put("installed_models",new org.json.JSONArray(installed)); } catch(Exception ignored) {}
                if(!installed.contains("ru") || !installed.contains("de")) { finish.accept("models_missing"); return; }
                diagnosticTranslator=Translation.getClient(new TranslatorOptions.Builder().setSourceLanguage("ru").setTargetLanguage("de").build());
                final long start=android.os.SystemClock.elapsedRealtime();
                diagnosticTranslator.translate("Здравствуйте. Где находится вокзал?").addOnSuccessListener(result -> {
                    if(done[0] || !visible || diagnostics!=channel) return;
                    try { report.put("translation_ms",android.os.SystemClock.elapsedRealtime()-start).put("result_nonempty",!result.trim().isEmpty()); } catch(Exception ignored) {}
                    finish.accept(result.trim().isEmpty()?"empty_result":(ready && !available.isEmpty()?"ok":"limited"));
                }).addOnFailureListener(error -> finish.accept("translation_error"));
            }).addOnFailureListener(error -> finish.accept("models_error"));
    }
    @Override protected void onStart() { super.onStart(); visible=true; startDiagnostics(); }
    @Override protected void onStop() { visible=false; stopDiagnostics(); super.onStop(); }
    private String tag() { return tags[languages.getSelectedItemPosition()]; }
    private TextView label(String value) { TextView v=new TextView(this); v.setText(value); v.setTextSize(18); v.setPadding(0,12,0,12); body.addView(v); return v; }
    private void button(String title,Runnable action) { Button b=new Button(this); b.setText(title); body.addView(b); b.setOnClickListener(v -> action.run()); }
    private SeekBar slider(String title,String key) {
        TextView caption=label(title+": 1.00"); SeekBar s=new SeekBar(this); s.setMax(150); s.setProgress(50); body.addView(s);
        s.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onStartTrackingTouch(SeekBar s) {} public void onStopTrackingTouch(SeekBar s) {}
            public void onProgressChanged(SeekBar s,int value,boolean user) {
                caption.setText(String.format(Locale.ROOT,"%s: %.2f",title,0.5f+value/100f));
                prefs.edit().putInt(tag()+key,value).apply();
            }
        }); return s;
    }
    private void loadVoices() {
        if(!ready) return;
        int savedRate=prefs.getInt(tag()+"rate",50), savedPitch=prefs.getInt(tag()+"pitch",50);
        rate.setProgress(savedRate); pitch.setProgress(savedPitch);
        available=new ArrayList<>(); Set<Voice> all=tts.getVoices();
        if(all!=null) for(Voice v:all) if(v.getLocale().getLanguage().equals(Locale.forLanguageTag(tag()).getLanguage()) && !v.isNetworkConnectionRequired()) available.add(v);
        available.sort(Comparator.comparing(Voice::getName));
        List<String> names=new ArrayList<>(); for(Voice v:available) names.add(v.getName());
        String selected=prefs.getString(tag()+"voice","");
        voices.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,names));
        for(int i=0;i<names.size();i++) if(names.get(i).equals(selected)) voices.setSelection(i);
        status.setText(available.isEmpty()?"Установите офлайн-голос этого языка в настройках Android":"Выбран голос без требования сети. Проверьте звучание на телефоне.");
    }
    private void speak() {
        if(recording) { status.setText("Сначала остановите запись"); return; }
        if(output.getText().length()==0) { status.setText("Сначала переведите фразу"); return; }
        if(!ready || available.isEmpty()) { status.setText("Нет доступного голоса"); return; }
        int choice=voices.getSelectedItemPosition(); if(choice<0 || choice>=available.size()) return;
        if(tts.setVoice(available.get(choice))==TextToSpeech.ERROR || tts.setSpeechRate(0.5f+rate.getProgress()/100f)==TextToSpeech.ERROR || tts.setPitch(0.5f+pitch.getProgress()/100f)==TextToSpeech.ERROR) {
            status.setText("Движок не принял настройки"); return;
        }
        if(tts.speak(output.getText().toString(),TextToSpeech.QUEUE_FLUSH,null,"preview")==TextToSpeech.ERROR) status.setText("Не удалось воспроизвести голос");
    }
    private void invalidateTranslation() {
        generation++; translating=false;
        if(tts!=null) tts.stop();
        if(output!=null) output.setText("");
        if(translationStatus!=null) translationStatus.setText("Готов к переводу");
        if(translator!=null) { translator.close(); translator=null; }
    }
    private void translate() {
        if(recording) { translationStatus.setText("Сначала остановите запись"); return; }
        if(translating) return;
        String input=text.getText().toString().trim();
        if(input.isEmpty()) { translationStatus.setText("Введите текст"); return; }
        invalidateTranslation();
        int from=sourceLanguage.getSelectedItemPosition(),to=languages.getSelectedItemPosition();
        if(from==to) { output.setText(input); translationStatus.setText("Выбран один язык — текст без перевода"); return; }
        final long request= generation;
        translating=true;
        Translator client=Translation.getClient(new TranslatorOptions.Builder()
            .setSourceLanguage(tags[from].substring(0,2)).setTargetLanguage(tags[to].substring(0,2)).build());
        translator=client;
        DownloadConditions.Builder conditions=new DownloadConditions.Builder();
        if(!mobileModels.isChecked()) conditions.requireWifi();
        translationStatus.setText("Проверка/загрузка моделей… При первом запуске подключитесь к Wi-Fi.");
        client.downloadModelIfNeeded(conditions.build()).addOnSuccessListener(v -> {
            if(request!=generation || isDestroyed()) return;
            translationStatus.setText("Перевод…");
            final long started=android.os.SystemClock.elapsedRealtime();
            client.translate(input).addOnSuccessListener(result -> {
                if(request!=generation || isDestroyed()) return;
                output.setText(result); translating=false;
                translationStatus.setText("Переведено на телефоне за "+(android.os.SystemClock.elapsedRealtime()-started)+" мс (без загрузки моделей)");
            }).addOnFailureListener(error -> translationFailed(request));
        }).addOnFailureListener(error -> translationFailed(request));
    }
    private void translationFailed(long request) {
        if(request!=generation || isDestroyed()) return;
        translating=false;
        translationStatus.setText("Перевод не выполнен. Проверьте интернет для загрузки моделей и свободное место; затем повторите.");
    }
    private void dictate() {
        if(recording) { status.setText("Сначала остановите запись WAV"); return; }
        new AlertDialog.Builder(this).setTitle("Голосовой ввод Android")
            .setMessage("Системный сервис распознавания может передать речь своему провайдеру. Офлайн-режим зависит от телефона. Продолжить?")
            .setNegativeButton("Отмена",null).setPositiveButton("Продолжить",(dialog,which) -> {
                if(tts!=null) tts.stop(); invalidateTranslation();
                speechLanguage=sourceLanguage.getSelectedItemPosition();
                Intent intent=new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
                intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
                intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE,tags[speechLanguage]);
                intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE,true);
                intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS,1);
                try { startActivityForResult(intent,20); }
                catch(android.content.ActivityNotFoundException e) { status.setText("Голосовой ввод не установлен. Можно ввести текст вручную."); }
            }).show();
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(request==20 && result==RESULT_OK && data!=null && speechLanguage==sourceLanguage.getSelectedItemPosition()) {
            ArrayList<String> phrases=data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if(phrases!=null && !phrases.isEmpty()) { text.setText(phrases.get(0)); translationStatus.setText("Проверьте распознанную фразу и нажмите «Перевести»"); }
        }
    }
    private void record() {
        if(worker!=null && worker.isAlive()) return;
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},1); status.setText("После выдачи разрешения нажмите запись ещё раз"); return;
        }
        if(tts!=null) tts.stop(); recording=true; status.setText("Запись… максимум 60 секунд. Новый образец заменит предыдущий.");
        worker=new Thread(() -> {
            AudioRecord mic=null;
            try {
                int min=AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
                if(min<=0) throw new IOException("Формат микрофона не поддерживается");
                mic=new AudioRecord(MediaRecorder.AudioSource.MIC,16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,Math.max(min,6400));
                if(mic.getState()!=AudioRecord.STATE_INITIALIZED) throw new IOException("Микрофон недоступен");
                ByteArrayOutputStream pcm=new ByteArrayOutputStream(); byte[] buffer=new byte[3200]; mic.startRecording();
                while(recording && pcm.size()<1920000) {
                    int n=mic.read(buffer,0,Math.min(buffer.length,1920000-pcm.size()));
                    if(n<=0) throw new IOException("Ошибка чтения микрофона"); pcm.write(buffer,0,n);
                }
                if(pcm.size()<3200) throw new IOException("Слишком короткая запись");
                byte[] data=pcm.toByteArray();
                ByteBuffer header=ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
                header.put(new byte[]{'R','I','F','F'}).putInt(36+data.length).put(new byte[]{'W','A','V','E','f','m','t',' '}).putInt(16).putShort((short)1).putShort((short)1).putInt(16000).putInt(32000).putShort((short)2).putShort((short)16).put(new byte[]{'d','a','t','a'}).putInt(data.length);
                try(FileOutputStream out=openFileOutput("sample.wav",MODE_PRIVATE)) { out.write(header.array()); out.write(data); }
                runOnUiThread(() -> status.setText(String.format(Locale.ROOT,"Сохранено %.1f сек, PCM16/16000/mono. Файл в закрытом каталоге приложения.",data.length/32000.0)));
            } catch(Exception e) { runOnUiThread(() -> status.setText("Запись не выполнена: проверьте разрешение и доступность микрофона")); }
            finally { recording=false; if(mic!=null) { try { mic.stop(); } catch(IllegalStateException ignored) {} mic.release(); } }
        },"audio-capture"); worker.start();
    }
    @Override protected void onPause() { recording=false; if(tts!=null) tts.stop(); super.onPause(); }
    @Override protected void onDestroy() { recording=false; invalidateTranslation(); if(tts!=null) tts.shutdown(); super.onDestroy(); }
}
