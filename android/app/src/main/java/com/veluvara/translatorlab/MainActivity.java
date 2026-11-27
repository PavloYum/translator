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
    static volatile boolean UI_VISIBLE=false;
    private LinearLayout body;
    private TextView mqttStatus, diagnosticReport, connectionBadge;
    private final java.util.List<View> pages=new java.util.ArrayList<>();
    private final java.util.List<Button> tabs=new java.util.ArrayList<>();
    private SharedPreferences.OnSharedPreferenceChangeListener preferenceListener;
    private static final int INK=0xff183c36, GREEN=0xff246b61, MUTED=0xff5f746d, BG=0xfff3f6f4;
    private TextView status;
    private EditText text;
    private Spinner languages, voices, sourceLanguage;
    private TextView output, translationStatus;
    private CheckBox mobileModels;
    private Switch automatic;
    private CheckBox autoSpeak, offlineSpeech;
    private ImageButton conversationButton;
    private android.speech.SpeechRecognizer recognizer;
    private boolean conversation=false;
    private long recognitionEpoch=0, utteranceEpoch=0;
    private String activeUtterance="";
    private final android.os.Handler conversationHandler=new android.os.Handler(android.os.Looper.getMainLooper());
    private final android.os.Handler autoHandler=new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean resumed=false, autoPending=false;
    private final Runnable autoTranslate=() -> { if(resumed && autoPending && automatic.isChecked()) { autoPending=false; translate(); } };
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
        super.onCreate(state); prefs=getSharedPreferences("voice",MODE_PRIVATE);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(BG);setContentView(root);
        root.setOnApplyWindowInsetsListener((view,insets) -> { view.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());return insets.consumeSystemWindowInsets(); });
        LinearLayout header=new LinearLayout(this);header.setOrientation(LinearLayout.VERTICAL);header.setPadding(dp(22),dp(16),dp(22),dp(10));root.addView(header);body=header;
        TextView brand=label("TRANSLATOR  /  0.6.2");brand.setTextSize(12);brand.setLetterSpacing(.13f);brand.setTextColor(GREEN);
        TextView title=label("Понимать друг друга");title.setTextSize(27);title.setTypeface(null,android.graphics.Typeface.BOLD);
        connectionBadge=label("Связь с Pi выключена");connectionBadge.setTextSize(13);
        android.widget.FrameLayout container=new android.widget.FrameLayout(this);root.addView(container,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout translatePage=page(container);body=translatePage;
        LinearLayout direction=card(translatePage);body=direction;
        smallTitle("НАПРАВЛЕНИЕ");label("Слева — язык речи · справа — язык перевода").setTextSize(13);
        LinearLayout pair=new LinearLayout(this);pair.setOrientation(LinearLayout.HORIZONTAL);direction.addView(pair);
        String[] names={"Русский","Deutsch","English"};
        sourceLanguage=new Spinner(this);languages=new Spinner(this);
        for(Spinner spinner:new Spinner[]{sourceLanguage,languages}) { spinner.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,names));pair.addView(spinner,new LinearLayout.LayoutParams(0,dp(52),1)); }
        sourceLanguage.setSelection(prefs.getInt("source_index",0));languages.setSelection(prefs.getInt("target_index",1));
        button("⇄  Поменять языки", () -> { int a=sourceLanguage.getSelectedItemPosition(),b=languages.getSelectedItemPosition();sourceLanguage.setSelection(b);languages.setSelection(a); });
        LinearLayout input=card(translatePage);body=input;smallTitle("ВАША ФРАЗА");
        text=new EditText(this);text.setTextSize(19);text.setTextColor(INK);text.setHint("Введите текст или скажите фразу…");text.setMinLines(3);text.setGravity(android.view.Gravity.TOP);text.setSaveEnabled(false);text.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(3000)});input.addView(text,new LinearLayout.LayoutParams(-1,-2));
        automatic=new Switch(this);automatic.setText("Переводить автоматически");automatic.setTextColor(INK);automatic.setChecked(prefs.getBoolean("auto_translate",true));input.addView(automatic);
        conversationButton=iconAction("Начать разговор",R.drawable.action_mic,() -> {if(conversation){stopConversation();invalidateTranslation();status.setText("Разговор остановлен");}else requestConversation();});
        iconAction("Одна фраза",R.drawable.action_phrase,this::dictate);
        iconAction("Перевести с Google",R.drawable.action_translate,this::translate);
        label("Микрофон слева — режим разговора. Удерживайте значок для подсказки.").setTextSize(13);
        translationStatus=label("Первый перевод потребует загрузки моделей");translationStatus.setTextSize(13);translationStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        LinearLayout result=card(translatePage);body=result;smallTitle("ПЕРЕВОД");output=label("");output.setHint("Здесь появится перевод");output.setHintTextColor(MUTED);output.setTextSize(23);output.setMinHeight(dp(76));output.setTextIsSelectable(true);output.setSaveEnabled(false);
        ImageView attribution=new ImageView(this);attribution.setImageResource(R.drawable.google_translate_attribution);attribution.setAdjustViewBounds(true);attribution.setContentDescription("Powered by Google Translate");result.addView(attribution,new LinearLayout.LayoutParams(-2,-2));
        autoSpeak=new CheckBox(this);autoSpeak.setText("Озвучивать перевод автоматически");autoSpeak.setTextColor(INK);autoSpeak.setChecked(prefs.getBoolean("auto_speak",false));result.addView(autoSpeak);autoSpeak.setOnCheckedChangeListener((v,enabled)->prefs.edit().putBoolean("auto_speak",enabled).apply());
        primary("Озвучить перевод",this::speak);
        button("Копировать перевод",() -> { if(output.length()>0) { ((android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(android.content.ClipData.newPlainText("Перевод",output.getText()));Toast.makeText(this,"Перевод скопирован",Toast.LENGTH_SHORT).show(); } });
        button("Остановить",() -> { stopConversation(); if(tts!=null) tts.stop();recording=false;invalidateTranslation();translationStatus.setText("Остановлено"); });
        iconAction("Поделиться переводом",R.drawable.action_share,() -> {if(output.length()>0){Intent share=new Intent(Intent.ACTION_SEND);share.setType("text/plain");share.putExtra(Intent.EXTRA_TEXT,output.getText().toString());startActivity(Intent.createChooser(share,"Поделиться переводом"));}});
        label("Перевод выполняется на телефоне. Голосовой ввод может использовать интернет.").setTextSize(13);

        LinearLayout piPage=page(container);body=piPage;
        LinearLayout health=card(piPage);body=health;smallTitle("СВЯЗЬ С RASPBERRY PI");mqttStatus=label("");mqttStatus.setTextSize(21);mqttStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        label("Связь остаётся включённой при переходе в чат. Постоянное уведомление показывает, что сервис работает.").setTextSize(15);
        primary("Запустить самопроверку",() -> { if(prefs.getBoolean("mqtt_enabled",false)) startDiagnostics(DiagnosticsService.TEST);else mqttStatus.setText("Сначала включите связь в настройках"); });
        button("Отключить фоновую связь",this::stopDiagnostics);
        LinearLayout report=card(piPage);body=report;smallTitle("ПОСЛЕДНЯЯ САМОПРОВЕРКА");diagnosticReport=label("Проверок пока нет");diagnosticReport.setTextSize(16);
        label("Проверяются модели RU → DE, готовность голоса и выполнение тестового перевода. Микрофон и динамик удалённо не включаются.").setTextSize(14);
        button("Настройки работы в фоне",() -> { try { startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,android.net.Uri.parse("package:"+getPackageName()))); } catch(Exception e) { Toast.makeText(this,"Откройте настройки Android → Приложения → Translator",Toast.LENGTH_LONG).show(); } });
        label("На Xiaomi разрешите уведомления и фоновую работу. После принудительной остановки или перезагрузки откройте Translator снова.").setTextSize(14);

        LinearLayout settingsPage=page(container);
        LinearLayout voiceCard=card(settingsPage);body=voiceCard;smallTitle("ГОЛОС ПЕРЕВОДА");voices=new Spinner(this);voiceCard.addView(voices,new LinearLayout.LayoutParams(-1,dp(52)));
        rate=slider("Скорость","rate");pitch=slider("Высота тона","pitch");button("Сбросить голосовые настройки",() -> {rate.setProgress(50);pitch.setProgress(50);});label("Голос, скорость и тон сохраняются отдельно для каждого языка.").setTextSize(13);
        LinearLayout models=card(settingsPage);body=models;smallTitle("ЯЗЫКОВЫЕ МОДЕЛИ");
        offlineSpeech=new CheckBox(this);offlineSpeech.setText("Предпочитать офлайн-распознавание речи");offlineSpeech.setChecked(prefs.getBoolean("offline_speech",false));models.addView(offlineSpeech);offlineSpeech.setOnCheckedChangeListener((v,enabled)->{prefs.edit().putBoolean("offline_speech",enabled).apply();if(conversation)scheduleListening();});
        label("Если речевой пакет Deutsch не установлен, оставьте выключенным и подключите интернет. Модели перевода и распознавания речи — разные.").setTextSize(13);
        mobileModels=new CheckBox(this);mobileModels.setText("Разрешить загрузку через мобильный интернет");mobileModels.setTextColor(INK);models.addView(mobileModels);label("По умолчанию модели загружаются через Wi-Fi. После загрузки текстовый перевод доступен без сети.").setTextSize(14);
        LinearLayout access=card(settingsPage);body=access;smallTitle("ДОСТУП К PI");
        EditText mqttUser=new EditText(this);mqttUser.setText(prefs.getString("mqtt_user","translator-phone"));mqttUser.setHint("Имя MQTT");mqttUser.setSingleLine(true);access.addView(mqttUser);
        EditText mqttPassword=new EditText(this);mqttPassword.setHint("Пароль · пусто = сохранённый");mqttPassword.setInputType(129);mqttPassword.setSaveEnabled(false);access.addView(mqttPassword);
        primary("Сохранить и включить связь",() -> {
            try {
                if(mqttPassword.length()>0) SecretStore.save(this,mqttPassword.getText().toString());
                if(SecretStore.load(this).isEmpty()) {Toast.makeText(this,"Введите пароль MQTT",Toast.LENGTH_LONG).show();return;}
                prefs.edit().putString("mqtt_user",mqttUser.getText().toString().trim()).putBoolean("mqtt_enabled",true).apply();mqttPassword.setText("");
                requestNotifications();startDiagnostics(DiagnosticsService.RECONNECT);showPage(1);
            } catch(Exception e) { Toast.makeText(this,"Не удалось сохранить доступ",Toast.LENGTH_LONG).show(); }
        });
        LinearLayout lab=card(settingsPage);body=lab;smallTitle("АУДИОЛАБОРАТОРИЯ");button("Записать WAV · до 60 секунд",this::record);button("Удалить запись",() -> { if(worker!=null && worker.isAlive()) {status.setText("Сначала остановите запись");return;} File f=new File(getFilesDir(),"sample.wav");status.setText(!f.exists()||f.delete()?"Запись удалена":"Ошибка удаления"); });button("Остановить запись",() -> recording=false);
        button("О переводе и конфиденциальности",() -> new AlertDialog.Builder(this).setTitle("О приложении")
            .setMessage("Текстовый перевод: Google Translate через ML Kit (cloud.google.com/translate). Модели загружаются из Google. SDK может передавать служебные сведения. Системный голосовой ввод может использовать облако. История текста не сохраняется. MQTT передаёт только технический отчёт. Микрофон и динамик в фоне не включаются.\n\nTHIS SERVICE MAY CONTAIN TRANSLATIONS POWERED BY GOOGLE. GOOGLE DISCLAIMS ALL WARRANTIES RELATED TO THE TRANSLATIONS, EXPRESS OR IMPLIED, INCLUDING ANY WARRANTIES OF ACCURACY, RELIABILITY, AND ANY IMPLIED WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.")
            .setPositiveButton("Понятно",null).show());
        body=root;status=label("Инициализация голоса…");status.setTextSize(12);status.setMaxLines(2);status.setPadding(dp(20),dp(4),dp(20),dp(4));status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        LinearLayout nav=new LinearLayout(this);nav.setPadding(dp(12),dp(6),dp(12),dp(6));root.addView(nav);
        String[] tabNames={"Перевод","Связь с Pi","Настройки"};for(int i=0;i<3;i++){ final int index=i;Button tab=new Button(this);tab.setText(tabNames[i]);tab.setTextSize(12);tab.setAllCaps(false);nav.addView(tab,new LinearLayout.LayoutParams(0,dp(54),1));tabs.add(tab);tab.setOnClickListener(v -> showPage(index)); }
        showPage(0);
        if(prefs.getBoolean("mqtt_enabled",false) && !prefs.getBoolean("notification_asked",false))requestNotifications();
        languages.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onNothingSelected(AdapterView<?> p){}public void onItemSelected(AdapterView<?> p,View v,int pos,long id){prefs.edit().putInt("target_index",pos).apply();translationInputChanged();loadVoices();}});
        sourceLanguage.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onNothingSelected(AdapterView<?> p){}public void onItemSelected(AdapterView<?> p,View v,int pos,long id){prefs.edit().putInt("source_index",pos).apply();translationInputChanged();}});
        voices.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onNothingSelected(AdapterView<?> p){}public void onItemSelected(AdapterView<?> p,View v,int pos,long id){if(pos<available.size()) prefs.edit().putString(tag()+"voice",available.get(pos).getName()).apply();}});
        text.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){}public void onTextChanged(CharSequence s,int a,int b,int c){translationInputChanged();}public void afterTextChanged(Editable e){}});
        automatic.setOnCheckedChangeListener((button,enabled) -> { prefs.edit().putBoolean("auto_translate",enabled).apply();cancelAutomatic();if(enabled)translationInputChanged();else {stopConversation();translationStatus.setText("Ручной режим · нажмите «Перевести с Google»");} });
        preferenceListener=(shared,key) -> { if(key!=null && key.startsWith("mqtt_")) runOnUiThread(this::refreshDiagnostics); };prefs.registerOnSharedPreferenceChangeListener(preferenceListener);refreshDiagnostics();
        tts=new TextToSpeech(this,resultCode -> {ready=resultCode==TextToSpeech.SUCCESS;if(ready){
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener(){
                public void onStart(String id){}
                public void onDone(String id){runOnUiThread(()->speechFinished(id,false));}
                public void onError(String id){runOnUiThread(()->speechFinished(id,true));}
            });loadVoices();}else status.setText("Системный голос недоступен");});
    }
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private android.graphics.drawable.GradientDrawable background(int color,int radius){android.graphics.drawable.GradientDrawable d=new android.graphics.drawable.GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private LinearLayout page(android.widget.FrameLayout container){ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);container.addView(scroll,new android.widget.FrameLayout.LayoutParams(-1,-1));pages.add(scroll);LinearLayout content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(dp(16),dp(6),dp(16),dp(18));scroll.addView(content);return content;}
    private LinearLayout card(LinearLayout parent){LinearLayout c=new LinearLayout(this);c.setOrientation(LinearLayout.VERTICAL);c.setPadding(dp(18),dp(12),dp(18),dp(16));c.setBackground(background(0xffffffff,22));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=dp(14);parent.addView(c,lp);return c;}
    private void showPage(int index){for(int i=0;i<pages.size();i++){pages.get(i).setVisibility(i==index?View.VISIBLE:View.GONE);Button tab=tabs.get(i);tab.setTextColor(i==index?0xffffffff:GREEN);tab.setBackground(background(i==index?GREEN:0xffe4ece7,16));tab.setSelected(i==index);}}
    private TextView label(String value){TextView v=new TextView(this);v.setText(value);v.setTextColor(INK);v.setTextSize(17);v.setLineSpacing(dp(3),1);v.setPadding(0,dp(6),0,dp(6));body.addView(v);return v;}
    private void smallTitle(String title){TextView v=label(title);v.setTextColor(MUTED);v.setTextSize(11);v.setLetterSpacing(.1f);}
    private void button(String title,Runnable action){makeButton(title,action,false);}
    private void primary(String title,Runnable action){makeButton(title,action,true);}
    private void makeButton(String title,Runnable action,boolean primary){
        int icon=R.drawable.action_settings;
        if(title.contains("языки"))icon=R.drawable.action_swap;
        else if(title.contains("Озвучить"))icon=R.drawable.action_speaker;
        else if(title.contains("Копировать"))icon=R.drawable.action_copy;
        else if(title.contains("Остановить") || title.contains("Отключить"))icon=R.drawable.action_stop;
        else if(title.contains("самопроверку") || title.contains("Сохранить"))icon=R.drawable.action_check;
        else if(title.contains("Сбросить"))icon=R.drawable.action_refresh;
        else if(title.contains("Записать"))icon=R.drawable.action_mic;
        else if(title.contains("Удалить"))icon=R.drawable.action_trash;
        else if(title.contains("конфиденциальности"))icon=R.drawable.action_info;
        iconAction(title,icon,action);
    }
    private ImageButton iconAction(String title,int icon,Runnable action){
        LinearLayout row=null;
        if(body.getChildCount()>0){View last=body.getChildAt(body.getChildCount()-1);if("actions".equals(last.getTag()) && ((LinearLayout)last).getChildCount()<4)row=(LinearLayout)last;}
        if(row==null){row=new LinearLayout(this);row.setTag("actions");row.setOrientation(LinearLayout.HORIZONTAL);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(52));lp.topMargin=dp(6);body.addView(row,lp);}
        ImageButton button=new ImageButton(this);button.setImageResource(icon);button.setImageTintList(android.content.res.ColorStateList.valueOf(MUTED));button.setPadding(dp(12),dp(12),dp(12),dp(12));
        button.setContentDescription(title);button.setTooltipText(title);button.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x33246b61),background(android.graphics.Color.TRANSPARENT,24),null));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(dp(48),dp(48));lp.rightMargin=dp(12);row.addView(button,lp);button.setOnClickListener(v -> action.run());return button;
    }
    private void updateConversationIcon(boolean active){
        if(conversationButton==null)return;
        String title=active?"Остановить разговор":"Начать разговор";
        conversationButton.setContentDescription(title);conversationButton.setTooltipText(title);conversationButton.setSelected(active);
        conversationButton.setImageResource(active?R.drawable.action_stop:R.drawable.action_mic);
        conversationButton.setImageTintList(android.content.res.ColorStateList.valueOf(active?0xffffffff:MUTED));
        conversationButton.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x33246b61),background(active?GREEN:android.graphics.Color.TRANSPARENT,24),null));
    }
    private String tag(){return tags[languages.getSelectedItemPosition()];}
    private void requestNotifications(){prefs.edit().putBoolean("notification_asked",true).apply();if(android.os.Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},22);}
    private void startDiagnostics(String action){
        if(!prefs.getBoolean("mqtt_enabled",false))return;
        try{startForegroundService(new Intent(this,DiagnosticsService.class).setAction(action));}
        catch(Exception e){prefs.edit().putString("mqtt_status","Не удалось запустить фоновую связь. Откройте приложение и попробуйте снова.").apply();}
    }
    private void stopDiagnostics(){prefs.edit().putBoolean("mqtt_enabled",false).putString("mqtt_status","Связь выключена").apply();stopService(new Intent(this,DiagnosticsService.class));refreshDiagnostics();}
    private void refreshDiagnostics(){
        boolean enabled=prefs.getBoolean("mqtt_enabled",false);String connection=enabled?prefs.getString("mqtt_status","Ожидание подключения…"):"Связь выключена";
        mqttStatus.setText(connection);connectionBadge.setText(enabled?"●  "+connection:"○  Связь с Pi выключена");
        String raw=prefs.getString("mqtt_report","");if(raw.isEmpty())return;
        try{org.json.JSONObject r=new org.json.JSONObject(raw);String state=r.optString("status");String summary=state.equals("ok")?"Проверка пройдена":state.equals("limited")?"Перевод работает · проверьте голос":state.equals("models_missing")?"Нужно загрузить модели RU и DE":"Результат: "+state;
            String when=java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT,java.text.DateFormat.SHORT).format(new java.util.Date(prefs.getLong("mqtt_report_at",0)));
            diagnosticReport.setText(summary+"\n"+when+"\nМодели: "+r.optString("installed_models","—")+"\nГолос: "+(r.optBoolean("tts_ready")?"готов":"не готов")+(r.has("translation_ms")?"\nПеревод: "+r.optLong("translation_ms")+" мс":""));
        }catch(Exception ignored){}
    }
    @Override protected void onStart(){super.onStart();UI_VISIBLE=true;startDiagnostics("");refreshDiagnostics();}
    @Override protected void onStop(){UI_VISIBLE=false;super.onStop();}
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
        if(conversation && (!ready || available.isEmpty())) {stopConversation();status.setText("Установите голос выбранного языка или выключите автоозвучку");return;}
        if(recording) { status.setText("Сначала остановите запись"); return; }
        if(output.getText().length()==0) { status.setText("Сначала переведите фразу"); return; }
        if(!ready || available.isEmpty()) { status.setText("Нет доступного голоса"); return; }
        int choice=voices.getSelectedItemPosition(); if(choice<0 || choice>=available.size()){stopConversation();return;}
        if(tts.setVoice(available.get(choice))==TextToSpeech.ERROR || tts.setSpeechRate(0.5f+rate.getProgress()/100f)==TextToSpeech.ERROR || tts.setPitch(0.5f+pitch.getProgress()/100f)==TextToSpeech.ERROR) {
            stopConversation();status.setText("Движок не принял настройки"); return;
        }
        pauseRecognition();
        activeUtterance="translation-"+(++utteranceEpoch);
        if(tts.speak(output.getText().toString(),TextToSpeech.QUEUE_FLUSH,null,activeUtterance)==TextToSpeech.ERROR) {stopConversation();status.setText("Не удалось воспроизвести голос");}
    }
    private void cancelAutomatic() {
        autoHandler.removeCallbacks(autoTranslate);autoPending=false;
    }
    private void translationInputChanged() {
        if(conversation)pauseRecognition();
        invalidateTranslation();
        if(automatic.isChecked() && !text.getText().toString().trim().isEmpty()) {
            autoPending=true;
            translationStatus.setText("Переведу после паузы во вводе…");
            if(resumed)autoHandler.postDelayed(autoTranslate,700);
        } else if(conversation) {scheduleListening();}

    }
    @Override protected void onResume() {
        super.onResume();resumed=true;
        if(autoPending) {autoHandler.removeCallbacks(autoTranslate);autoHandler.postDelayed(autoTranslate,700);}
    }
    private void invalidateTranslation() {
        cancelAutomatic();
        activeUtterance="";generation++; translating=false;
        if(tts!=null) tts.stop();
        if(output!=null) output.setText("");
        if(translationStatus!=null) translationStatus.setText("Готов к переводу");
        if(translator!=null) { translator.close(); translator=null; }
    }
    private void translate() {
        cancelAutomatic();
        if(recording) { translationStatus.setText("Сначала остановите запись"); return; }
        if(translating){translationStatus.setText("Перевод ещё выполняется. Остановите его кнопкой ■, чтобы повторить.");return;}
        String input=text.getText().toString().trim();
        if(input.isEmpty()) { translationStatus.setText("Введите текст"); return; }
        if(conversation)pauseRecognition();
        invalidateTranslation();
        int from=sourceLanguage.getSelectedItemPosition(),to=languages.getSelectedItemPosition();
        if(from==to) { output.setText(input); translationStatus.setText("Выбран один язык — текст без перевода");translationComplete(); return; }
        final long request= generation;
        translating=true;
        autoHandler.postDelayed(()->{if(request==generation && translating){invalidateTranslation();stopConversation();translationStatus.setText("Истекло время ожидания моделей/перевода. Проверьте Wi-Fi или разрешите загрузку моделей через мобильную сеть в настройках.");}},90000);
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
                translationStatus.setText("Переведено на телефоне за "+(android.os.SystemClock.elapsedRealtime()-started)+" мс (без загрузки моделей)");translationComplete();
            }).addOnFailureListener(error -> translationFailed(request));
        }).addOnFailureListener(error -> translationFailed(request));
    }
    private void translationFailed(long request) {
        if(request!=generation || isDestroyed()) return;
        translating=false;stopConversation();
        translationStatus.setText("Перевод не выполнен. Проверьте интернет для загрузки моделей и свободное место; затем повторите.");
    }
    private void translationComplete() {
        if(!resumed)return;
        if(autoSpeak.isChecked())speak();else scheduleListening();
    }
    private void speechFinished(String id,boolean error) {
        if(!id.equals(activeUtterance))return;
        activeUtterance="";
        if(error){stopConversation();status.setText("Ошибка озвучки. Проверьте установленный голос.");}
        else scheduleListening();
    }
    private void pauseRecognition() {
        recognitionEpoch++;
        conversationHandler.removeCallbacksAndMessages(null);
        if(recognizer!=null){recognizer.cancel();recognizer.destroy();recognizer=null;}
    }
    private void stopConversation() {
        conversation=false;pauseRecognition();
        updateConversationIcon(false);
        getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
    private void requestConversation() {
        if(recording){status.setText("Сначала остановите запись WAV");return;}
        if(!prefs.getBoolean("speech_consent",false)) {
            new AlertDialog.Builder(this).setTitle("Режим разговора")
                .setMessage("Микрофон будет слушать фразы до остановки или выхода из приложения. Системный сервис Android может отправлять речь своему провайдеру. При озвучке микрофон приостанавливается.")
                .setNegativeButton("Отмена",null).setPositiveButton("Включить",(d,w)->{prefs.edit().putBoolean("speech_consent",true).apply();requestConversation();}).show();return;
        }
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},23);return;}
        if(!android.speech.SpeechRecognizer.isRecognitionAvailable(this)){status.setText("Установите системный сервис распознавания речи");return;}
        invalidateTranslation();automatic.setChecked(true);conversation=true;
        updateConversationIcon(true);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        scheduleListening();
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){
        super.onRequestPermissionsResult(request,permissions,results);
        if(request==23 && results.length>0){if(results[0]==PackageManager.PERMISSION_GRANTED)requestConversation();else status.setText("Для разговора разрешите доступ к микрофону");}
    }
    private void scheduleListening(){
        if(!conversation || !resumed)return;
        pauseRecognition();
        final long epoch=recognitionEpoch;
        conversationHandler.postDelayed(()->{if(conversation && resumed && epoch==recognitionEpoch)listenPhrase();},600);
    }
    private void listenPhrase(){
        if(!conversation || !resumed)return;
        final long epoch=++recognitionEpoch;
        final int language=sourceLanguage.getSelectedItemPosition();
        recognizer=android.speech.SpeechRecognizer.createSpeechRecognizer(this);
        recognizer.setRecognitionListener(new android.speech.RecognitionListener(){
            private boolean current(){return conversation && resumed && epoch==recognitionEpoch;}
            public void onReadyForSpeech(Bundle b){if(current())status.setText("Слушаю: "+tags[language]+" · говорите");}
            public void onBeginningOfSpeech(){}
            public void onRmsChanged(float rms){}
            public void onBufferReceived(byte[] b){}
            public void onEndOfSpeech(){if(current())status.setText("Распознаю фразу…");}
            public void onPartialResults(Bundle b){}
            public void onEvent(int event,Bundle b){}
            public void onError(int error){
                if(!current())return;
                if(error==android.speech.SpeechRecognizer.ERROR_NO_MATCH || error==android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT){scheduleListening();return;}
                stopConversation();status.setText(SpeechErrors.message(error,tags[language]));
            }
            public void onResults(Bundle b){
                if(!current())return;
                ArrayList<String> phrases=b.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION);
                pauseRecognition();
                if(language!=sourceLanguage.getSelectedItemPosition() || phrases==null || phrases.isEmpty() || phrases.get(0).trim().isEmpty()){scheduleListening();return;}
                text.setText(phrases.get(0));translate();
            }
        });
        Intent intent=new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE,tags[language]);
        intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE,prefs.getBoolean("offline_speech",false));
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS,1);
        try{recognizer.startListening(intent);
            conversationHandler.postDelayed(()->{if(conversation && epoch==recognitionEpoch){stopConversation();status.setText("Сервис речи не ответил за 45 секунд. Повторите запуск микрофона.");}},45000);
        }catch(RuntimeException e){stopConversation();status.setText("Не удалось включить распознавание речи");}
    }
    private void dictate() {
        stopConversation();
        if(recording) { status.setText("Сначала остановите запись WAV"); return; }
        new AlertDialog.Builder(this).setTitle("Голосовой ввод Android")
            .setMessage("Системный сервис распознавания может передать речь своему провайдеру. Офлайн-режим зависит от телефона. Продолжить?")
            .setNegativeButton("Отмена",null).setPositiveButton("Продолжить",(dialog,which) -> {
                if(tts!=null) tts.stop(); invalidateTranslation();
                speechLanguage=sourceLanguage.getSelectedItemPosition();
                Intent intent=new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
                intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
                intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE,tags[speechLanguage]);
                intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE,prefs.getBoolean("offline_speech",false));
                intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS,1);
                try { startActivityForResult(intent,20); }
                catch(android.content.ActivityNotFoundException e) { status.setText("Голосовой ввод не установлен. Можно ввести текст вручную."); }
            }).show();
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(request==20 && result==RESULT_OK && data!=null && speechLanguage==sourceLanguage.getSelectedItemPosition()) {
            ArrayList<String> phrases=data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if(phrases!=null && !phrases.isEmpty()) { text.setText(phrases.get(0)); if(!automatic.isChecked())translationStatus.setText("Проверьте распознанную фразу и нажмите «Перевести»"); }
        }
    }
    private void record() {
        stopConversation();
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
    @Override protected void onPause() {stopConversation();activeUtterance="";resumed=false;autoHandler.removeCallbacks(autoTranslate); recording=false; if(tts!=null) tts.stop(); super.onPause(); }
    @Override protected void onDestroy() { recording=false; invalidateTranslation(); if(tts!=null) tts.shutdown(); if(preferenceListener!=null) prefs.unregisterOnSharedPreferenceChangeListener(preferenceListener);super.onDestroy(); }
}
