package com.cecil.chatbot.ai;

import com.cecil.chatbot.knowledge.Retriever;
import java.util.ArrayList;
import java.util.List;

/**
 * Everything one AI model needs to write an answer.
 *
 *   system  = hidden instructions (who the bot is, rules, knowledge-base notes)
 *   turns   = the conversation so far, oldest first, ending with the person's newest message
 */
public class ChatRequest {

    /** One message in the conversation. role is "user" or "assistant". */
    public record Turn(String role, String text) { }

    public String system = "";
    public final List<Turn> turns = new ArrayList<>();
    public String model = "";
    public double temperature = 0.7;
    public int maxTokens = 800;
    public int timeoutSec = 60;

    // Only the built-in demo model uses these (real models get the same facts inside "system").
    public String botName = "Nova";
    public String personaId = "";
    public String personaName = "Assistant";
    public List<Retriever.Hit> sources = new ArrayList<>();

    public String lastUserText() {
        for (int i = turns.size() - 1; i >= 0; i--) {
            if (turns.get(i).role().equals("user")) return turns.get(i).text();
        }
        return "";
    }

    /**
     * Gemini and Claude need the turns to go user, assistant, user, ... and start with "user".
     * This joins two messages in a row from the same side and drops a leading assistant message.
     */
    public List<Turn> alternating() {
        List<Turn> out = new ArrayList<>();
        for (Turn t : turns) {
            if (t.text() == null || t.text().isBlank()) continue;
            if (out.isEmpty() && !t.role().equals("user")) continue;
            if (!out.isEmpty() && out.get(out.size() - 1).role().equals(t.role())) {
                Turn prev = out.remove(out.size() - 1);
                out.add(new Turn(t.role(), prev.text() + "\n\n" + t.text()));
            } else {
                out.add(t);
            }
        }
        return out;
    }
}
