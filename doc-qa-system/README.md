# AI Document Question-Answering System

Upload your documents (PDF, Word, text) and ask questions about them.
The app finds the right parts of your documents and gives you an answer **with sources** —
click a source number like `[1]` to see the exact text the answer came from.

**Tech:** Java 17 · Spring Boot 3 · H2 database · Apache PDFBox · Apache POI · React 18 (Vite) · Claude API (optional)

---

## Features

- **Upload documents** — PDF, DOCX, TXT, MD, CSV, JSON, HTML (drag and drop, up to 20 MB)
- **Ask questions** in plain English and get answers with clickable citations `[1] [2]`
- **See the source** — page number and the matching text, with your question words highlighted
- **Two answer modes**
  - **Claude AI mode** — writes a natural answer, using only your documents (needs an API key)
  - **Offline mode** — works with no key and no internet; picks the best sentences from your documents
- **Ask from selected documents only** — tick the documents you want on the left
- **Match level** (high / medium / low) so you know how well the documents cover the question
- **Question history** — saved in the database, still there after restart
- **Document preview** — read the extracted text of any document
- **Light and dark mode**, works on phone screens too

---

## How it works (simple version)

```
 Upload              Split                  Search                    Answer
┌────────┐   text  ┌─────────┐  question  ┌──────────────┐  top 5   ┌─────────────────┐
│ PDF /  │ ──────▶ │ Chunks  │ ─────────▶ │ BM25 search  │ ───────▶ │ Claude AI  or   │
│ DOCX / │ PDFBox  │ ~900    │            │ (best        │  chunks  │ Offline answer  │
│ TXT    │ POI     │ chars   │            │  matching)   │          │ + sources [1]   │
└────────┘         └─────────┘            └──────────────┘          └─────────────────┘
```

1. **Extract** — PDFBox reads PDFs page by page; POI reads Word files.
2. **Chunk** — the text is cut into small pieces (~900 characters) that overlap a little,
   cut at sentence ends so each piece makes sense.
3. **Search** — when you ask, every chunk gets a score with **BM25** (the ranking method
   search engines use). Chunks with more of your words — especially rare words — score higher.
4. **Answer** — the top 5 chunks are sent to Claude with the rule *"answer only from these"*.
   With no API key, the offline answerer picks the best matching sentences instead.
   This method is called **RAG** (Retrieval-Augmented Generation).

---

## How to run

You need: **Java 17+**, **Maven**, and **Node.js 18+** (Node only if you change the frontend).

### Option A — one command (easiest)

The React site is already built into the backend, so just run:

```bash
cd backend
mvn spring-boot:run
```

Open **http://localhost:8090** — that's it.

### Option B — development mode (to edit the frontend)

Terminal 1:
```bash
cd backend
mvn spring-boot:run
```

Terminal 2:
```bash
cd frontend
npm install
npm run dev
```

Open **http://localhost:5173**. After changing the frontend, run `npm run build` to copy
it into the backend again.

### Turn on Claude AI mode (optional)

Get an API key from https://console.anthropic.com, then set it before starting the backend:

**Windows (PowerShell):**
```powershell
$env:ANTHROPIC_API_KEY="sk-ant-..."
mvn spring-boot:run
```

**Windows (CMD):**
```cmd
set ANTHROPIC_API_KEY=sk-ant-...
mvn spring-boot:run
```

The bottom-left of the app shows a **green dot** in Claude mode and an **orange dot** in offline mode.
If Claude can't be reached, the app automatically falls back to offline mode.

To change the model, set `DOCQA_MODEL` (default: `claude-sonnet-5-5`).

### Try it

Upload `samples/student-handbook.txt` and ask:
- *How much is the late fee?*
- *When does the library close on Saturday?*
- *How many books can I borrow?*

---

## Run the tests

```bash
cd backend
mvn test
```

Tests cover the tokenizer, chunker, BM25 search, both answer generators, and the full REST API
(upload → ask → history → delete, plus error cases).

---

## REST API

| Method | URL | What it does |
|---|---|---|
| `POST` | `/api/documents` | Upload a file (form field `file`) |
| `GET` | `/api/documents` | List documents |
| `GET` | `/api/documents/{id}` | Document details + text preview |
| `DELETE` | `/api/documents/{id}` | Delete a document |
| `POST` | `/api/ask` | Ask: `{"question": "...", "documentIds": [1, 2]}` (`documentIds` optional) |
| `GET` | `/api/history` | Last 50 questions and answers |
| `DELETE` | `/api/history` | Clear history |
| `GET` | `/api/status` | AI mode, model, document and chunk counts |

Example answer:
```json
{
  "question": "How much is the late fee?",
  "answer": "A late fee of 500 rupees is charged after that date. [1]",
  "mode": "offline",
  "confidence": "high",
  "sources": [{ "number": 1, "fileName": "student-handbook.txt", "pageNumber": null, "snippet": "...", "score": 4.21 }],
  "durationMs": 12
}
```

Database console (to look at the tables): http://localhost:8090/h2-console
— JDBC URL `jdbc:h2:file:./data/docqa`, user `sa`, no password.

---

## Project structure

```
doc-qa-system/
├── backend/                               Spring Boot app
│   ├── pom.xml
│   └── src/main/java/com/cecil/docqa/
│       ├── DocQaApplication.java          main class
│       ├── config/                        settings + CORS
│       ├── model/                         Document, DocumentChunk, QaRecord (database tables)
│       ├── repository/                    database access (Spring Data JPA)
│       ├── service/
│       │   ├── TextExtractor.java         reads PDF / DOCX / TXT
│       │   ├── TextChunker.java           splits text into chunks
│       │   ├── Tokenizer.java             words for searching (stop words + stemming)
│       │   ├── SearchIndex.java           BM25 search, in memory
│       │   ├── DocumentService.java       upload / list / delete
│       │   ├── QaService.java             search → answer → save history
│       │   └── answer/
│       │       ├── ClaudeAnswerGenerator.java      Claude API
│       │       └── ExtractiveAnswerGenerator.java  offline answers
│       └── web/                           REST controllers, DTOs, error handling
├── frontend/                              React app (Vite)
│   └── src/  App.jsx · api.js · styles.css
└── samples/student-handbook.txt           a document to try
```

## Settings (`backend/src/main/resources/application.yml`)

| Setting | Default | Meaning |
|---|---|---|
| `docqa.chunk.size` | 900 | characters per chunk |
| `docqa.chunk.overlap` | 150 | characters shared by neighbouring chunks |
| `docqa.retrieval.top-k` | 5 | how many chunks are used for an answer |
| `docqa.ai.model` | claude-sonnet-5-5 | Claude model |

## Limits

- Scanned PDFs (only images, no text) need OCR first — the app tells you if no text is found.
- The search matches **words**, so very different wording (e.g. "car" vs "vehicle") may not match.
  A next step would be adding embeddings (vector search).
