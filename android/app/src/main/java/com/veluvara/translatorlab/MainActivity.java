package com.veluvara.translatorlab;

import android.Manifest;
import android.app.Activity;
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

/** Foreground-only measurement lab. No network permission or cloud integration. */
public class MainActivity extends Activity {
    private LinearLayout body;
    private TextView status;
    private EditText text;
    private Spinner languages, voices;
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
        label("Translator · аудиолаборатория");
        label("Локальная проверка микрофона и озвучки. Перевод пока не подключён. Запись до 60 секунд, только при открытом приложении.");
        languages = new Spinner(this); languages.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, tags)); body.addView(languages);
        voices = new Spinner(this); body.addView(voices);
        text = new EditText(this); text.setText(examples[0]); text.setMinLines(2); body.addView(text);
        rate = slider("Скорость", "rate"); pitch = slider("Высота тона", "pitch");
        button("Прослушать", this::speak);
        button("Остановить", () -> { if(tts!=null) tts.stop(); recording=false; });
        button("Сбросить настройки голоса", () -> { rate.setProgress(50); pitch.setProgress(50); });
        button("Записать образец", this::record);
        button("Удалить образец", () -> {
            if(worker!=null && worker.isAlive()) { status.setText("Сначала остановите запись"); return; }
            File file=new File(getFilesDir(), "sample.wav");
            status.setText(!file.exists() || file.delete() ? "Образец удалён" : "Не удалось удалить файл");
        });
        status=label("Инициализация голоса…");
        languages.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onNothingSelected(AdapterView<?> parent) {}
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if(tts!=null) tts.stop(); text.setText(examples[position]); loadVoices();
            }
        });
        voices.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onNothingSelected(AdapterView<?> parent) {}
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if(position<available.size()) prefs.edit().putString(tag()+"voice", available.get(position).getName()).apply();
            }
        });
        tts=new TextToSpeech(this, result -> {
            ready=result==TextToSpeech.SUCCESS;
            if(ready) loadVoices(); else status.setText("Системная озвучка недоступна");
        });
    }
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
        status.setText(available.isEmpty()?"Установите офлайн-голос этого языка в настройках Android":"Голос готов. Данные не отправляются в облако приложением.");
    }
    private void speak() {
        if(recording) { status.setText("Сначала остановите запись"); return; }
        if(!ready || available.isEmpty()) { status.setText("Нет доступного голоса"); return; }
        int choice=voices.getSelectedItemPosition(); if(choice<0 || choice>=available.size()) return;
        if(tts.setVoice(available.get(choice))==TextToSpeech.ERROR || tts.setSpeechRate(0.5f+rate.getProgress()/100f)==TextToSpeech.ERROR || tts.setPitch(0.5f+pitch.getProgress()/100f)==TextToSpeech.ERROR) {
            status.setText("Движок не принял настройки"); return;
        }
        if(tts.speak(text.getText().toString(),TextToSpeech.QUEUE_FLUSH,null,"preview")==TextToSpeech.ERROR) status.setText("Не удалось воспроизвести голос");
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
    @Override protected void onDestroy() { recording=false; if(tts!=null) tts.shutdown(); super.onDestroy(); }
}
