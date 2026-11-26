# MQTT diagnostics 0.4

Phone: `wss://mqtt.velu-vara.com:443/mqtt`, user `translator-phone`.
Password is provisioned separately, never embedded in APK or this repository.
Enter it under «Удалённая самопроверка MQTT» and press «Сохранить и включить MQTT».
The saved password is encrypted by an Android Keystore AES-GCM key. Android backup
is disabled. MQTT is opt-in and owned by a foreground service with a persistent notification.
Leaving the Activity keeps the connection. Stop from UI/notification disables it.
Force-stop/reboot requires opening the app again; Doze/OEM restrictions may delay
network. See docs/BACKGROUND.md.

Pi requester (credentials remain outside Git):
```sh
/home/pavel/.local/share/translator-mqtt-venv/bin/python /home/pavel/translator/mqtt/check.py
# Test the external path rather than local broker:
/home/pavel/.local/share/translator-mqtt-venv/bin/python /home/pavel/translator/mqtt/check.py --external
```
Install requester dependency elsewhere with `pip install paho-mqtt==2.1.0`.
Default private credentials: `~/.config/translator-mqtt/credentials.json` (0600),
JSON with a `translator-monitor` password. The phone uses its separate account.
This script never sends notifications to people or invokes microphone capture.

## Contract
- command topic `translator/v1/phone1/cmd` (monitor write, phone read).
- reply topic `translator/v1/phone1/reply` (phone write, monitor read).
- QoS 1; neither commands nor replies retained; clean sessions.
- command: `{"action":"self_test","request_id":"unique-uuid","issued_at":1790000000}`.
  Timestamp is current Unix seconds, example is intentionally not usable.
- Only self_test allowed; reject retained, >2048 bytes, older than 60s or >10s
  into future, bad request id, recent duplicates. At most one accepted command
  per 10 seconds; only one test in flight. Test deadline 20 seconds.
- Models are listed, not downloaded remotely. Fixed RU→DE test runs only if
  Russian and German models are present. No user text/audio returned.
- Reports: correlated id, version, timestamp, TTS readiness, offline voice count,
  model language codes, translation duration and non-empty result flag.
- `ok` means non-empty test translation + TTS ready + a target offline voice;
  it does NOT prove translation accuracy or audible sound. `limited` means
  translation succeeded but TTS/voice unavailable. Other statuses: models_missing,
  busy, timeout, models_error, translation_error, empty_result.
- Microphone is never activated; speaker is never activated remotely.
- Phone clocks must be reasonably synchronized. No reply means unknown, not down.
- Existing broker user keeps existing topic privileges but is denied the new
  translator namespace. New accounts cannot access any other topics.

## Checks performed
Broker local roundtrip and 3 forbidden publish attempts tested with MQTT v5.
Public TLS/WebSocket authentication tested. CommandGuard standalone test covers
unsupported action, stale/future timestamp, malformed id, duplicates and rate limit.
Phone end-to-end check requires installation and enabling MQTT on the real device.

Run command parser test:
```sh
mkdir -p android/build/guard-tests
javac -d android/build/guard-tests android/app/src/main/java/com/veluvara/translatorlab/CommandGuard.java android/app/src/testStandalone/CommandGuardTest.java
java -cp android/build/guard-tests com.veluvara.translatorlab.CommandGuardTest
```

Version 0.4 adds background_service, app_visible, voice_target (de) report fields.
The last diagnostic report is retained privately for the UI; no personal text.
