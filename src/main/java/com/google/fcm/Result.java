package com.google.fcm;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * @author André Alexandre
 * @since 1.9.0
 */
@Data
@Builder
public class Result {

    private final String messageId;
    private final String canonicalRegistrationId;
    private final String errorCode;
    private final Integer success;
    private final Integer failure;
    private final List<String> failedRegistrationIds;

}