package com.cecil.chatbot.ai;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Common shape of every AI model client (Gemini, OpenAI-compatible, Claude, demo).
 * The rest of the app only talks to this interface, so adding a new AI service means adding one class.
 */
public interface ChatModel {

    /**
     * Asks the model and streams the answer.
     *
     * @param req     the prompt
     * @param onDelta called with each new piece of text as it arrives
     * @param stop    checked often; when it returns true the model stops early
     * @return the full answer and token counts
     */
    ChatResult stream(ChatRequest req, Consumer<String> onDelta, BooleanSupplier stop) throws AiException;
}
