# GenAI Chatbot

A **Generative AI chatbot with a Java backend**. The Java server talks to real AI models —
**Google Gemini**, **Groq / OpenAI / OpenRouter / Ollama** (OpenAI-compatible) and **Anthropic Claude** —
and streams the answer to the browser word by word. It also has assistants (system prompts),
a knowledge base the bot can cite (RAG), safety filters, limits and a usage dashboard.

Plain **Java 17** backend (no external libraries — even the AI calls use Java's built-in `HttpClient`)
and a plain **HTML / CSS / JavaScript** frontend, the same setup as the other projects in this folder.
**Light mode and dark mode** are both built in.

## Run it

You only need **Java 17 or newer** (JDK). No database, no npm.

| How | What to do |
|---|---|
| Windows | Double-click `run.bat` |
| Mac / Linux | `./run.sh` |
| IntelliJ | Open this folder, wait for Maven to load, open `backend/src/main/java/com/cecil/chatbot/App.java`, click Run |
| Maven | `cd backend` then `mvn package` and `java -jar target/genai-chatbot.jar` |

Then open **http://localhost:8080** in your browser.

### Demo logins

| Username | Password | Role |
|---|---|---|
| admin | admin123 | Admin (AI models, API keys, settings, team) |
| priya | trainer123 | AI trainer (assistants, knowledge base, sees feedback) |
| rahul, ananya | member123 | Member (chats) |

On the first run the app fills itself with demo data: 7 assistants, 4 knowledge-base notes about a made-up
"Nova College", a few chats and two weeks of usage. To start fresh, stop the app and delete the `data` folder.

## Connect a real AI model (2 minutes)

Without a key the **built-in demo model** answers (it works offline with simple rules), so the app always runs.
For real AI answers:

1. Get a free key: **Google Gemini** → https://aistudio.google.com → *Get API key*
   (or **Groq** → https://console.groq.com → *API Keys*).
2. Sign in as **admin** → **AI models** → *Edit / API key* on Google Gemini → paste the key → Save.
3. Press **Test** (it should say *Connected*), then **Make default**.

Safer option: set the key as an environment variable before starting, and it is never saved in the data folder:

```
Windows (cmd):   set GEMINI_API_KEY=your-key   then   run.bat
Mac / Linux:     export GEMINI_API_KEY=your-key  then  ./run.sh
```

Also understood: `GROQ_API_KEY`, `OPENAI_API_KEY`, `ANTHROPIC_API_KEY`.
**Ollama** (free, runs on your own PC, no key): install from ollama.com, run `ollama pull llama3.2`,
then turn on "Ollama (local)" on the AI models page. Any other OpenAI-compatible service can be added with **+ Add provider**.

If the online model fails (no internet, wrong key, quota used up), the demo model answers instead and the
message says why (this can be turned off in **AI settings**).

## Run the tests

`run-tests.bat` (Windows) or `./run-tests.sh` (Mac / Linux). **26 tests**, no internet needed: the online AI
services are replaced by small fake servers that answer in exactly the same streaming format as OpenAI,
Gemini and Claude. Covered: JSON, passwords, login lock-out, roles, chat privacy, PII redaction, prompt
injection, blocked words, rate limit, daily token limit, BM25 search, prompt building and context window,
calculator, demo model, SSE reading, all three AI clients, error messages, sending a message end to end,
demo fallback, Stop, regenerate / edit, validation, saving to disk, demo data and the HTTP API with streaming.

## What it does

| Page | What you can do |
|---|---|
| Chat | Chat list with search and pin. Pick an assistant and a model, then chat. Answers **stream** in; **Stop** ends early. Markdown with code blocks (Copy button), tables and citations [1]. Copy, 👍 / 👎 feedback, **Regenerate**, **Edit** the last question. Chat settings: assistant, provider, model, creativity (temperature), knowledge base on/off. Export as Markdown or JSON. Each answer shows model, tokens and time. |
| Assistants | 7 ready-made assistants (General, Java tutor, Viva coach, Code reviewer, Cyber security mentor, Email & writing helper, Campus helpdesk). Trainers can add / edit: name, colour, system prompt, creativity, starter questions, knowledge base on/off. |
| Knowledge base | Notes the bot can look up (paste text or load a .txt / .md file). A **search tester** shows which parts the bot would use and their BM25 score. |
| Dashboard | My questions, tokens today vs daily limit, average answer time, 14-day chart, which models are ready. Admin: whole-team numbers, use by model and by person, chats per assistant, safety counts. Trainer / admin: feedback and the latest answers marked bad. |
| AI models (admin) | Providers with status, API key (only the last 4 characters are ever shown), **Test connection**, **Make default**, add / remove providers. |
| AI settings (admin) | Bot name, default model, demo fallback, PII redaction, prompt-injection mode, blocked words, context budget, reply length, messages per minute, tokens per day, time-out. |
| Team (admin) | Add people, change roles, reset passwords, turn accounts off. |

### How one message travels (in simple words)

1. The browser sends the message to `POST /api/chats/{id}/send`.
2. The server checks: is it your chat, speed limit, daily token limit, blocked words, prompt injection.
3. If the knowledge base is on, it finds the best 3 matching note parts (BM25).
4. It builds the prompt: **system prompt** (bot + assistant instructions + date + notes [1] [2] [3]) and the
   **recent chat history** that fits the token budget (older messages are left out — the "context window").
5. Before anything goes to an online model, personal details are replaced: e-mail → `[EMAIL]`, phone → `[PHONE]`,
   card number (Luhn check) → `[CARD]`, Aadhaar / PAN → `[ID_NUMBER]`, API keys → `[SECRET_KEY]`.
6. The right client (`GeminiClient`, `OpenAiClient`, `AnthropicClient` or `DemoModel`) sends it with `HttpClient`
   and reads the reply as **Server-Sent Events** (`data: {...}` lines).
7. Each piece is passed straight on to the browser, also as SSE (`event: delta`). The browser shows it at once.
8. At the end the answer is saved with model, tokens (from the service, or estimated as characters ÷ 4) and time,
   and a usage record is written for the dashboard and limits.

### Key ideas

- **LLM**: a large language model predicts the next token again and again to write text.
- **Token**: a piece of a word, about 4 characters. Services count and charge by tokens.
- **System prompt**: hidden instructions sent before the chat; each assistant has its own.
- **Temperature**: 0 = focused and repeatable, 0.7 = balanced, 1+ = creative.
- **Context window**: how much text a model can read at once; we send only the newest messages that fit.
- **RAG**: Retrieval-Augmented Generation — search your notes first, give the best parts to the model, it cites them.
- **BM25**: the ranking formula used by search engines — more matching words and rarer words score higher.
- **Streaming / SSE**: the reply arrives in small pieces over one open HTTP response.
- **Prompt injection**: a message that tries to make the bot ignore its rules ("ignore all previous instructions").

### Who can do what

| | Member | AI trainer | Admin |
|---|---|---|---|
| Chat, use assistants, search the knowledge base, own dashboard | ✔ | ✔ | ✔ |
| Add / edit assistants and knowledge-base notes, see feedback | | ✔ | ✔ |
| AI models and API keys, AI settings, team, team dashboard | | | ✔ |
| Speed limit and daily token limit apply | ✔ | ✔ | |

Chats are private: nobody else can open them, not even the admin (the admin sees only counts, and trainers
see only answers that someone marked as bad).

## Light and dark mode

The app follows the computer's setting (light or dark) the first time.
The **Dark mode / Light mode** button in the top bar (and on the sign-in page) switches it, and the browser remembers the choice.
All colours are CSS variables, so both modes use the same layout.

## Folder layout

```
genai-chatbot/
├── backend/                          Java backend (also serves the website)
│   ├── pom.xml
│   ├── run.bat / run.sh              build + start
│   ├── run-tests.bat / run-tests.sh
│   └── src/
│       ├── main/java/com/cecil/chatbot/
│       │   ├── App.java              starts everything
│       │   ├── ai/                   ChatModel (interface), GeminiClient, OpenAiClient, AnthropicClient,
│       │   │                         DemoModel, HttpAi (HttpClient + SSE), PromptBuilder, ModelRegistry, Tokens
│       │   ├── knowledge/            Retriever (chunking + BM25 search)
│       │   ├── safety/               Guard (PII redaction, prompt injection, blocked words), RateLimiter
│       │   ├── model/                User, Chat, Message, Persona, KnowledgeDoc, Provider, Settings, UsageRecord, Enums
│       │   ├── store/                DataStore (JSON files), SeedData (demo data)
│       │   ├── service/              Auth, Chat (sending + streaming), Persona, Knowledge, Settings, Analytics
│       │   ├── web/                  ApiServer (routes), Http (helpers, SSE)
│       │   └── util/                 Json, Passwords, MathEval
│       └── test/java/com/cecil/chatbot/AllTests.java
├── frontend/                         index.html, app.css, app.js, theme.js, favicon.svg
├── data/                             created on first run (users, chats, personas, knowledge, providers, usage, settings)
├── pom.xml                           root Maven file for IntelliJ
├── run.bat / run.sh                  shortcuts to backend/run.*
└── run-tests.bat / run-tests.sh
```

## Settings (optional)

Set these as environment variables before starting:

- `PORT` – port number (default `8080`)
- `HOST` – `127.0.0.1` by default (only your computer). Use `0.0.0.0` to open it to your local network.
- `DATA_DIR` – where to keep the data files (default `data/` in the project folder)
- `GEMINI_API_KEY`, `GROQ_API_KEY`, `OPENAI_API_KEY`, `ANTHROPIC_API_KEY` – AI keys (see above)

Model names change over time. If a provider says "model not found", open **AI models → Edit** and type the
current model name from that provider's website.

## Security

- API keys stay on the server: the browser only ever sees the last 4 characters. Environment variables are supported so keys need not be saved in files.
- Personal details are hidden before text is sent to an online model; the chat itself keeps what the person typed.
- Prompt-injection attempts are marked or blocked; the admin can add blocked words.
- Speed limit per person (sliding 60-second window) and a daily token limit protect the AI quota.
- AI answers are shown with a safe Markdown renderer: text is escaped first, links must be http(s), and a strict
  Content-Security-Policy blocks inline scripts and styles — so an AI answer can never run code in the browser.
- Passwords are stored as PBKDF2-SHA256 hashes with a random salt; 5 wrong passwords lock that username for 5 minutes.
- Sessions use random 256-bit tokens that expire after 12 hours of no use. Every API call checks the role on the server.
- Cross-site POST requests are rejected; static files cannot be read outside the `frontend` folder.
- Provider base URLs must use https (plain http is allowed only for localhost, e.g. Ollama).
- Request bodies are limited to 1 MB, messages to 8,000 characters, and data files are written safely (temp file, then move).

## API (for reference)

All API calls except login need the header `Authorization: Bearer <token>`.

| Method | Path | What |
|---|---|---|
| POST | /api/auth/login | `{username, password}` → `{token, user}` |
| POST | /api/auth/logout, /api/auth/password | sign out, change password |
| GET | /api/auth/me, /api/meta | me; lists (roles, providers, assistants, bot name) |
| GET/POST/PUT | /api/users, /api/users/{username} | team (admin) |
| GET/POST/DELETE | /api/chats?q= | my chats (search), new chat, delete all unpinned |
| GET/PUT/DELETE | /api/chats/{id} | one chat with messages; rename, pin, assistant, provider, model, temperature, knowledge base |
| POST | /api/chats/{id}/send | `{text, mode: new / regenerate / edit}` → **event stream**: `start`, `delta` × n, `done` (or `error`) |
| POST | /api/chats/{id}/stop | stop the answer being written |
| POST | /api/chats/{id}/messages/{msgId}/feedback | `{value: 1 / -1 / 0, note}` |
| GET | /api/chats/{id}/export.md, export.json | download |
| GET/POST/PUT/DELETE | /api/personas, /api/personas/{id} | assistants |
| GET/POST/PUT/DELETE | /api/knowledge, /api/knowledge/{id} | knowledge base |
| GET | /api/knowledge/search?q=&k= | search tester |
| GET/POST/PUT/DELETE | /api/providers, /api/providers/{id} | AI providers (admin) |
| POST | /api/providers/{id}/test | test connection |
| GET/PUT | /api/settings | AI settings (admin) |
| GET | /api/dashboard | dashboard numbers |
