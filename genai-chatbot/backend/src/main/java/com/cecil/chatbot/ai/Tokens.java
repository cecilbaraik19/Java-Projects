package com.cecil.chatbot.ai;

/**
 * Token counting.
 *
 * AI models read text as "tokens" (pieces of words). In English one token is about 4 characters,
 * so "Hello, how are you?" (19 characters) is about 5 tokens. Services charge and limit by tokens.
 * When the service tells us the real numbers we use them; otherwise we estimate with this rule.
 */
public final class Tokens {

    private Tokens() { }

    public static int estimate(String text) {
        if (text == null || text.isEmpty()) return 0;
        return (text.length() + 3) / 4;
    }
}
