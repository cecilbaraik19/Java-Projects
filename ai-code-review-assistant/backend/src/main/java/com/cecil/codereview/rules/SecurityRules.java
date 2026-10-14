package com.cecil.codereview.rules;

import com.cecil.codereview.analyzer.ReviewContext;
import com.cecil.codereview.analyzer.Rule;
import com.cecil.codereview.analyzer.SourceFile;
import com.cecil.codereview.model.Category;
import com.cecil.codereview.model.Severity;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Rules that find security weaknesses (OWASP style). */
public final class SecurityRules {
    private SecurityRules() { }

    public static List<Rule> all() {
        List<Rule> rules = new ArrayList<>();
        rules.add(new HardcodedSecret());
        rules.add(new SqlInjection());
        rules.add(new CommandInjection());
        rules.add(new LinePatternRule("WEAK_HASH", "Weak hashing algorithm", Category.SECURITY, Severity.MAJOR,
                "MD5 and SHA-1 are broken. Attackers can create collisions or crack hashed passwords quickly.",
                "(?i)(MessageDigest\\.getInstance\\(\\s*\"(MD5|MD2|SHA-?1)\"|hashlib\\.(md5|sha1)\\(|createHash\\(\\s*['\"](md5|sha1)['\"])",
                true, "Weak hash algorithm (MD5/SHA-1) is used.",
                "Use SHA-256 or stronger. For passwords use a slow hash like bcrypt, scrypt, Argon2 or PBKDF2.",
                true, "java", "python", "javascript", "typescript"));
        rules.add(new LinePatternRule("WEAK_CIPHER", "Weak encryption", Category.SECURITY, Severity.MAJOR,
                "DES, RC4 and ECB mode leak patterns in the data and can be broken.",
                "Cipher\\.getInstance\\(\\s*\"(DES|DESede|RC4|RC2|Blowfish|AES\"|[^\"]*/ECB/)",
                true, "Weak cipher or insecure ECB mode is used.",
                "Use \"AES/GCM/NoPadding\" with a random 12-byte IV for each message.", false, "java"));
        rules.add(new InsecureRandom());
        rules.add(new LinePatternRule("INSECURE_DESERIALIZATION", "Unsafe deserialization", Category.SECURITY,
                Severity.MAJOR,
                "Deserializing untrusted data can let an attacker run code on the server.",
                "(new\\s+ObjectInputStream\\s*\\(|\\.readObject\\s*\\(\\s*\\)|pickle\\.loads?\\(|yaml\\.load\\((?![^)]*SafeLoader))",
                false, "Data is deserialized in an unsafe way.",
                "Never deserialize untrusted input. Use JSON with a fixed schema, or an ObjectInputFilter allow-list.",
                false, "java", "python"));
        rules.add(new DisabledTlsValidation());
        rules.add(new PathTraversal());
        rules.add(new SensitiveDataLogged());
        rules.add(new LinePatternRule("DANGEROUS_EVAL", "Use of eval", Category.SECURITY, Severity.CRITICAL,
                "eval() runs any text as code. If the text contains user input, an attacker can run anything.",
                "(?<![\\w.])(eval|exec)\\s*\\(", false, "eval/exec runs dynamic code.",
                "Remove eval. Use JSON.parse / ast.literal_eval or a lookup table instead.",
                false, "javascript", "typescript", "python"));
        rules.add(new LinePatternRule("XSS_INNER_HTML", "Possible XSS", Category.SECURITY, Severity.MAJOR,
                "Setting innerHTML or document.write with dynamic data allows cross-site scripting.",
                "(\\.innerHTML\\s*=(?!\\s*['\"`][^'\"`+]*['\"`]\\s*;?\\s*$)|document\\.write\\s*\\()", false,
                "HTML is built from dynamic data.",
                "Use textContent, or escape/sanitize the data (e.g. DOMPurify) before inserting it.",
                false, "javascript", "typescript"));
        return rules;
    }

    // ------------------------------------------------------------------------

    static final class HardcodedSecret extends BaseRule {
        private static final Pattern ASSIGN = Pattern.compile(
                "(?i)\\b\\w*(password|passwd|pwd|secret|api[_-]?key|apikey|access[_-]?token|auth[_-]?token"
                        + "|private[_-]?key|client[_-]?secret|db[_-]?pass)\\w*\\s*[:=]\\s*[\"'][^\"'\\s]{4,}[\"']");
        private static final Pattern KNOWN = Pattern.compile(
                "(AKIA[0-9A-Z]{16}|-----BEGIN (RSA |EC |DSA )?PRIVATE KEY-----|\\bsk-[A-Za-z0-9_-]{20,}"
                        + "|\\bghp_[A-Za-z0-9]{36}|\\bxox[baprs]-[A-Za-z0-9-]{10,}|AIza[0-9A-Za-z_-]{35})");
        private static final Pattern PLACEHOLDER = Pattern.compile("(?i)[\"'](\\$\\{[^}]+}|<[^>]+>|xxx+|\\*+)[\"']");

        HardcodedSecret() {
            super("HARDCODED_SECRET", "Hard-coded secret", Category.SECURITY, Severity.CRITICAL,
                    "Passwords, API keys and tokens written in the source code end up in Git history and can be stolen.");
        }

        @Override
        public boolean appliesTo(SourceFile file) { return true; }

        @Override
        public void check(SourceFile file, ReviewContext ctx) {
            for (int i = 1; i <= file.lineCount(); i++) {
                String raw = file.rawLine(i);
                if (KNOWN.matcher(raw).find()) {
                    ctx.report(this, i, "A real-looking API key / private key is written in the code.",
                            "Remove it, rotate (change) the key now, and load it from an environment variable or a secrets vault.");
                } else if (ASSIGN.matcher(raw).find() && !PLACEHOLDER.matcher(raw).find()) {
                    ctx.report(this, i, "A password or secret is hard-coded.",
                            "Read it from an environment variable (System.getenv(\"DB_PASSWORD\")) or a config file kept out of Git.");
                }
            }
        }
    }

    static final class SqlInjection extends BaseRule {
        private static final Pattern[] PATTERNS = {
                Pattern.compile("(?i)\"\\s*(select|insert\\s+into|update|delete\\s+from|drop|alter)\\b[^\"]*\"\\s*\\+\\s*[\\w(]"),
                Pattern.compile("(?i)\\b(executeQuery|executeUpdate|execute|addBatch|prepareStatement|createQuery|createNativeQuery|query)\\s*\\([^;]*\"\\s*\\+"),
                Pattern.compile("(?i)\\b(executeQuery|executeUpdate|execute|prepareStatement|createQuery|query)\\s*\\([^;]*\\+\\s*\""),
                Pattern.compile("(?i)String\\.format\\(\\s*\"\\s*(select|insert|update|delete)\\b"),
                Pattern.compile("(?i)\\.execute\\(\\s*f[\"']"),
                Pattern.compile("(?i)\\.execute\\(\\s*[\"'][^\"']*(select|insert|update|delete)[^\"']*[\"']\\s*%"),
                Pattern.compile("(?i)`\\s*(select|insert\\s+into|update|delete\\s+from)\\b[^`]*\\$\\{")
        };

        SqlInjection() {
            super("SQL_INJECTION", "SQL injection", Category.SECURITY, Severity.CRITICAL,
                    "Building SQL by joining strings with user data lets attackers change the query (OWASP A03).");
        }

        @Override
        public boolean appliesTo(SourceFile file) { return !"text".equals(file.language()); }

        @Override
        public void check(SourceFile file, ReviewContext ctx) {
            for (int i = 1; i <= file.lineCount(); i++) {
                if (file.codeLine(i).trim().isEmpty()) continue;
                String raw = file.rawLine(i);
                String code = file.codeLine(i);
                for (Pattern p : PATTERNS) {
                    Matcher m = p.matcher(raw);
                    if (m.find() && isRealCode(raw, code, m.start(), m.end())) {
                        ctx.report(this, i, "SQL query is built by string concatenation / formatting.",
                                "Use a PreparedStatement with ? placeholders: ps = conn.prepareStatement(\"SELECT * FROM users WHERE id = ?\"); ps.setInt(1, id);");
                        break;
                    }
                }
            }
        }

        /** The match must start in real code and its '+' must be a real operator, not text inside a string. */
        private static boolean isRealCode(String raw, String code, int start, int end) {
            if (start < code.length() && !Character.isWhitespace(raw.charAt(start)) && Character.isWhitespace(code.charAt(start))) {
                return false;
            }
            int plus = raw.lastIndexOf('+', end - 1);
            if (plus >= start && plus < code.length()) return code.charAt(plus) == '+';
            return true;
        }
    }

    static final class CommandInjection extends BaseRule {
        private static final Pattern EXEC = Pattern.compile(
                "(Runtime\\.getRuntime\\(\\)\\.exec\\s*\\(|new\\s+ProcessBuilder\\s*\\(|os\\.system\\s*\\(|os\\.popen\\s*\\("
                        + "|subprocess\\.\\w+\\([^)]*shell\\s*=\\s*True|child_process|\\bexecSync\\s*\\()");

        CommandInjection() {
            super("COMMAND_INJECTION", "OS command injection", Category.SECURITY, Severity.CRITICAL,
                    "Running shell commands built from variables lets attackers run their own commands.");
        }

        @Override
        public boolean appliesTo(SourceFile file) { return !"text".equals(file.language()); }

        @Override
        public void check(SourceFile file, ReviewContext ctx) {
            for (int i = 1; i <= file.lineCount(); i++) {
                String code = file.codeLine(i);
                if (!EXEC.matcher(file.rawLine(i)).find() || code.trim().isEmpty()) continue;
                boolean dynamic = code.contains("+") || file.rawLine(i).contains("${") || file.rawLine(i).contains("f\"")
                        || file.rawLine(i).contains("shell=True");
                if (dynamic) {
                    ctx.report(this, i, "A system command is built from variables.",
                            "Pass arguments as a list (new ProcessBuilder(\"ls\", dir)), never through a shell, and validate input against an allow-list.");
                }
            }
        }
    }

    static final class InsecureRandom extends BaseRule {
        private static final Pattern RANDOM = Pattern.compile("(new\\s+(?:java\\.util\\.)?Random\\s*\\(|Math\\.random\\s*\\(|\\brandom\\.(randint|random|choice)\\s*\\()");
        private static final Pattern SECURITY_WORDS = Pattern.compile("(?i)(password|token|otp|salt|secret|session|nonce|captcha|apikey)");

        InsecureRandom() {
            super("INSECURE_RANDOM", "Predictable random numbers", Category.SECURITY, Severity.MAJOR,
                    "java.util.Random / Math.random are predictable. They must not be used for tokens, OTPs or passwords.");
        }

        @Override
        public boolean appliesTo(SourceFile file) { return !"text".equals(file.language()); }

        @Override
        public void check(SourceFile file, ReviewContext ctx) {
            for (int i = 1; i <= file.lineCount(); i++) {
                String code = file.codeLine(i);
                if (!RANDOM.matcher(code).find()) continue;
                // look at a small window around the line for security related names
                StringBuilder window = new StringBuilder();
                for (int l = Math.max(1, i - 3); l <= Math.min(file.lineCount(), i + 3); l++) {
                    window.append(file.codeLine(l)).append('\n');
                }
                if (SECURITY_WORDS.matcher(window).find()) {
                    ctx.report(this, i, "Non-secure random generator used near security-sensitive code.",
                            "Use java.security.SecureRandom (Java), secrets module (Python) or crypto.randomBytes (Node).");
                }
            }
        }
    }

    static final class DisabledTlsValidation extends BaseRule {
        private static final Pattern EMPTY_TRUST = Pattern.compile(
                "check(Server|Client)Trusted\\s*\\([^)]*\\)\\s*(throws[^{]*)?\\{\\s*}");
        private static final Pattern LINE = Pattern.compile(
                "(ALLOW_ALL_HOSTNAME_VERIFIER|NoopHostnameVerifier|setHostnameVerifier\\s*\\([^)]*->\\s*true"
                        + "|verify\\s*=\\s*False|rejectUnauthorized\\s*:\\s*false|NODE_TLS_REJECT_UNAUTHORIZED)");

        DisabledTlsValidation() {
            super("TLS_VALIDATION_DISABLED", "TLS certificate checks disabled", Category.SECURITY, Severity.CRITICAL,
                    "Turning off certificate or hostname checks allows man-in-the-middle attacks.");
        }

        @Override
        public boolean appliesTo(SourceFile file) { return !"text".equals(file.language()); }

        @Override
        public void check(SourceFile file, ReviewContext ctx) {
            Matcher m = EMPTY_TRUST.matcher(file.codeText());
            while (m.find()) {
                ctx.report(this, file.lineOf(m.start()), "TrustManager accepts every certificate.",
                        "Delete the custom TrustManager and use the default SSLContext. Add internal CAs to a trust store instead.");
            }
            for (int i = 1; i <= file.lineCount(); i++) {
                if (!file.codeLine(i).trim().isEmpty() && LINE.matcher(file.rawLine(i)).find()) {
                    ctx.report(this, i, "Certificate or hostname verification is switched off.",
                            "Keep verification on. Fix the certificate problem instead of disabling the check.");
                }
            }
        }
    }

    static final class PathTraversal extends BaseRule {
        private static final Pattern FILE_API = Pattern.compile(
                "(new\\s+File(InputStream|OutputStream|Reader|Writer)?\\s*\\(|Paths\\.get\\s*\\(|Path\\.of\\s*\\(|\\bopen\\s*\\(|readFile(Sync)?\\s*\\()");
        private static final Pattern USER_INPUT = Pattern.compile(
                "(getParameter\\s*\\(|getHeader\\s*\\(|request\\.(args|form|GET|POST|query|params|body)|@RequestParam|input\\s*\\()");

        PathTraversal() {
            super("PATH_TRAVERSAL", "Path traversal", Category.SECURITY, Severity.MAJOR,
                    "Using user input in a file path lets attackers read files like ../../etc/passwd.");
        }

        @Override
        public boolean appliesTo(SourceFile file) { return !"text".equals(file.language()); }

        @Override
        public void check(SourceFile file, ReviewContext ctx) {
            for (int i = 1; i <= file.lineCount(); i++) {
                String code = file.codeLine(i);
                if (FILE_API.matcher(code).find() && USER_INPUT.matcher(code).find()) {
                    ctx.report(this, i, "File path is built from user input.",
                            "Normalize the path, then check it still starts with the allowed base folder: "
                                    + "if (!p.normalize().startsWith(baseDir)) throw new SecurityException();");
                }
            }
        }
    }

    static final class SensitiveDataLogged extends BaseRule {
        private static final Pattern OUTPUT = Pattern.compile(
                "(System\\.(out|err)\\.print|\\blog(ger)?\\.(info|debug|warn|error|trace)\\s*\\(|console\\.log\\s*\\(|\\bprint\\s*\\()");
        private static final Pattern SENSITIVE = Pattern.compile(
                "(?i)\\b(\\w*(password|passwd|secret|token|creditcard|cardnumber|aadhaar)\\w*|pin|ssn|cvv)\\b");

        SensitiveDataLogged() {
            super("SENSITIVE_DATA_LOGGED", "Sensitive data in logs", Category.SECURITY, Severity.MAJOR,
                    "Printing passwords, tokens or card numbers puts them in log files that many people can read.");
        }

        @Override
        public boolean appliesTo(SourceFile file) { return !"text".equals(file.language()); }

        @Override
        public void check(SourceFile file, ReviewContext ctx) {
            for (int i = 1; i <= file.lineCount(); i++) {
                String code = file.codeLine(i);
                if (OUTPUT.matcher(code).find() && SENSITIVE.matcher(code).find()) {
                    ctx.report(this, i, "A sensitive value (password/token/card) is printed or logged.",
                            "Never log secrets. Log a masked value (e.g. ****1234) or just the fact that the event happened.");
                }
            }
        }
    }
}
