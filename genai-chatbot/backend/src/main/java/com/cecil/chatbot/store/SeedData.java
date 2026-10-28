package com.cecil.chatbot.store;

import com.cecil.chatbot.ai.DemoModel;
import com.cecil.chatbot.ai.PromptBuilder;
import com.cecil.chatbot.ai.Tokens;
import com.cecil.chatbot.knowledge.Retriever;
import com.cecil.chatbot.model.Chat;
import com.cecil.chatbot.model.Enums.ProviderKind;
import com.cecil.chatbot.model.Enums.Role;
import com.cecil.chatbot.model.KnowledgeDoc;
import com.cecil.chatbot.model.Message;
import com.cecil.chatbot.model.Persona;
import com.cecil.chatbot.model.Provider;
import com.cecil.chatbot.model.UsageRecord;
import com.cecil.chatbot.model.User;
import com.cecil.chatbot.util.Passwords;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Demo data for the first run: 4 people, 6 AI providers, 7 assistants, 4 knowledge-base notes,
 * a few chats and two weeks of usage. All names and notes are made up.
 */
public final class SeedData {

    private SeedData() { }

    public static void fill(DataStore s) {
        synchronized (s.lock) {
            String now = s.nowText();
            user(s, "admin", "Site Admin", Role.ADMIN, "admin123", now);
            user(s, "priya", "Priya Nair", Role.TRAINER, "trainer123", now);
            user(s, "rahul", "Rahul Verma", Role.MEMBER, "member123", now);
            user(s, "ananya", "Ananya Das", Role.MEMBER, "member123", now);

            providers(s);
            personas(s, now);
            knowledge(s, now);
            // if a key is already set (environment variable), use Gemini or Groq by default
            for (String id : new String[] {"gemini", "groq"}) {
                Provider p = s.provider(id);
                if (p != null && p.ready()) { s.settings.defaultProviderId = id; break; }
            }
            s.settings.blockedWords = new ArrayList<>(List.of("exam paper leak", "credit card dump"));
            chats(s);
            usage(s);
        }
        s.saveAll();
    }

    private static void user(DataStore s, String username, String name, Role role, String password, String now) {
        User u = new User();
        u.username = username;
        u.displayName = name;
        u.role = role;
        u.passwordHash = Passwords.hash(password);
        u.active = true;
        u.createdAt = now;
        s.users.add(u);
    }

    private static Provider provider(DataStore s, String id, String name, ProviderKind kind, String url, String model,
                                     List<String> models, String env, boolean needsKey, boolean enabled, String help) {
        Provider p = new Provider();
        p.id = id;
        p.name = name;
        p.kind = kind;
        p.baseUrl = url;
        p.model = model;
        p.models = new ArrayList<>(models);
        p.envKey = env;
        p.needsKey = needsKey;
        p.enabled = enabled;
        p.help = help;
        s.providers.add(p);
        return p;
    }

    static void providers(DataStore s) {
        provider(s, "demo", "Demo (offline)", ProviderKind.DEMO, "", "demo-rules-1", List.of(), "", false, true,
                "Built into the app. Works with no internet and no key, using simple rules. Good for testing.");
        provider(s, "gemini", "Google Gemini", ProviderKind.GEMINI, "https://generativelanguage.googleapis.com/v1beta",
                "gemini-2.5-flash", List.of("gemini-2.5-flash", "gemini-2.5-flash-lite", "gemini-2.5-pro"), "GEMINI_API_KEY", true, true,
                "Free key: aistudio.google.com -> Get API key. Paste it here or set GEMINI_API_KEY.");
        provider(s, "groq", "Groq (Llama)", ProviderKind.OPENAI, "https://api.groq.com/openai/v1",
                "llama-3.3-70b-versatile", List.of("llama-3.3-70b-versatile", "llama-3.1-8b-instant"), "GROQ_API_KEY", true, true,
                "Very fast and has a free tier: console.groq.com -> API Keys. Uses the OpenAI-compatible API.");
        provider(s, "openai", "OpenAI", ProviderKind.OPENAI, "https://api.openai.com/v1",
                "gpt-4o-mini", List.of("gpt-4o-mini", "gpt-4.1-mini", "gpt-4o"), "OPENAI_API_KEY", true, true,
                "Paid key from platform.openai.com -> API keys.");
        provider(s, "anthropic", "Anthropic Claude", ProviderKind.ANTHROPIC, "https://api.anthropic.com/v1",
                "claude-haiku-4-5", List.of("claude-haiku-4-5", "claude-sonnet-4-5"), "ANTHROPIC_API_KEY", true, true,
                "Paid key from console.anthropic.com -> API keys.");
        provider(s, "ollama", "Ollama (local)", ProviderKind.OPENAI, "http://localhost:11434/v1",
                "llama3.2", List.of("llama3.2", "qwen2.5", "mistral"), "", false, false,
                "Runs AI on your own computer, free and private. Install from ollama.com, run 'ollama pull llama3.2', then turn this on.");
    }

    private static void persona(DataStore s, String id, String name, String color, String desc, double temp, boolean kb,
                                String prompt, List<String> starters, String now) {
        Persona p = new Persona();
        p.id = id;
        p.name = name;
        p.color = color;
        p.description = desc;
        p.temperature = temp;
        p.useKnowledge = kb;
        p.systemPrompt = prompt;
        p.starters = new ArrayList<>(starters);
        p.createdBy = "admin";
        p.updatedAt = now;
        s.personas.add(p);
    }

    static void personas(DataStore s, String now) {
        persona(s, "general", "General assistant", "teal", "Friendly helper for any question.", 0.7, false,
                "Be friendly and helpful. Give the direct answer first, then a short explanation. If you are not sure, say so.",
                List.of("What is generative AI?", "Explain RAG in simple words", "Give me 5 tips to learn faster", "What is 15% of 2400?"), now);
        persona(s, "java-tutor", "Java tutor", "blue", "Explains Java step by step with small examples.", 0.4, false,
                "You are a patient Java teacher for first-year B.Sc. IT students. Explain in very simple English, step by step. "
              + "Always include a small, complete Java example that compiles, and explain each important line. End with one practice question.",
                List.of("Explain OOP with a simple example", "What is the difference between an interface and an abstract class?", "How does a HashMap work?", "What is the JVM?"), now);
        persona(s, "viva-coach", "Viva coach", "violet", "Practise viva questions and get feedback on your answers.", 0.6, false,
                "You are a friendly viva (oral exam) examiner for a B.Sc. IT student's Java project. Ask one question at a time. "
              + "When the student answers, give a score out of 10, say what was good, what was missing, and a model answer in 2-4 lines. Then ask the next question.",
                List.of("Ask me viva questions on this chatbot project", "What is streaming and how does this app use it?", "What is a token?", "Explain prompt engineering"), now);
        persona(s, "code-reviewer", "Code reviewer", "slate", "Finds bugs, security issues and style problems in code.", 0.2, false,
                "You are a senior code reviewer. Review the code the user sends. List problems in order of importance: bugs, security issues "
              + "(injection, hard-coded secrets, missing validation), performance, then readability. For each, say why it matters and show the fixed code. Be kind but direct.",
                List.of("Review: if (name == \"admin\") { login(); }", "What makes code secure?", "Explain SQL injection with a Java example"), now);
        persona(s, "cyber-mentor", "Cyber security mentor", "rose", "Teaches ethical hacking and defence, legally.", 0.5, false,
                "You are a cyber security mentor. Teach concepts of web security, networking and defence clearly, with real-world examples and how to protect against each attack. "
              + "Only help with legal, authorised and educational activities (own labs, CTFs, bug bounty programmes in scope). Refuse to help attack systems without permission.",
                List.of("Explain the OWASP Top 10 in simple words", "What is prompt injection in AI apps?", "How do I prepare for a CTF?", "What is XSS and how do I prevent it?"), now);
        persona(s, "writer", "Email & writing helper", "amber", "Writes and improves emails, letters and posts.", 0.8, false,
                "You help write clear, polite and well-structured English: emails, applications, letters, LinkedIn posts. "
              + "Ask for missing details only if really needed. Give a subject line for emails. Keep it short unless asked otherwise.",
                List.of("Write a leave application for 2 days", "Make this email more polite: send me the notes now", "Write a LinkedIn post about my chatbot project"), now);
        persona(s, "helpdesk", "Campus helpdesk", "green", "Answers from the knowledge base (library, exams, this app).", 0.3, true,
                "You are the helpdesk bot for Nova College (a made-up college used for this demo). Answer ONLY from the knowledge-base notes given to you, "
              + "and cite them like [1]. If the notes do not have the answer, say \"I could not find that in the notes\" and suggest asking the office.",
                List.of("How many books can I borrow from the library?", "What is the minimum attendance for exams?", "What is the late fee for library books?", "How do I connect Gemini to this chatbot?"), now);
    }

    private static void doc(DataStore s, String id, String title, String tags, String text, String now) {
        KnowledgeDoc d = new KnowledgeDoc();
        d.id = id;
        d.title = title;
        d.tags = tags;
        d.text = text;
        d.createdBy = "priya";
        d.updatedAt = now;
        s.knowledge.add(d);
    }

    static void knowledge(DataStore s, String now) {
        doc(s, "kb-library", "Nova College library rules", "library, books, borrow, fine",
                "Nova College is a made-up college used to demonstrate this chatbot.\n\n"
              + "Opening hours: the library is open from 8:00 am to 8:00 pm on weekdays and from 9:00 am to 2:00 pm on Saturdays. It is closed on Sundays and public holidays.\n\n"
              + "Borrowing: students can borrow up to 4 books at a time for 14 days. Teachers can borrow up to 10 books for 30 days. "
              + "Reference books, journals and newspapers cannot be taken out of the library.\n\n"
              + "Renewal: a book can be renewed twice online if nobody else has reserved it.\n\n"
              + "Late fee: the fine is Rs 5 per book per day after the due date. A lost book must be replaced or paid for at twice its price.\n\n"
              + "Library card: bring your college ID card to get a library card. A lost library card can be replaced for Rs 50.", now);
        doc(s, "kb-exams", "Nova College exam and attendance rules", "exam, attendance, marks, grade",
                "Attendance: students need at least 75% attendance in each subject to sit the end-semester exam. "
              + "With a medical certificate, the college may allow down to 65%.\n\n"
              + "Marks: each subject has 40 marks of internal assessment (2 class tests, assignments and attendance) and 60 marks in the end-semester exam. "
              + "The pass mark is 40% overall and at least 35% in the end-semester exam.\n\n"
              + "Re-evaluation: a student can apply for re-evaluation within 7 days of the result. The fee is Rs 500 per paper.\n\n"
              + "Project viva: final-year and Java project vivas are held in the last two weeks of the semester. Bring a working demo, the project report and the source code.", now);
        doc(s, "kb-app", "GenAI Chatbot - how this app works", "chatbot, gemini, api key, provider, streaming, safety",
                "Connecting a real AI model: the admin opens the AI models page, chooses a provider such as Google Gemini or Groq, pastes the API key and presses Test connection. "
              + "A free Gemini key comes from Google AI Studio (aistudio.google.com). A free Groq key comes from console.groq.com. "
              + "The key can also be set as an environment variable such as GEMINI_API_KEY before starting the app.\n\n"
              + "Choosing a model: in a chat, press the model button above the message box to pick the provider and model for that chat.\n\n"
              + "Streaming: answers appear word by word. The Java backend reads the AI service's Server-Sent Events and forwards each piece to the browser. The Stop button ends the answer early.\n\n"
              + "Safety: before a message is sent to an online model, e-mail addresses, phone numbers, card numbers, ID numbers and secret keys are replaced with labels like [EMAIL]. "
              + "Messages that try prompt injection are marked or blocked, and the admin can add blocked words.\n\n"
              + "Limits: each member can send 10 messages per minute and use 40,000 tokens per day by default. The admin can change these on the AI settings page.", now);
        doc(s, "kb-java", "Java quick notes", "java, oop, jvm, collections",
                "Java source code (.java) is compiled by javac into bytecode (.class). The JVM runs the bytecode, so the same program runs on Windows, Linux and Mac.\n\n"
              + "The four pillars of OOP are encapsulation, inheritance, polymorphism and abstraction.\n\n"
              + "An interface lists methods without bodies (except default methods). A class can implement many interfaces but extend only one class.\n\n"
              + "ArrayList keeps items in order and allows duplicates. HashMap stores key-value pairs and finds a value by its key very fast using the key's hashCode.\n\n"
              + "Checked exceptions (like IOException) must be caught or declared with throws. Unchecked exceptions (like NullPointerException) do not have to be.", now);
    }

    /** A few finished chats written by the demo model, so the screens are not empty. */
    static void chats(DataStore s) {
        DemoModel demo = new DemoModel(s.clock());
        Retriever r = new Retriever();
        r.rebuild(s.knowledge);
        LocalDateTime t = s.now().minusDays(2).withHour(10).withMinute(5).withSecond(0);
        chat(s, demo, r, "rahul", "general", t, List.of("What is generative AI?", "What is a token?"), new int[] {1, 0});
        chat(s, demo, r, "rahul", "java-tutor", t.plusDays(1).withHour(16), List.of("Explain OOP with a simple example"), new int[] {1});
        chat(s, demo, r, "rahul", "helpdesk", t.plusDays(1).withHour(18), List.of("How many books can I borrow from the library?", "What is the late fee?"), new int[] {1, 1});
        chat(s, demo, r, "ananya", "helpdesk", t.withHour(12), List.of("What is the minimum attendance for exams?"), new int[] {1});
        chat(s, demo, r, "ananya", "general", t.plusDays(1).withHour(11), List.of("Write a poem about the monsoon"), new int[] {-1});
        chat(s, demo, r, "priya", "viva-coach", t.plusDays(1).withHour(9), List.of("What is streaming and how does this app use it?"), new int[] {1});
        Chat c = s.chats.get(s.chats.size() - 5);
        c.pinned = true;
        for (Chat x : s.chats) {
            for (Message m : x.messages) {
                if (m.feedback < 0) m.feedbackNote = "Demo mode cannot write poems - needs a real model.";
            }
        }
    }

    private static void chat(DataStore s, DemoModel demo, Retriever r, String owner, String personaId, LocalDateTime start,
                             List<String> questions, int[] feedback) {
        Persona p = s.persona(personaId);
        Chat c = new Chat();
        c.id = "c" + Passwords.randomId();
        c.owner = owner;
        c.personaId = personaId;
        c.providerId = "";
        c.model = "";
        c.useKnowledge = p.useKnowledge;
        c.createdAt = start.format(DataStore.TIME);
        LocalDateTime at = start;
        for (int i = 0; i < questions.size(); i++) {
            Message u = new Message();
            u.id = "m" + Passwords.randomId();
            u.role = Message.USER;
            u.content = questions.get(i);
            u.at = at.format(DataStore.TIME);
            c.messages.add(u);
            List<Retriever.Hit> hits = c.useKnowledge ? r.search(u.content, 3) : List.of();
            PromptBuilder.Built b = PromptBuilder.build(s.settings.botName, p, c.messages, hits, s.settings.maxContextTokens, false, at);
            Message a = new Message();
            a.id = "m" + Passwords.randomId();
            a.role = Message.ASSISTANT;
            a.content = demo.answer(b.request);
            at = at.plusSeconds(4);
            a.at = at.format(DataStore.TIME);
            a.providerId = "demo";
            a.providerName = "Demo (offline)";
            a.model = "demo-rules-1";
            a.promptTokens = b.estimatedTokens;
            a.outputTokens = Tokens.estimate(a.content);
            a.ms = 900 + a.content.length() * 3L;
            for (Retriever.Hit h : hits) {
                a.sources.add(new java.util.LinkedHashMap<>(java.util.Map.of("n", h.n(), "docId", h.docId(), "title", h.title(),
                        "snippet", h.text().length() > 240 ? h.text().substring(0, 240) + "…" : h.text())));
            }
            a.feedback = i < feedback.length ? feedback[i] : 0;
            c.messages.add(a);
            at = at.plusMinutes(2);
        }
        c.title = titleOf(questions.get(0));
        c.updatedAt = at.format(DataStore.TIME);
        s.chats.add(c);
    }

    private static String titleOf(String q) {
        String t = q.replace("?", "");
        return t.length() > 44 ? t.substring(0, 44) + "…" : t;
    }

    /** Two weeks of usage records for the dashboard chart (all from the demo model). */
    static void usage(DataStore s) {
        Random rnd = new Random(7);
        String[] people = {"rahul", "ananya", "priya", "admin"};
        for (int d = 13; d >= 1; d--) {
            for (String u : people) {
                int calls = rnd.nextInt(u.equals("admin") ? 3 : 9);
                for (int i = 0; i < calls; i++) {
                    UsageRecord r = new UsageRecord();
                    r.at = s.now().minusDays(d).withHour(9 + rnd.nextInt(11)).withMinute(rnd.nextInt(60)).format(DataStore.TIME);
                    r.username = u;
                    r.providerId = "demo";
                    r.model = "demo-rules-1";
                    r.promptTokens = 150 + rnd.nextInt(700);
                    r.outputTokens = 60 + rnd.nextInt(320);
                    r.ms = 700 + rnd.nextInt(2500);
                    r.ok = true;
                    s.usage.add(r);
                }
            }
        }
        // records for the seeded chats
        for (Chat c : s.chats) {
            for (Message m : c.messages) {
                if (m.isUser()) continue;
                UsageRecord r = new UsageRecord();
                r.at = m.at;
                r.username = c.owner;
                r.chatId = c.id;
                r.providerId = "demo";
                r.model = m.model;
                r.promptTokens = m.promptTokens;
                r.outputTokens = m.outputTokens;
                r.ms = m.ms;
                s.usage.add(r);
            }
        }
        s.usage.sort((a, b) -> a.at.compareTo(b.at));
    }
}
