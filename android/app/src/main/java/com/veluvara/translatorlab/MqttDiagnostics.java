package com.veluvara.translatorlab;

import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Service-owned, TLS-verified, narrow diagnostic channel. */
final class MqttDiagnostics {
    static final String ROOT="translator/v1/phone1";
    private final ScheduledExecutorService io=Executors.newSingleThreadScheduledExecutor();
    private final Consumer<String> status;
    private final Consumer<String> check;
    private volatile boolean active=true;
    private MqttClient client;
    private final CommandGuard guard=new CommandGuard();
    MqttDiagnostics(String user,String password,Consumer<String> status,Consumer<String> check) {
        this.status=status; this.check=check;
        io.scheduleWithFixedDelay(() -> {
            if(!active) return;
            try {
                if(client==null) {
                    client=new MqttClient("wss://mqtt.velu-vara.com:443/mqtt","translator-"+UUID.randomUUID(),new MemoryPersistence());
                    client.setTimeToWait(15000);
                    client.setCallback(new MqttCallback() {
                        public void connectionLost(Throwable cause) { if(active) status.accept("MQTT: связь потеряна, повтор через 30 секунд"); }
                        public void deliveryComplete(IMqttDeliveryToken token) {}
                        public void messageArrived(String topic,MqttMessage message) {
                            if(!active || !topic.equals(ROOT+"/cmd") || message.isRetained() || message.getPayload().length>2048) return;
                            try {
                                JSONObject cmd=new JSONObject(new String(message.getPayload(),StandardCharsets.UTF_8));
                                String id=cmd.getString("request_id");
                                if(guard.accept(cmd.getString("action"),id,cmd.getLong("issued_at"),System.currentTimeMillis()/1000)) check.accept(id);
                            } catch(Exception ignored) { /* Malformed commands never execute. */ }
                        }
                    });
                }
                if(!client.isConnected()) {
                    MqttConnectOptions options=new MqttConnectOptions();
                    options.setUserName(user); options.setPassword(password.toCharArray());
                    options.setHttpsHostnameVerificationEnabled(true);
                    options.setCleanSession(true); options.setConnectionTimeout(10); options.setKeepAliveInterval(30);
                    client.connect(options);
                    if(!active) return;
                    client.subscribe(ROOT+"/cmd",1);
                    status.accept("MQTT подключён · самопроверка доступна в фоне");
                }
            } catch(Exception e) { try { if(client!=null && client.isConnected()) client.disconnect(1000); } catch(Exception ignored) {} if(active) status.accept("MQTT: нет соединения. Проверьте доступ и интернет; повтор через 30 секунд."); }
        },0,30,TimeUnit.SECONDS);
    }
    void reply(JSONObject report) {
        if(!active) return;
        try { io.execute(() -> {
            if(!active || client==null || !client.isConnected()) return;
            try { client.publish(ROOT+"/reply",report.toString().getBytes(StandardCharsets.UTF_8),1,false); }
            catch(Exception e) { status.accept("MQTT: отчёт не доставлен"); }
        }); } catch(RejectedExecutionException ignored) {}
    }
    void close() {
        active=false;
        io.execute(() -> {
            if(client!=null) {
                try { if(client.isConnected()) client.disconnect(1000); } catch(Exception ignored) {}
                try { if(client.isConnected()) client.disconnectForcibly(1000,1000,false); client.close(); } catch(Exception ignored) {}
            }
        });
        io.shutdown();
    }
}
