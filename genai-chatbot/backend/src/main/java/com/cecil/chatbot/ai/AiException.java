package com.cecil.chatbot.ai;

/** A problem talking to an AI service, with a message that is safe and simple to show to the person. */
public class AiException extends Exception {

    private static final long serialVersionUID = 1L;

    public final int status;

    public AiException(int status, String message) {
        super(message);
        this.status = status;
    }

    public AiException(String message) {
        this(0, message);
    }
}
