package com.rikkahub.agent.port;

/** Host-configured translation capability; unavailable implementations must fail explicitly. */
public interface TranslationPort {
    String translate(String text, String targetLanguage);
}
