package com.veluvara.translatorlab;
public class SpeechErrorsTest {
    public static void main(String[] args){
        check(SpeechErrors.message(13,"de-DE").contains("речевой пакет"));
        check(SpeechErrors.message(12,"de-DE").contains("не поддерживается"));
        check(SpeechErrors.message(9,"ru-RU").contains("разрешите микрофон"));
        check(SpeechErrors.message(2,"de-DE").contains("интернет"));
        check(SpeechErrors.message(8,"de-DE").contains("занят"));
        check(SpeechErrors.message(42,"de-DE").contains("42"));
        check(SpeechErrors.message(13,"de-DE").contains("de-DE"));
        System.out.println("Speech errors: 7 checks passed");
    }
    private static void check(boolean ok){if(!ok)throw new AssertionError();}
}
