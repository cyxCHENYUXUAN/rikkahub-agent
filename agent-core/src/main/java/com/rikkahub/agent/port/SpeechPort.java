package com.rikkahub.agent.port;

/** Host-configured TTS/STT capability; implementations must bound input sizes. */
public interface SpeechPort {
    byte[] synthesize(String text, String voice);

    String transcribe(byte[] audio);
}
