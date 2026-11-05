package com.rikkahub.agent.port;

/** Host-configured image/video generation capability; task execution is asynchronous. */
public interface MediaPort {
    byte[] generateImage(String prompt);
}
