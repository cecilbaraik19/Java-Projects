package com.cecil.chatbot.ai;

import com.cecil.chatbot.knowledge.Retriever;
import com.cecil.chatbot.util.MathEval;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The built-in "demo" model. It works with NO internet and NO API key, so the app can always be shown.
 *
 * It is not a real AI: it follows simple rules -
 *   knowledge-base notes -> quotes the best matching sentences with [1] citations
 *   greetings, "who are you", date / time, maths (12*(3+4)), a short FAQ about Java and Generative AI,
 *   a quick code check for the Code reviewer, and otherwise explains how to connect a real model.
 * It still streams its answer word by word, exactly like a real model, so the whole app can be tested offline.
 */
public class DemoModel implements ChatModel {

    /** Pause between words, so streaming can be seen. Tests set it to 0. */
    public static volatile int delayMs = 18;

    private static final Pattern MATH = Pattern.compile("[-+*/%^().\\d\\s×÷x]*\\d[-+*/%^().\\d\\s×÷x]*");
    private static final Pattern GREETING = Pattern.compile("^(hi|hii+|hello|hey|namaste|good (morning|afternoon|evening)|hola)\\b.*");

    /** A tiny FAQ: keywords -> answer. */
    private record Faq(String[] keys, String answer) { }

    private static final List<Faq> FAQ = List.of(
        new Faq(new String[] {"generative ai", "genai", "gen ai"},
            "**Generative AI** is AI that *creates* new content - text, images, code or music - instead of only sorting or predicting.\n\n"
          + "- It learns patterns from a huge amount of data.\n- When you give it a prompt, it writes something new that fits the pattern.\n"
          + "- Examples: ChatGPT, Gemini and Claude (text), DALL-E (images), GitHub Copilot (code).\n\n"
          + "This chatbot is a GenAI app: the Java backend sends your message to a model such as Gemini and streams the reply back."),
        new Faq(new String[] {"llm", "large language model", "language model"},
            "An **LLM (Large Language Model)** is a very big neural network trained on lots of text.\n\n"
          + "It works by predicting the **next token** (piece of a word) again and again:\n"
          + "\"The sky is\" -> \"blue\". Doing this word after word gives a full answer.\n\n"
          + "Examples: GPT (OpenAI), Gemini (Google), Claude (Anthropic), Llama (Meta)."),
        new Faq(new String[] {"prompt engineering", "prompt"},
            "A **prompt** is the text you give the AI. **Prompt engineering** means writing it well:\n\n"
          + "1. Say the role: *\"You are a Java teacher.\"*\n2. Give the task clearly: *\"Explain inheritance.\"*\n"
          + "3. Give limits: *\"In 5 lines, with one example.\"*\n4. Show the format you want (list, table, code).\n\n"
          + "In this app the **system prompt** of each assistant is a ready-made prompt."),
        new Faq(new String[] {"system prompt"},
            "The **system prompt** is a hidden instruction sent to the model before your chat. "
          + "It sets the bot's role and rules, e.g. *\"You are a patient Java tutor. Use small examples.\"* "
          + "Each assistant in this app has its own system prompt (see the **Assistants** page)."),
        new Faq(new String[] {"rag", "retrieval augmented", "retrieval-augmented", "knowledge base"},
            "**RAG = Retrieval-Augmented Generation.**\n\n1. **Retrieve**: search your own notes for parts that match the question (this app uses BM25 ranking).\n"
          + "2. **Augment**: add those parts to the prompt.\n3. **Generate**: the AI answers using them and cites [1], [2].\n\n"
          + "It lets the bot answer from *your* documents and reduces made-up answers."),
        new Faq(new String[] {"token"},
            "A **token** is a small piece of text that the model reads - about **4 characters** or **3/4 of a word** in English.\n\n"
          + "\"Hello, how are you?\" is about 5 tokens. AI services count and charge by tokens, and each model has a "
          + "**context window** (how many tokens it can read at once). This app shows tokens under every answer."),
        new Faq(new String[] {"temperature", "creativity"},
            "**Temperature** controls how creative / random the answers are.\n\n"
          + "- **0 - 0.3**: focused, same answer each time (good for facts, code).\n- **0.7**: balanced (default).\n"
          + "- **1.0+**: more creative and varied (stories, ideas).\n\nYou can change it per chat with the *Chat settings* button."),
        new Faq(new String[] {"hallucination", "hallucinate", "made up", "wrong answer"},
            "A **hallucination** is when an AI says something false but sounds sure.\n\nWays to reduce it:\n"
          + "- Give it the facts (RAG / knowledge base).\n- Use a low temperature.\n- Ask it to say \"I don't know\" when unsure.\n- Always check important answers."),
        new Faq(new String[] {"api key", "apikey"},
            "An **API key** is like a password that lets this app use an AI service.\n\n"
          + "- Gemini: free key from **Google AI Studio**.\n- Groq: free key from **console.groq.com**.\n- Ollama: runs on your own computer, no key.\n\n"
          + "The admin adds it on the **AI models** page, or sets an environment variable such as `GEMINI_API_KEY`. "
          + "The key stays on the server and is never sent to the browser."),
        new Faq(new String[] {"streaming", "stream", "sse", "server-sent"},
            "**Streaming** means the answer appears word by word while the AI is still writing it.\n\n"
          + "- The AI service sends pieces using **Server-Sent Events (SSE)**: lines like `data: {...}`.\n"
          + "- The Java backend reads them with `HttpClient` and forwards each piece to the browser, also as SSE.\n"
          + "- The browser reads them with `fetch()` and a stream reader. The **Stop** button closes the stream."),
        new Faq(new String[] {"jvm", "java virtual machine"},
            "The **JVM (Java Virtual Machine)** runs Java programs.\n\n`javac` turns `.java` code into **bytecode** (`.class` files), "
          + "and the JVM runs that bytecode on any computer - **\"write once, run anywhere\"**."),
        new Faq(new String[] {"oop", "object oriented", "object-oriented"},
            "**OOP (Object-Oriented Programming)** builds programs from objects. The 4 pillars:\n\n"
          + "1. **Encapsulation** - keep data private, use methods (getters / setters).\n2. **Inheritance** - a class reuses another (`class Dog extends Animal`).\n"
          + "3. **Polymorphism** - one method name, many forms (overloading, overriding).\n4. **Abstraction** - show what it does, hide how (interfaces, abstract classes).\n\n"
          + "In this project `ChatModel` is an **interface** and `GeminiClient`, `OpenAiClient`, `AnthropicClient` and `DemoModel` implement it - that is polymorphism."),
        new Faq(new String[] {"interface"},
            "An **interface** in Java lists methods a class must have, without saying how.\n\n```java\npublic interface ChatModel {\n    ChatResult stream(ChatRequest req, Consumer<String> onDelta, BooleanSupplier stop);\n}\n```\n"
          + "Every AI client in this app implements it, so the rest of the code works with any AI service."),
        new Faq(new String[] {"java"},
            "**Java** is an object-oriented programming language made by Sun Microsystems (now Oracle) in 1995.\n\n"
          + "- **Platform independent**: code runs on the JVM anywhere.\n- **Object-oriented**, strongly typed, with automatic memory (garbage collection).\n"
          + "- Used for backends, Android apps and big business systems.\n\nThis chatbot's backend is plain Java 17 with no extra libraries."),
        new Faq(new String[] {"http", "rest", "api"},
            "A **REST API** lets programs talk over HTTP using addresses and methods:\n\n"
          + "| Method | Use |\n|---|---|\n| GET | read data |\n| POST | create / do something |\n| PUT | change |\n| DELETE | remove |\n\n"
          + "This app's browser code calls the Java backend at `/api/...`, and the backend calls the AI service's API.")
    );

    private final Clock clock;

    public DemoModel(Clock clock) {
        this.clock = clock;
    }

    @Override
    public ChatResult stream(ChatRequest req, Consumer<String> onDelta, BooleanSupplier stop) {
        String answer = answer(req);
        ChatResult r = new ChatResult();
        StringBuilder sent = new StringBuilder();
        // send it in small pieces (a word and the space after it), like a real model
        Matcher m = Pattern.compile("\\S+\\s*|\\s+").matcher(answer);
        while (m.find()) {
            if (stop.getAsBoolean()) break;
            String piece = m.group();
            sent.append(piece);
            onDelta.accept(piece);
            if (delayMs > 0) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        r.text = sent.toString();
        r.promptTokens = Tokens.estimate(req.system);
        for (ChatRequest.Turn t : req.turns) r.promptTokens += Tokens.estimate(t.text());
        r.outputTokens = Tokens.estimate(r.text);
        return r;
    }

    /** Works out the whole answer (no streaming). */
    public String answer(ChatRequest req) {
        String text = req.lastUserText().trim();
        String low = text.toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder();
        boolean teach = false; // persona tips are added only to real explanations

        if (!req.sources.isEmpty()) {
            out.append(fromNotes(text, req.sources));
            teach = true;
        } else if (GREETING.matcher(low).matches() && low.length() < 40) {
            out.append("Hello! I'm **").append(req.botName).append("**, your ").append(req.personaName).append(".\n\n")
               .append("Right now I'm running in **offline demo mode**. Try asking me:\n\n")
               .append("- *What is generative AI?*\n- *Explain RAG in simple words*\n- *What is 15% of 2400?* or *(25+17)*3*\n- *What is the date today?*");
        } else if (low.matches(".*\\b(who are you|what are you|your name|what can you do|help me|how do you work)\\b.*")) {
            out.append("I'm **").append(req.botName).append("**, a chatbot built with a **Java backend**.\n\n")
               .append("- The backend can send messages to **Google Gemini**, **OpenAI-compatible** services (OpenAI, Groq, Ollama) or **Claude**.\n")
               .append("- Answers are **streamed** word by word.\n- I can look up the **knowledge base** and cite notes like [1].\n")
               .append("- Personal details are hidden before your message leaves the server.\n\n")
               .append("At the moment I'm the **built-in demo model**, which works offline with simple rules.");
        } else if (isMath(low)) {
            out.append(math(text));
        } else if (low.matches(".*\\b(time|date|today|day is it)\\b.*") && low.length() < 60) {
            LocalDateTime now = LocalDateTime.now(clock);
            out.append("It is **").append(now.format(DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)))
               .append("** on **").append(now.format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.ENGLISH))).append("** (server time).");
        } else if (looksLikeCode(text) && (req.personaId.equals("code-reviewer") || low.contains("review") || low.contains("check"))) {
            out.append(review(text));
        } else {
            Faq best = null;
            for (Faq f : FAQ) {
                for (String k : f.keys()) {
                    if (Pattern.compile("\\b" + Pattern.quote(k) + "\\b").matcher(low).find()) { best = f; break; }
                }
                if (best != null) break;
            }
            if (best != null) {
                out.append(best.answer());
                teach = true;
            } else {
                String shortQ = text.length() > 120 ? text.substring(0, 120) + "…" : text;
                out.append("I'm running in **offline demo mode**, so I can only answer simple things: greetings, maths, the date, "
                        + "basics of Java and Generative AI, and questions about notes in the **knowledge base**.\n\n")
                   .append("You asked: *\"").append(shortQ.replace("*", "")).append("\"*\n\n")
                   .append("To get a real AI answer, the admin can connect **Gemini**, **Groq**, **OpenAI**, **Claude** or a local **Ollama** model "
                        + "on the **AI models** page, then pick it with the model button above the message box.");
            }
        }
        if (teach) out.append(personaTip(req.personaId));
        return out.toString();
    }

    private String fromNotes(String question, List<Retriever.Hit> hits) {
        List<String> q = Retriever.words(question);
        StringBuilder sb = new StringBuilder("Here is what I found in the knowledge base:\n\n");
        int shown = 0;
        for (Retriever.Hit h : hits) {
            if (shown == 2) break;
            List<String> best = bestSentences(h.text(), q, 2);
            if (best.isEmpty()) continue;
            sb.append("- ").append(String.join(" ", best)).append(" [").append(h.n()).append("]\n");
            shown++;
        }
        sb.append("\n*(Demo mode: these are the closest lines from the notes. A real AI model will write a proper answer from them.)*");
        return sb.toString();
    }

    /** The sentences with the most question words. */
    static List<String> bestSentences(String text, List<String> q, int max) {
        String[] sentences = text.replace("\n", " ").split("(?<=[.!?])\\s+");
        List<String> out = new ArrayList<>();
        List<Integer> scores = new ArrayList<>();
        for (String s : sentences) {
            List<String> w = Retriever.words(s);
            int score = 0;
            for (String term : q) if (w.contains(term)) score++;
            scores.add(score);
        }
        for (int round = 0; round < max; round++) {
            int bi = -1;
            for (int i = 0; i < sentences.length; i++) {
                if (scores.get(i) > 0 && (bi < 0 || scores.get(i) > scores.get(bi))) bi = i;
            }
            if (bi < 0) break;
            out.add(sentences[bi].trim());
            scores.set(bi, -1);
        }
        if (out.isEmpty() && sentences.length > 0) out.add(sentences[0].trim());
        return out;
    }

    static boolean isMath(String low) {
        String t = low.replaceAll("^(what is|what's|calculate|calc|solve|compute|find)\\s+", "").replaceAll("[?=]+$", "").trim();
        if (t.matches("\\d+(\\.\\d+)?\\s*%\\s*of\\s*\\d+(\\.\\d+)?")) return true;
        return MATH.matcher(t).matches() && t.matches(".*\\d\\s*[-+*/%^×÷x]\\s*[\\d(].*");
    }

    static String math(String text) {
        String t = text.toLowerCase(Locale.ROOT).replaceAll("^(what is|what's|calculate|calc|solve|compute|find)\\s+", "")
                .replaceAll("[?=]+$", "").trim();
        Matcher pct = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*%\\s*of\\s*(\\d+(?:\\.\\d+)?)").matcher(t);
        if (pct.matches()) {
            double a = Double.parseDouble(pct.group(1)), b = Double.parseDouble(pct.group(2));
            return "**" + pct.group(1) + "% of " + pct.group(2) + " = " + MathEval.format(a * b / 100) + "**\n\n"
                    + "Working: " + pct.group(2) + " × " + pct.group(1) + " ÷ 100 = " + MathEval.format(a * b / 100);
        }
        try {
            double v = MathEval.eval(t);
            return "**" + t.replaceAll("\\s+", " ") + " = " + MathEval.format(v) + "**\n\n"
                    + "(Brackets first, then powers ^, then × ÷ %, then + −.)";
        } catch (IllegalArgumentException e) {
            return "I could not work that out: " + e.getMessage() + ". Try something like `(25 + 17) * 3`.";
        }
    }

    static boolean looksLikeCode(String t) {
        return t.contains("```") || (t.contains(";") && (t.contains("{") || t.contains("(")));
    }

    /** A few simple checks that a beginner reviewer would make. */
    static String review(String code) {
        List<String> notes = new ArrayList<>();
        if (code.matches("(?s).*catch\\s*\\([^)]*\\)\\s*\\{\\s*\\}.*")) notes.add("**Empty catch block** - the error is hidden. At least log it.");
        if (code.matches("(?s).*(\"[^\"]*\"\\s*==|==\\s*\"[^\"]*\").*")) notes.add("**Comparing strings with ==** - use `.equals()` instead.");
        if (code.contains("System.out.print")) notes.add("**System.out.println** used - fine for practice; real apps use a logger.");
        String low = code.toLowerCase(Locale.ROOT);
        if (low.matches("(?s).*(password|secret|apikey|api_key)\\s*=\\s*\"[^\"]+\".*")) notes.add("**Hard-coded secret** - read passwords / keys from settings or environment variables.");
        if ((low.contains("select ") || low.contains("insert ") || low.contains("delete ")) && low.matches("(?s).*\"\\s*\\+.*")) notes.add("**SQL built by joining strings** - risk of SQL injection. Use `PreparedStatement` with `?`.");
        if (code.matches("(?s).*\\bcatch\\s*\\(\\s*Exception\\b.*")) notes.add("**Catching Exception** - catch the specific exception you expect.");
        int longest = 0;
        for (String line : code.split("\n")) longest = Math.max(longest, line.length());
        if (longest > 120) notes.add("**Very long line (" + longest + " characters)** - split it to make it easier to read.");
        StringBuilder sb = new StringBuilder("Quick code check (demo mode, simple rules):\n\n");
        if (notes.isEmpty()) sb.append("- No common problems found. Check names, comments and edge cases (null, empty input).\n");
        for (String n : notes) sb.append("- ").append(n).append('\n');
        sb.append("\nA real AI model gives a full review with fixed code.");
        return sb.toString();
    }

    private static String personaTip(String personaId) {
        return switch (personaId == null ? "" : personaId) {
            case "viva-coach" -> "\n\n> **Viva tip:** say the answer in one line first, then give one example from your project.";
            case "java-tutor" -> "\n\n> **Try it:** write a tiny Java program for this and run it - that is the fastest way to learn.";
            case "cyber-mentor" -> "\n\n> **Stay ethical:** practise only on your own machines or legal labs (CTFs, TryHackMe, HackTheBox).";
            default -> "";
        };
    }
}
