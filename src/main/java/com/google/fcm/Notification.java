package com.google.fcm;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * @author André Alexandre
 * @since 1.0.0
 */
@Data
@Builder
public class Notification implements Serializable {

    private final String title;
    private final String body;
    private final String icon;
    private final String sound;
    private final Integer badge;
    private final String tag;
    private final String color;
    private final String clickAction;
    private final String bodyLocKey;
    private final List<String> bodyLocArgs;
    private final String titleLocKey;
    private final List<String> titleLocArgs;

}