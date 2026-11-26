# Фоновая диагностика 0.4

DiagnosticsService владеет MQTT, отдельным TTS для проверки готовности и ML Kit
клиентом фиксированного теста. Activity отображает настройки/последний отчёт,
но onStop/onDestroy больше не останавливают сервис. Сервис startForeground
сразу после явного запуска из видимой Activity, START_STICKY при системном
перезапуске (с проверкой сохранённого opt-in). При STOP opt-in сбрасывается.
После force-stop/перезагрузки нужен ручной запуск, boot receiver отсутствует.

Тип FGS connectedDevice: связь по сети с пользовательским физическим Pi.
Заявлены FOREGROUND_SERVICE, FOREGROUND_SERVICE_CONNECTED_DEVICE и
CHANGE_NETWORK_STATE — сетевой prerequisite данного типа. Приложение не меняет
Wi-Fi, не запрашивает постоянный wakelock и не включает microphone FGS.
POST_NOTIFICATIONS запрашивается с API 33; отказ не скрывает сервис из системного
списка активных приложений, но может скрыть карточку из шторки.

Старые callbacks после остановки/переподключения фильтруются epoch; отменяются
таймеры и ML-клиент, MQTT disconnect/close выполняется вне UI thread. Служебный
JSON сохраняется приватно для карточки последней проверки; личного текста нет.
Поле app_visible позволяет отличить проверку в открытом окне от фоновой.

Ограничения: Doze/OEM могут задерживать сеть. Нет гарантии uptime, автоматического
старта после boot и работы после force-stop. После публикации провести реальные
проверки: Home/chat, блокировка 2/10 минут, отключение Wi-Fi/сотовой сети и возврат,
Stop из уведомления, force-stop, перезапуск, запрет уведомлений, доступность TTS.

Документация:
https://developer.android.com/develop/background-work/services/fgs/service-types#connected-device
https://developer.android.com/develop/background-work/services/fgs/launch
