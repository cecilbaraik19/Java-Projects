package com.cecil.chatbot.ai;

/** What came back from the model. Token counts are 0 when the service did not report them (we estimate then). */
public class ChatResult {
    public String text = "";
    public int promptTokens;
    public int outputTokens;
    /** "stop" = finished normally, "length" = cut short by the reply limit. */
    public String finish = "stop";
}
