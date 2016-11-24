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
public class MulticastResult implements Serializable {

    private final int success;
    private final int failure;
    private final int canonicalIds;
    private final long multicastId;
    private final List<Result> results;
    private final List<Long> retryMulticastIds;

    public int getTotal() {
        return success + failure;
    }

}
