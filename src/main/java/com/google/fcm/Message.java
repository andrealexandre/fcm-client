package com.google.fcm;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * @author André Alexandre
 * @since 1.0.0
 */
@Data
@Builder
public class Message {

    private final String collapseKey;
    private final Boolean delayWhileIdle;
    private final Integer timeToLive;
    private final Map<String, String> data;
    private final Boolean dryRun;
    private final String restrictedPackageName;
    private final String priority;
    private final Boolean contentAvailable;
    private final Notification notification;

}