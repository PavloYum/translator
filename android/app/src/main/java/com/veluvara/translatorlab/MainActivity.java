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
    private LinearLayout body, journalEntries;
    private DialogueStore journal;
    private final java.util.concurrent.ExecutorService journalWorker=java.util.concurrent.Executors.newSingleThreadExecutor();
    private String dialogueSession;
    private long journalBefore=Long.MAX_VALUE,journalEpoch=0;
    private boolean journalLoading=false;
    private Button journalMore;
    private TextView journalTitle, modelStatus, speechModels, voiceModels;
    private android.speech.SpeechRecognizer modelProbe;
    private final android.os.Handler modelHandler=new android.os.Handler(android.os.Looper.getMainLooper());
    private int currentPage=0;
    private LinearLayout languageRows, pickerRows;
    private Button sourceChoice,targetChoice;
    private Spinner pickerTarget;
    private final Set<String> installedTranslations=new HashSet<>(), downloadingTranslations=new HashSet<>();
    private boolean translationModelsKnown=false;
    private final String[] languageNames={"Русский","Немецкий","Английский"};
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
        journal=new DialogueStore(this);
        dialogueSession=prefs.getString("dialogue_session","");
        if(dialogueSession.isEmpty())newDialogue();
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(BG);setContentView(root);
        root.setOnApplyWindowInsetsListener((view,insets) -> { view.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());return insets.consumeSystemWindowInsets(); });
        LinearLayout header=new LinearLayout(this);header.setOrientation(LinearLayout.VERTICAL);header.setPadding(dp(22),dp(16),dp(22),dp(10));root.addView(header);body=header;
        TextView brand=label("TRANSLATOR  /  0.8.1");brand.setTextSize(12);brand.setLetterSpacing(.13f);brand.setTextColor(GREEN);
        TextView title=label("Понимать друг друга");title.setTextSize(27);title.setTypeface(null,android.graphics.Typeface.BOLD);
        connectionBadge=label("Связь с Pi выключена");connectionBadge.setTextSize(13);
        android.widget.FrameLayout container=new android.widget.FrameLayout(this);root.addView(container,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout translatePage=page(container);body=translatePage;
        LinearLayout direction=card(translatePage);body=direction;
        smallTitle("НАПРАВЛЕНИЕ");label("Слева — язык речи · справа — язык перевода").setTextSize(13);
        LinearLayout pair=new LinearLayout(this);pair.setOrientation(LinearLayout.HORIZONTAL);direction.addView(pair);
        String[] names={"Русский","Deutsch","English"};
        sourceLanguage=new Spinner(this);languages=new Spinner(this);
        for(Spinner spinner:new Spinner[]{sourceLanguage,languages}) { spinner.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,names));spinner.setVisibility(View.GONE);direction.addView(spinner,new LinearLayout.LayoutParams(0,0)); }
        sourceLanguage.setSelection(prefs.getInt("source_index",0));languages.setSelection(prefs.getInt("target_index",1));
        sourceChoice=new Button(this);targetChoice=new Button(this);
        for(Button choice:new Button[]{sourceChoice,targetChoice}){choice.setAllCaps(false);choice.setTextSize(16);choice.setTextColor(INK);choice.setBackground(background(0xffeef0fa,24));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(52),1);lp.setMargins(dp(3),0,dp(3),0);pair.addView(choice,lp);}
        updateLanguageChoices();sourceChoice.setOnClickListener(v->openLanguagePicker(sourceLanguage,"Язык речи"));targetChoice.setOnClickListener(v->openLanguagePicker(languages,"Язык перевода"));
        button("⇄  Поменять языки", () -> { int a=sourceLanguage.getSelectedItemPosition(),b=languages.getSelectedItemPosition();sourceLanguage.setSelection(b);languages.setSelection(a);applyLanguageSelection(); });
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
        offlineSpeech=new CheckBox(this);offlineSpeech.setText("Распознавать речь на устройстве (офлайн)");offlineSpeech.setChecked(prefs.getBoolean("offline_speech",false));models.addView(offlineSpeech);offlineSpeech.setOnCheckedChangeListener((v,enabled)->{prefs.edit().putBoolean("offline_speech",enabled).apply();if(conversation)scheduleListening();});
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
            .setMessage("Текстовый перевод: Google Translate через ML Kit (cloud.google.com/translate). Модели загружаются из Google. SDK может передавать служебные сведения. Системный голосовой ввод может использовать облако. При включённом журнале фразы и переводы сохраняются только в закрытой базе приложения. Их можно удалить во вкладке «Журнал». Аудио разговора не сохраняется. MQTT передаёт только технический отчёт. Микрофон и динамик в фоне не включаются.\n\nTHIS SERVICE MAY CONTAIN TRANSLATIONS POWERED BY GOOGLE. GOOGLE DISCLAIMS ALL WARRANTIES RELATED TO THE TRANSLATIONS, EXPRESS OR IMPLIED, INCLUDING ANY WARRANTIES OF ACCURACY, RELIABILITY, AND ANY IMPLIED WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.")
            .setPositiveButton("Понятно",null).show());
        LinearLayout journalPage=page(container);body=journalPage;
        smallTitle("ЖУРНАЛ ДИАЛОГОВ");journalTitle=label("Фразы сохраняются на этом телефоне");
        CheckBox saveJournal=new CheckBox(this);saveJournal.setText("Сохранять диалог");saveJournal.setChecked(prefs.getBoolean("save_journal",true));journalPage.addView(saveJournal);
        saveJournal.setOnCheckedChangeListener((v,enabled)->prefs.edit().putBoolean("save_journal",enabled).apply());
        label("Исходная фраза, перевод, языки и время. Новые записи сверху. Записи остаются после закрытия приложения. Аудио не сохраняется.").setTextSize(13);
        iconAction("Новый диалог",R.drawable.action_phrase,()->{stopConversation();invalidateTranslation();text.setText("");newDialogue();Toast.makeText(this,"Начат новый диалог",Toast.LENGTH_SHORT).show();showPage(0);});
        iconAction("Обновить журнал",R.drawable.action_refresh,()->loadJournal(true));
        iconAction("Удалить весь журнал",R.drawable.action_trash,()->confirmDelete(null));
        journalEntries=new LinearLayout(this);journalEntries.setOrientation(LinearLayout.VERTICAL);journalPage.addView(journalEntries);
        journalMore=new Button(this);journalMore.setText("Показать ещё");journalPage.addView(journalMore);journalMore.setOnClickListener(v->loadJournal(false));
        LinearLayout modelPage=page(container);body=modelPage;
        smallTitle("ЯЗЫКОВЫЕ ПАКЕТЫ");
        label("Перевод текста, распознавание речи и озвучка используют разные пакеты.").setTextSize(14);
        modelStatus=label("Проверка моделей перевода…");speechModels=label("Распознавание речи: сведения ещё не запрошены");voiceModels=label("Голоса: проверка…");
        iconAction("Обновить список языков",R.drawable.action_refresh,this::refreshModels);
        smallTitle("ЯЗЫК ПЕРЕВОДА");
        languageRows=new LinearLayout(this);languageRows.setOrientation(LinearLayout.VERTICAL);modelPage.addView(languageRows);renderLanguageRows(languageRows,languages);
        label("Загрузка моделей перевода использует Wi-Fi, если мобильная загрузка не разрешена в настройках.").setTextSize(13);
        button("Запросить речевой пакет выбранного языка",()->downloadSpeech());
        label("Речевой пакет запрашивается для языка слева. После загрузки обновите список. Системный сервис может не предоставлять список или установку пакетов.").setTextSize(13);
        body=root;status=label("Инициализация голоса…");status.setTextSize(12);status.setMaxLines(2);status.setPadding(dp(20),dp(4),dp(20),dp(4));status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        View divider=new View(this);divider.setBackgroundColor(0xffdce4df);root.addView(divider,new LinearLayout.LayoutParams(-1,dp(1)));
        LinearLayout nav=new LinearLayout(this);nav.setBackgroundColor(0xffffffff);nav.setPadding(dp(4),0,dp(4),0);root.addView(nav);
        String[] tabNames={"Перевод","Pi","Настройки","Журнал","Языки"};for(int i=0;i<5;i++){ final int index=i;Button tab=new Button(this);tab.setText(tabNames[i]);tab.setTextSize(11);tab.setPadding(0,0,0,0);tab.setMinWidth(0);tab.setMinimumWidth(0);tab.setAllCaps(false);nav.addView(tab,new LinearLayout.LayoutParams(0,dp(54),1));tabs.add(tab);tab.setOnClickListener(v -> showPage(index)); }
        quietViews(root);showPage(0);
        if(prefs.getBoolean("mqtt_enabled",false) && !prefs.getBoolean("notification_asked",false))requestNotifications();
        languages.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onNothingSelected(AdapterView<?> p){}public void onItemSelected(AdapterView<?> p,View v,int pos,long id){prefs.edit().putInt("target_index",pos).apply();updateLanguageChoices();translationInputChanged();loadVoices();}});
        sourceLanguage.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onNothingSelected(AdapterView<?> p){}public void onItemSelected(AdapterView<?> p,View v,int pos,long id){prefs.edit().putInt("source_index",pos).apply();updateLanguageChoices();translationInputChanged();}});
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
    private void applyLanguageSelection(){
        prefs.edit().putInt("source_index",sourceLanguage.getSelectedItemPosition()).putInt("target_index",languages.getSelectedItemPosition()).apply();
        updateLanguageChoices();translationInputChanged();loadVoices();
    }
    private void updateLanguageChoices(){
        if(sourceChoice==null || targetChoice==null)return;
        sourceChoice.setText(languageNames[sourceLanguage.getSelectedItemPosition()]+" ▾");targetChoice.setText(languageNames[languages.getSelectedItemPosition()]+" ▾");redrawLanguages();
    }
    private void redrawLanguages(){if(languageRows!=null)renderLanguageRows(languageRows,languages);if(pickerRows!=null && pickerTarget!=null)renderLanguageRows(pickerRows,pickerTarget);}
    private void openLanguagePicker(Spinner target,String title){
        LinearLayout content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(dp(12),dp(16),dp(12),dp(16));content.setBackgroundColor(0xfffaf9ff);
        TextView hint=new TextView(this);hint.setText("Доступные языки · пакет перевода справа");hint.setTextColor(MUTED);hint.setPadding(dp(12),0,dp(12),dp(14));content.addView(hint);
        pickerRows=new LinearLayout(this);pickerRows.setOrientation(LinearLayout.VERTICAL);pickerTarget=target;content.addView(pickerRows);renderLanguageRows(pickerRows,target);
        new AlertDialog.Builder(this).setTitle(title).setView(content).setPositiveButton("Готово",null).setOnDismissListener(d->{pickerRows=null;pickerTarget=null;}).show();
        refreshModels();
    }
    private void renderLanguageRows(LinearLayout parent,Spinner target){
        parent.removeAllViews();int selected=target.getSelectedItemPosition();
        // Current selection first, then the remaining supported languages.
        for(int order=0;order<tags.length;order++){
            final int index=order==0?selected:(order<=selected?order-1:order);
            final String code=tags[index].substring(0,2);boolean chosen=index==selected;
            LinearLayout row=new LinearLayout(this);row.setGravity(android.view.Gravity.CENTER_VERTICAL);row.setPadding(dp(6),0,dp(6),0);row.setBackground(background(chosen?0xffdfe4fa:0x00000000,28));LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(-1,dp(60));rp.bottomMargin=dp(6);parent.addView(row,rp);
            Button select=new Button(this);select.setAllCaps(false);select.setText((chosen?"✓   ":"     ")+languageNames[index]);select.setTextSize(19);select.setTextColor(INK);select.setGravity(android.view.Gravity.CENTER_VERTICAL|android.view.Gravity.START);select.setPadding(dp(12),0,0,0);select.setBackgroundColor(android.graphics.Color.TRANSPARENT);select.setContentDescription(languageNames[index]+(chosen?", выбран":""));select.setSelected(chosen);select.setSoundEffectsEnabled(false);row.addView(select,new LinearLayout.LayoutParams(0,-1,1));select.setOnClickListener(v->{target.setSelection(index);applyLanguageSelection();});
            ImageButton download=new ImageButton(this);boolean installed=installedTranslations.contains(code),pending=downloadingTranslations.contains(code);
            download.setImageResource(installed?R.drawable.action_check:pending?R.drawable.action_refresh:R.drawable.action_download);download.setImageTintList(android.content.res.ColorStateList.valueOf(MUTED));download.setPadding(dp(12),dp(12),dp(12),dp(12));download.setBackgroundColor(android.graphics.Color.TRANSPARENT);download.setSoundEffectsEnabled(false);
            String description=installed?"Пакет перевода загружен":pending?"Загрузка или ожидание Wi-Fi":translationModelsKnown?"Скачать пакет перевода":"Проверить наличие пакета";
            download.setContentDescription(languageNames[index]+": "+description);download.setTooltipText(description);row.addView(download,new LinearLayout.LayoutParams(dp(48),dp(48)));
            download.setOnClickListener(v->{if(installed || pending)Toast.makeText(this,description,Toast.LENGTH_SHORT).show();else if(!translationModelsKnown)refreshModels();else downloadTranslation(code);});
        }
    }
    private void quietViews(View view){view.setSoundEffectsEnabled(false);if(view instanceof android.view.ViewGroup){android.view.ViewGroup group=(android.view.ViewGroup)view;for(int i=0;i<group.getChildCount();i++)quietViews(group.getChildAt(i));}}
    private void closeModelProbe(){modelHandler.removeCallbacksAndMessages(null);if(modelProbe!=null){modelProbe.destroy();modelProbe=null;}}
    private Intent speechIntent(){Intent intent=new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE,tags[sourceLanguage.getSelectedItemPosition()]);return intent;}
    private void refreshModels(){
        com.google.mlkit.common.model.RemoteModelManager.getInstance().getDownloadedModels(TranslateRemoteModel.class).addOnSuccessListener(models->{if(isDestroyed())return;Set<String> codes=new TreeSet<>();for(TranslateRemoteModel m:models)codes.add(m.getLanguage());installedTranslations.clear();installedTranslations.addAll(codes);translationModelsKnown=true;redrawLanguages();modelStatus.setText("Пакеты перевода · ✓ загружен · ↓ скачать");}).addOnFailureListener(e->{if(!isDestroyed())modelStatus.setText("Не удалось прочитать модели перевода");});
        Set<String> codes=new TreeSet<>();if(ready && tts.getVoices()!=null)for(Voice v:tts.getVoices())if(!v.isNetworkConnectionRequired())codes.add(v.getLocale().toLanguageTag());
        voiceModels.setText("Озвучка · голоса без требования сети: "+(codes.isEmpty()?"не обнаружены / движок ещё не готов":String.join(", ",codes)));
        closeModelProbe();
        if(conversation){speechModels.setText("Остановите разговор, затем обновите список речевых пакетов");return;}
        if(android.os.Build.VERSION.SDK_INT<33){speechModels.setText("Список речевых пакетов недоступен через API на этой версии Android");return;}
        if(!android.speech.SpeechRecognizer.isOnDeviceRecognitionAvailable(this)){speechModels.setText("Речь офлайн: системный движок недоступен. Модели перевода его не заменяют.");return;}
        try{
            modelProbe=android.speech.SpeechRecognizer.createOnDeviceSpeechRecognizer(this);final android.speech.SpeechRecognizer probe=modelProbe;
            speechModels.setText("Проверка речевых пакетов…");
            modelHandler.postDelayed(()->{if(modelProbe==probe){speechModels.setText("Сервис не ответил на запрос пакетов. Их наличие неизвестно.");closeModelProbe();}},10000);
            probe.checkRecognitionSupport(speechIntent(),getMainExecutor(),new android.speech.RecognitionSupportCallback(){
                public void onSupportResult(android.speech.RecognitionSupport result){if(modelProbe!=probe || isDestroyed())return;speechModels.setText("Речь офлайн · установлены: "+result.getInstalledOnDeviceLanguages()+"\nВ очереди загрузки: "+result.getPendingOnDeviceLanguages()+"\nДоступны для загрузки: "+result.getSupportedOnDeviceLanguages());closeModelProbe();}
                public void onError(int error){if(modelProbe!=probe || isDestroyed())return;speechModels.setText("Сервис не предоставил список речевых пакетов (код "+error+"). Наличие пакетов неизвестно.");closeModelProbe();}
            });
        }catch(RuntimeException e){closeModelProbe();speechModels.setText("Системный сервис не поддерживает проверку речевых пакетов");}
    }
    private void downloadTranslation(String code){
        if(downloadingTranslations.contains(code))return;downloadingTranslations.add(code);redrawLanguages();TranslateRemoteModel model=new TranslateRemoteModel.Builder(code).build();DownloadConditions.Builder conditions=new DownloadConditions.Builder();if(!mobileModels.isChecked())conditions.requireWifi();modelStatus.setText("Загрузка модели перевода "+code+"…");
        com.google.mlkit.common.model.RemoteModelManager.getInstance().download(model,conditions.build()).addOnSuccessListener(v->{if(!isDestroyed()){downloadingTranslations.remove(code);refreshModels();}}).addOnFailureListener(e->{if(!isDestroyed()){downloadingTranslations.remove(code);redrawLanguages();modelStatus.setText("Не удалось загрузить "+code+". Проверьте сеть и место.");Toast.makeText(this,"Не удалось загрузить модель "+code,Toast.LENGTH_LONG).show();}});
    }
    private void downloadSpeech(){
        if(conversation){speechModels.setText("Сначала остановите разговор");return;}
        if(android.os.Build.VERSION.SDK_INT<33 || !android.speech.SpeechRecognizer.isOnDeviceRecognitionAvailable(this)){speechModels.setText("Установка речевых пакетов через приложение недоступна");return;}
        closeModelProbe();try{modelProbe=android.speech.SpeechRecognizer.createOnDeviceSpeechRecognizer(this);modelProbe.triggerModelDownload(speechIntent());speechModels.setText("Запрошен речевой пакет "+tags[sourceLanguage.getSelectedItemPosition()]+". Это не подтверждение загрузки. Позже нажмите обновить.");modelHandler.postDelayed(this::closeModelProbe,30000);}catch(RuntimeException e){closeModelProbe();speechModels.setText("Сервис не принял запрос загрузки речевого пакета");}
    }
    private void newDialogue(){dialogueSession=UUID.randomUUID().toString();prefs.edit().putString("dialogue_session",dialogueSession).apply();}
    private void savePhrase(String session,String request,long at,String from,String to,String original,String translated){
        journalWorker.execute(()->{try{journal.save(session,request,at,from,to,original,translated);runOnUiThread(()->{if(!isDestroyed()){prefs.edit().putString("journal_last_result","saved").apply();if(currentPage==3)loadJournal(true);}});}catch(Exception e){runOnUiThread(()->{if(!isDestroyed()){prefs.edit().putString("journal_last_result","error:"+e.getClass().getSimpleName()).apply();status.setText("Журнал не сохранён: "+e.getClass().getSimpleName()+". Проверьте свободное место.");Toast.makeText(this,"Ошибка сохранения журнала",Toast.LENGTH_LONG).show();}});}});
    }
    private void loadJournal(boolean reset){
        if(journalLoading && !reset)return;
        if(reset){journalBefore=Long.MAX_VALUE;journalEntries.removeAllViews();}
        final long epoch=++journalEpoch,before=journalBefore;journalLoading=true;journalMore.setEnabled(false);
        journalWorker.execute(()->{
            try{java.util.List<DialogueStore.Entry> entries=journal.page(before);
                runOnUiThread(()->{if(isDestroyed() || epoch!=journalEpoch)return;journalLoading=false;journalMore.setEnabled(true);
                    LinearLayout previous=body;
                    for(DialogueStore.Entry entry:entries){body=card(journalEntries);
                        String when=java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT,java.text.DateFormat.SHORT).format(new Date(entry.created));
                        smallTitle(when+" · "+entry.from+" → "+entry.to);
                        label((entry.session.equals(dialogueSession)?"Текущий диалог":"Диалог")+" · "+entry.session.substring(0,8)).setTextSize(12);
                        TextView original=label(entry.original);original.setTextIsSelectable(true);original.setSaveEnabled(false);
                        TextView translated=label(entry.translated);translated.setTextColor(GREEN);translated.setTextIsSelectable(true);translated.setSaveEnabled(false);
                        iconAction("Копировать фразу и перевод",R.drawable.action_copy,()->{((android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(android.content.ClipData.newPlainText("Диалог",entry.original+"\n"+entry.translated));Toast.makeText(this,"Скопировано",Toast.LENGTH_SHORT).show();});
                        iconAction("Удалить этот диалог",R.drawable.action_trash,()->confirmDelete(entry.session));
                        journalBefore=entry.id;
                    }
                    body=previous;journalMore.setVisibility(entries.size()==50?View.VISIBLE:View.GONE);
                    journalTitle.setText(journalEntries.getChildCount()==0?"Пока нет сохранённых фраз":"Сохранённые фразы · новые сверху");
                });
            }catch(Exception e){runOnUiThread(()->{if(!isDestroyed() && epoch==journalEpoch){journalLoading=false;journalMore.setEnabled(true);journalTitle.setText("Не удалось открыть журнал. Попробуйте обновить.");}});}
        });
    }
    private void confirmDelete(String session){
        new AlertDialog.Builder(this).setTitle(session==null?"Удалить весь журнал?":"Удалить этот диалог?").setMessage("Сохранённые фразы и переводы будут удалены с этого телефона.")
            .setNegativeButton("Отмена",null).setPositiveButton("Удалить",(d,w)->{
                stopConversation();invalidateTranslation();
                journalWorker.execute(()->{try{journal.delete(session);runOnUiThread(()->{if(!isDestroyed()){if(session==null || session.equals(dialogueSession))newDialogue();loadJournal(true);}});}catch(Exception e){runOnUiThread(()->{if(!isDestroyed())Toast.makeText(this,"Не удалось удалить журнал",Toast.LENGTH_LONG).show();});}});
            }).show();
    }
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private android.graphics.drawable.GradientDrawable background(int color,int radius){android.graphics.drawable.GradientDrawable d=new android.graphics.drawable.GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private LinearLayout page(android.widget.FrameLayout container){ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);container.addView(scroll,new android.widget.FrameLayout.LayoutParams(-1,-1));pages.add(scroll);LinearLayout content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(dp(16),dp(6),dp(16),dp(18));scroll.addView(content);return content;}
    private LinearLayout card(LinearLayout parent){LinearLayout c=new LinearLayout(this);c.setOrientation(LinearLayout.VERTICAL);c.setPadding(dp(18),dp(12),dp(18),dp(16));c.setBackground(background(0xffffffff,22));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=dp(14);parent.addView(c,lp);return c;}
    private void showPage(int index){currentPage=index;if(index==4)refreshModels();if(index==3 && journalEntries!=null)loadJournal(true);for(int i=0;i<pages.size();i++){pages.get(i).setVisibility(i==index?View.VISIBLE:View.GONE);Button tab=tabs.get(i);tab.setTextColor(i==index?GREEN:MUTED);tab.setTypeface(null,i==index?android.graphics.Typeface.BOLD:android.graphics.Typeface.NORMAL);tab.setBackground(tabBackground(i==index));tab.setSelected(i==index);tab.setContentDescription(tab.getText()+(i==index?", выбранная вкладка":", вкладка"));}}
    private android.graphics.drawable.Drawable tabBackground(boolean selected){
        android.graphics.drawable.GradientDrawable line=background(selected?GREEN:android.graphics.Color.TRANSPARENT,2);
        android.graphics.drawable.LayerDrawable layers=new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{background(0xffffffff,0),line});
        layers.setLayerHeight(1,dp(3));layers.setLayerGravity(1,android.view.Gravity.BOTTOM);layers.setLayerInset(1,dp(8),0,dp(8),0);
        return new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x22246b61),layers,null);
    }
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
        ImageButton button=new ImageButton(this);button.setSoundEffectsEnabled(false);button.setImageResource(icon);button.setImageTintList(android.content.res.ColorStateList.valueOf(MUTED));button.setPadding(dp(12),dp(12),dp(12),dp(12));
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
        if(from==to) { if(prefs.getBoolean("save_journal",true))savePhrase(dialogueSession,UUID.randomUUID().toString(),System.currentTimeMillis(),tags[from],tags[to],input,input);output.setText(input); translationStatus.setText("Выбран один язык — текст без перевода");translationComplete(); return; }
        final long request= generation;
        final String savedSession=dialogueSession, savedRequest=UUID.randomUUID().toString();
        final boolean saveThis=prefs.getBoolean("save_journal",true);
        final long createdAt=System.currentTimeMillis();
        translating=true;
        autoHandler.postDelayed(()->{if(request==generation && translating){invalidateTranslation();stopConversation();translationStatus.setText("Истекло время ожидания моделей/перевода. Проверьте Wi-Fi или разрешите загрузку моделей через мобильную сеть в настройках.");}},90000);
        Translator client=Translation.getClient(new TranslatorOptions.Builder()
            .setSourceLanguage(tags[from].substring(0,2)).setTargetLanguage(tags[to].substring(0,2)).build());
        translator=client;
        DownloadConditions.Builder conditions=new DownloadConditions.Builder();
        if(!mobileModels.isChecked()) conditions.requireWifi();
        translationStatus.setText("Проверка/загрузка моделей… При первом запуске подключитесь к Wi-Fi.");
        Runnable performTranslation=() -> {
            if(request!=generation || isDestroyed()) return;
            translationStatus.setText("Перевод…");
            final long started=android.os.SystemClock.elapsedRealtime();
            client.translate(input).addOnSuccessListener(result -> {
                if(request!=generation || isDestroyed()) return;
                output.setText(result); translating=false;
                if(saveThis && prefs.getBoolean("save_journal",true))savePhrase(savedSession,savedRequest,createdAt,tags[from],tags[to],input,result);
                translationStatus.setText("Переведено на телефоне за "+(android.os.SystemClock.elapsedRealtime()-started)+" мс (без загрузки моделей)");translationComplete();
            }).addOnFailureListener(error -> translationFailed(request));
        };
        com.google.mlkit.common.model.RemoteModelManager.getInstance().getDownloadedModels(TranslateRemoteModel.class).addOnSuccessListener(models->{
            if(request!=generation || isDestroyed())return;
            Set<String> installed=new HashSet<>();for(TranslateRemoteModel m:models)installed.add(m.getLanguage());
            if(installed.contains(tags[from].substring(0,2)) && installed.contains(tags[to].substring(0,2)))performTranslation.run();
            else client.downloadModelIfNeeded(conditions.build()).addOnSuccessListener(v->performTranslation.run()).addOnFailureListener(e->translationFailed(request));
        }).addOnFailureListener(e->translationFailed(request));
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
        try{
            if(prefs.getBoolean("offline_speech",false)){
                if(android.os.Build.VERSION.SDK_INT<31 || !android.speech.SpeechRecognizer.isOnDeviceRecognitionAvailable(this)){stopConversation();status.setText("Офлайн-распознаватель недоступен. Проверьте вкладку «Языки» или выключите офлайн-речь.");return;}
                recognizer=android.speech.SpeechRecognizer.createOnDeviceSpeechRecognizer(this);
            }else recognizer=android.speech.SpeechRecognizer.createSpeechRecognizer(this);
        }catch(RuntimeException e){stopConversation();status.setText("Не удалось открыть сервис распознавания");return;}
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
    @Override protected void onDestroy() { closeModelProbe();journalEpoch++;journalWorker.execute(()->journal.close());journalWorker.shutdown();recording=false; invalidateTranslation(); if(tts!=null) tts.shutdown(); if(preferenceListener!=null) prefs.unregisterOnSharedPreferenceChangeListener(preferenceListener);super.onDestroy(); }
}
