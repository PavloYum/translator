package com.veluvara.translatorlab;

/** Android recognition error codes, mapped without storing speech content. */
final class SpeechErrors {
    static String message(int error,String language) {
        String prefix="Речь "+language+": ";
        switch(error) {
            case 12: return prefix+"язык не поддерживается системным сервисом. Выберите другой сервис распознавания в настройках Android.";
            case 13: return prefix+"речевой пакет недоступен. Отключите предпочтение офлайн-распознавания, подключите интернет или загрузите речевой пакет языка.";
            case 1: case 2: case 4: case 11: return prefix+"ошибка сети/сервиса. Проверьте интернет и повторите запуск микрофона.";
            case 3: return prefix+"микрофон недоступен. Закройте другие приложения, использующие микрофон.";
            case 8: return prefix+"сервис распознавания занят. Подождите и повторите запуск.";
            case 9: return prefix+"разрешите микрофон в настройках Android.";
            case 10: return prefix+"слишком много запросов. Подождите перед повторным запуском.";
            default: return prefix+"распознавание остановлено (код "+error+"). Повторите запуск микрофона.";
        }
    }
}
