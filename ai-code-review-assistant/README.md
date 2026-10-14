# AI Code Review Assistant

An automated code reviewer. You give it code, and it reviews it the way a senior developer would:

- what is wrong (bugs, security holes, bad style),
- **why** it matters, with a before/after example,
- how to fix it. It can also fix simple things for you.
- a quality score (0-100) and a grade (A-F).

It has a **Java backend** (the review engine + REST API) and a **web frontend** (HTML/CSS/JS) that opens in your browser.
It works **offline**. With a Claude API key it can also ask Claude for a deeper review.

---

## 1. Project structure

```
ai-code-review-assistant/
├── backend/                      Java backend (no external libraries, only the JDK)
│   ├── pom.xml                   Maven build (optional)
│   ├── run.bat / run.sh          build + start the server
│   ├── run-tests.bat / .sh       run all tests
│   ├── cli.bat                   command line version (no website)
│   └── src/
│       ├── main/java/com/cecil/codereview/
│       │   ├── App.java              main: starts the web server
│       │   ├── Cli.java              command line version
│       │   ├── ReviewService.java    connects everything
│       │   ├── server/               REST API + serves the frontend (ApiServer, JsonParser, JsonBuilder)
│       │   ├── analyzer/             SourceFile, Rule, ReviewEngine, Scorer
│       │   ├── rules/                50 rules: SecurityRules, BugRules, QualityRules
│       │   ├── metrics/              lines, methods, cyclomatic complexity
│       │   ├── ai/                   ReviewSummarizer, KnowledgeBase, ClaudeReviewer, AutoFixer
│       │   ├── report/               HTML / JSON / Markdown / console reports, history
│       │   ├── git/                  changed files / changed lines
│       │   └── config/               settings
│       └── test/java/com/cecil/codereview/
│           ├── AllTests.java         32 engine tests
│           └── ApiTests.java         15 REST API tests
├── frontend/                     Website (plain HTML, CSS, JavaScript)
│   ├── index.html
│   ├── app.css                   light + dark mode
│   ├── app.js                    pages: Review, Full report, Rules, History, Settings
│   └── favicon.svg
├── samples/                      demo files: BankService.java (many problems), Account.java (clean), utils.py
├── data/                         history.csv is saved here (created automatically)
├── pom.xml                       root Maven file (so IntelliJ sees the backend module)
├── run.bat / run.sh              shortcut: starts the backend
└── run-tests.bat / run-tests.sh  shortcut: runs the tests
```

---

## 2. How to run

You need **JDK 17 or newer**. Check it with `java -version`.

### Windows (easiest)

1. Open the project folder in File Explorer.
2. Double-click **`run.bat`**.
3. The browser opens **http://localhost:8080** by itself. If it doesn't, open that link yourself.
4. To stop the server, press **Ctrl + C** in the black window (or just close it).

From PowerShell:

```powershell
cd "D:\Web-Development\Java Projects\ai-code-review-assistant"
.\run.bat
```

### IntelliJ IDEA

1. **File → Open**, choose the `ai-code-review-assistant` folder. It loads as a Maven project because of `pom.xml`.
2. Open `backend/src/main/java/com/cecil/codereview/App.java`.
3. Click the green ▶ next to `main`, then open **http://localhost:8080**.

### Tests

Double-click `run-tests.bat`. You should see `32 passed` and `15 passed`.

---

## 3. Using the website

| Page | What you can do |
|---|---|
| **Review code** | Paste code (**+ New**), **Upload** files, upload a whole **Folder**, load a **path** from your computer (also "only Git changes"), or try the **Samples**. Press **Review** (or Ctrl+Enter). Problem lines are colored. Click an issue to jump to its line and see why it matters + a before/after example. **Auto-fix** shows a preview and then fixes the safe issues. **Export** downloads an HTML, Markdown or JSON report. |
| **Full report** | Overall grade, issues by severity and category, the AI reviewer summary, a table of all files, and the most complex methods. |
| **Rules** | All 50 rules with explanations. Untick a rule to switch it off. |
| **History** | A chart and table of every review score, so you can see the code getting better. |
| **Settings** | Minimum severity, limits (method length, complexity...), theme (light/dark), Claude status. |

---

## 4. Features

- **50 rules**
  - **Security:** SQL injection, command injection, hard-coded passwords/API keys, MD5/SHA-1, weak encryption, insecure random for OTPs, TLS checks turned off, path traversal, unsafe deserialization, secrets in logs, eval, XSS.
  - **Bugs:** Strings compared with `==`, empty catch blocks, resource leaks, `equals()` without `hashCode()`, switch without default, `if (x = y)`, money stored in `double`, and more.
  - **Other:** performance, maintainability (long methods, complexity, duplicate code, unused code), style, documentation.
- **Metrics:** code lines, comment ratio, methods, cyclomatic complexity, nesting, parameters.
- **Offline AI engine:** a reviewer-style summary with a verdict, the top 3 priorities, the hotspot file, the most complex method, what is good, and next steps.
- **Claude AI (optional):** a deeper review of logic problems and false alarms, with an improved example.
- **Auto-fix (safe only):** `==` → `.equals()`, MD5 → SHA-256, unused imports, `size()==0` → `isEmpty()`, trailing spaces.
- **Reports:** HTML, Markdown (for GitHub PR comments), JSON.
- **Git mode:** review only the files you changed.
- **History** of scores, **dark/light mode**, works on mobile too.
- **Languages:** Java (all rules), JavaScript / TypeScript / Python (main rules).

### Turn on Claude AI (optional)

```bat
set ANTHROPIC_API_KEY=your-key-here
run.bat
```

Then go to **Settings** and tick "Also ask Claude for a deeper review". Without a key, the offline engine is used.

---

## 5. REST API (backend)

The frontend only talks to the backend through these calls:

| Method | URL | What it does |
|---|---|---|
| GET | `/api/info` | version, default limits, whether Claude is available |
| GET | `/api/rules` | all rules with explanation + before/after example |
| GET | `/api/samples` | the demo files |
| POST | `/api/review` | `{files:[{name,content}], settings, useClaude, saveHistory}` → full report |
| POST | `/api/fix` | same body → fixed file contents + list of changes |
| POST | `/api/export?format=html\|md\|json` | same body → downloadable report |
| POST | `/api/load-path` | `{path, git, base}` → reads code files from a folder on this computer |
| GET / DELETE | `/api/history` | past scores / clear them |

**Security of the server** (good points for a viva):
- It listens on **127.0.0.1 only**, so other computers on the network can't reach it.
- **DNS-rebinding protection:** API calls are accepted only when the Host header is localhost.
- **CSRF protection:** a POST must be `application/json`, so another website can't send a hidden form to it.
- **Path-traversal protection:** frontend files are served only from inside the `frontend` folder.
- A request body is limited to 20 MB, and at most 500 files per review.

---

## 6. Command line version (optional)

```bat
cd backend
cli.bat                              :: interactive menu
cli.bat ..\samples                   :: review a folder
cli.bat ..\samples --fix             :: also write fixed copies
cli.bat ..\samples --fail-on MAJOR   :: exit code 2 if MAJOR+ issues (for CI)
cli.bat --git                        :: only the files you changed
cli.bat --list-rules
cli.bat --explain SQL_INJECTION
cli.bat --help
```

You can hide a single warning with a comment: `// review-ignore` or `// review-ignore: RULE_ID`.

---

## 7. How it works (simple, for the viva)

```
Browser (frontend)  --fetch JSON-->  ApiServer (backend)
                                         |
SourceFile      reads the code and makes a "clean" copy where comments and
                string contents are blanked out, so text inside them can't cause false alarms
MetricsCalculator  finds methods and calculates complexity
                   (1 + number of if / for / while / case / catch / && / || / ?)
ReviewEngine    runs all 50 Rule objects and removes ignored issues
Scorer          turns the issues into a score and grade
AI layer        ReviewSummarizer (offline summary), KnowledgeBase (why + examples),
                ClaudeReviewer (optional), AutoFixer (safe fixes)
Reports         JSON for the website, plus HTML / Markdown / history.csv
```

**Score:** every issue has a weight (CRITICAL 10, MAJOR 5, MINOR 2, INFO 0.5). Add them up, divide by the file size, and turn that into 0-100 with a smooth curve. Any CRITICAL issue caps the score at 69.
Grades: A ≥ 90, B ≥ 80, C ≥ 70, D ≥ 60, F below 60.

**Design patterns:**
- **Strategy:** every rule is its own class using the same `Rule` interface.
- **Template Method:** `BaseRule` / `LinePatternRule` hold the shared code.
- **Layers:** frontend → REST API → service → engine → rules.

**Limits:** it uses smart pattern matching, not a full compiler, so a few false alarms can happen. Auto-fix only changes things that are 100% safe.
