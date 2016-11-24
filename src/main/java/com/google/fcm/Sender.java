package com.google.fcm;

import java.io.IOException;

/**
 * Interface responsible for top level message sending.
 *
 * @author André Alexandre
 * @since 1.0.0
 */
public interface Sender {

    Result send(Message message) throws IOException;

}