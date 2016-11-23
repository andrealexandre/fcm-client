package com.google.fcm

import spock.lang.Specification;

/**
 * @author André Alexandre
 * @since 1.9.0
 */
class SenderTest extends Specification {

    def sender = Mock(Sender)

    def simple_test() {
        given:
            def message = Message.builder().build()

        when: "simple call to send"
            def result = sender.send(message)
        then:
            1 * sender.send(message) >> Result.builder().build()
            result != null
    }

}