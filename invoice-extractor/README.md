# Bahi – AI-Based Invoice Data Extractor

**Extract data from invoices automatically.** Upload a supplier bill (a text file, a PDF made by billing software, or –
with Claude switched on – a photo or scan) and Bahi pulls out the **vendor, GSTIN, invoice number, invoice and due
dates, PO number, line items (description, HSN/SAC, qty, rate, amount), CGST / SGST / IGST, total and amount in words**.
Every value comes with a **confidence score** and the **line it was read from**, highlighted on the bill.
**Eleven cross-checks** catch misread figures, wrong GST and duplicate bills. When a person corrects a field and approves
the bill, Bahi **learns** – it remembers the words that vendor prints in front of each field, and a **Naive Bayes**
classifier learns the expense category. Approved bills go into a **ledger** that exports to CSV / JSON for accounts.

Plain **Java 17** backend (no external libraries) and a plain **HTML / CSS / JavaScript** frontend, the same setup as
the other projects in this folder. **Light mode and dark mode** are both built in.

The look is a new **"ledger & rubber stamp"** theme: the sidebar is the red cloth binding of a *bahi-khata* (the
traditional Indian account book) with gold stitching, pages are pale-green ruled ledger paper with a red double margin,
bill statuses are **rubber stamps** in four inks (*To review*, *Approved*, *Auto-checked*, *Rejected*, *Exported*), the
bill itself is shown as a torn paper slip with **highlighter marks** (yellow = numbers, blue = dates, pink = parties,
green = amounts, orange = tax), and confidence is an **ink level**. Switch with the **Dark mode / Light mode** button at the top right of every page. Dark mode is blue-black
paper with violet ink.

## Run it

You only need **Java 17 or newer** (JDK). No internet, no database, no npm.

| How | What to do |
|---|---|
| Windows | Double-click `run.bat` |
| Mac / Linux | `./run.sh` |
| IntelliJ | Open this folder, open `backend/src/main/java/com/cecil/invoice/App.java`, click Run |
| Maven | `cd backend` then `mvn package` and `java -jar target/invoice-extractor.jar` |

Then open **http://localhost:8080** in your browser. (Set `PORT` to use another port.)

### Demo logins

| Username | Password | Role | What they can do |
|---|---|---|---|
| meera | accounts123 | Accountant | Everything with bills: review, correct, approve, reject, reopen, export, edit vendors, history |
| ravi, sunil | clerk123 | Clerk | Upload bills and correct what was read (cannot approve or export) |
| admin | admin123 | Admin | Everything, plus logins, settings and retraining the category model |

On the first run the app reads about **55 demo invoices** from **10 made-up suppliers** over the last four months
(stationery, computers, cloud software, broadband, a CA firm, travel, repairs, catering, water, office rent) in
**four different layouts**. They are really read by the extractor and then "reviewed" like an accountant would:
wrong fields corrected, bad bills rejected (a wrong total, a wrong GSTIN, CGST charged on an inter-state supply,
a duplicate scan), good ones approved and exported. So the learning and all the numbers on the dashboard are real.
About 7 bills are left in the **Review Tray** for you. Dates are counted from today. All companies and GSTINs are made up.
To start fresh, stop the app and delete the `data` folder.

### Try it

1. Sign in as **meera** → **Scan Desk** → pick a sample on the right (e.g. *Print shop – a new vendor*) → **Read this bill**.
2. On the review screen, click a field's label to see where it was found on the bill. Fix the category, press **Approve**.
3. Upload `samples/03-ca-firm-bill-ref.pdf` – Mehta & Associates prints **"Bill Ref"** instead of "Invoice No".
   Bahi learned that from a correction in the demo data, and reads it by itself (*"learned label for this vendor"*).
4. Try *Total does not add up* or *Wrong GSTIN check digit* – the checks fail and approval needs a note.
5. **Ledger** → **Export to accounts** downloads the register and stamps the bills *Exported*.

The `samples` folder has 9 `.txt` bills and 4 `.pdf` bills for uploading.

## How the reader works ("Local AI")

| Step | What happens |
|---|---|
| 1. Clean | Split into lines; repair scan mistakes inside numbers (`1,O8O.0O` → `1,080.00`) but never inside a GSTIN or invoice number |
| 2. Find labels | About 80 label phrases ("Invoice No", "Bill No", "Dated", "Taxable Value", "Grand Total", "Amount Payable"...). Matched with **Levenshtein fuzzy matching**, so "lnvoice N0" and "Grand Tota1" still count. Where labels overlap, the longest wins ("Grand Total" beats "Total") |
| 3. Candidates | For each label, the value is taken to the right; or, for table-style headings, from the line below **in the same column** |
| 4. Score | `score = 0.55 × label strength + position (same line 0.30, below 0.27, next line 0.18) + right shape 0.12 + learned-label bonus 0.14`. The best candidate wins; its score is the confidence |
| 5. Agree | The subtotal that equals the sum of the items, and the total that equals subtotal + GST, get +0.20. Missing values are worked out (due date from "Net 30", tax from CGST + SGST) |
| 6. Items | Find the header row (Description / Qty / Rate / Amount / HSN), read rows until "Sub Total"; try every choice of 3 numbers to find qty × rate = amount |
| 7. Vendor | Known by GSTIN or by a fuzzy name match (≥ 85 % alike). Buyer vs seller GSTIN is decided by the "Bill To" block and our own GSTIN |
| 8. Category | Multinomial **Naive Bayes** on the item words (+ the vendor's usual category, 40 %) |

**PDF:** the PDF content stream is unzipped (FlateDecode / ASCII85) and the text drawing commands (`Tj`, `TJ`, `Tm`, `Td`)
are read with their x/y position, so columns stay lined up. Scanned PDFs have no text – use Claude for those.

### The 11 checks

Key fields found · items add up to the subtotal · qty × rate = amount · subtotal + tax = total (round-off allowed) ·
vendor GSTIN check character · billed to our GSTIN · CGST+SGST inside a state / IGST between states · standard GST rate
(5/12/18/28 %) · dates make sense · amount in words = total (Indian lakh/crore words) · not a duplicate.

Overall confidence = weighted average of the key fields' confidence, −4 % per warning, −15 % per failed check.

### Auto-check

Bills are approved without a person only when **all** are true (admin can change these in Settings): the vendor is
already known, every check passes, confidence ≥ 95 %, total ≤ ₹25,000, the category guess is ≥ 60 % sure.

### Learning from corrections

When a bill with corrected fields is approved, Bahi finds where the right value is printed, takes the words in front
of it (or above it, in the same column) and saves them for that vendor – e.g. Mehta & Associates: `invoiceNo → "bill ref"`.
The approved bill's words also train the category model. See **How Bahi Reads** in the app.

## Optional: Claude for photos and scans

Set an Anthropic API key before starting, then turn Claude on in **Settings**:

```
Windows:  set ANTHROPIC_API_KEY=sk-ant-...      then run.bat
Mac/Linux: export ANTHROPIC_API_KEY=sk-ant-...  then ./run.sh
```

The bill (text, PDF or image) is sent to the Messages API with a prompt asking for one JSON object. Claude's answer is
put through the **same 11 checks** – it is never trusted blindly. If Claude cannot be reached, the built-in reader is used.

## Run the tests

`run-tests.bat` (Windows) or `./run-tests.sh` (Mac / Linux). **29 tests**, with the clock fixed at 1 Nov 2026:
money / dates / GSTIN / words / fuzzy helpers; every key field on 6 layouts × 4 random bills; a poor scan; item rows;
PDF reading; each kind of bad bill caught; duplicates; Naive Bayes; learning "Bill Ref"; auto-check rules; approve with
override; Claude's JSON; and the web server (sign-in, a clerk cannot approve, upload).

## Security

Passwords hashed with PBKDF2-SHA256 + salt · HttpOnly SameSite session cookie, 12-hour idle limit · 5 wrong passwords
lock for 5 minutes · roles checked on the server for every API call · CSRF origin check · Content-Security-Policy and
other security headers · uploads limited to 8 MB · CSV export protected against formula injection · server listens only
on 127.0.0.1.

## Folders

```
invoice-extractor/
├── run.bat / run.sh, run-tests.bat / run-tests.sh
├── samples/                          9 .txt and 4 .pdf made-up bills to upload
├── frontend/                         index.html, app.css, app.js, theme.js, favicon.svg
├── backend/src/main/java/com/cecil/invoice/
│   ├── App.java                      starts everything
│   ├── extract/                      LocalExtractor (the reader), ItemsParser, PdfText, Classifier (Naive Bayes),
│   │                                 Validator (11 checks), ClaudeExtractor
│   ├── service/                      InvoiceService (upload → read → check → review → approve → export),
│   │                                 LearningService, DashboardService, AuthService, UserService
│   ├── store/                        DataStore (JSON files), SeedData, SampleInvoices (bill generator)
│   ├── model/                        Invoice, FieldValue, LineItem, Check, Vendor, User, Settings ...
│   ├── util/                         Money, Dates, Gstin, Words, Fuzzy, Json, Csv, Passwords, Text
│   └── web/                          ApiServer (routes), Http
└── data/                             created on first run (invoices.json, vendors.json, model.json ...)
```
