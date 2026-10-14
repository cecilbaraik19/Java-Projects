package com.cecil.codereview.ai;

import java.util.HashMap;
import java.util.Map;

/**
 * Built-in "expert knowledge": for each rule a plain-language explanation
 * and a small before/after example. This is what the offline AI engine uses
 * to explain issues like a senior reviewer would.
 */
public final class KnowledgeBase {
    private KnowledgeBase() { }

    public static final class Advice {
        public final String why;
        public final String before;
        public final String after;

        Advice(String why, String before, String after) {
            this.why = why;
            this.before = before;
            this.after = after;
        }
    }

    private static final Map<String, Advice> ADVICE = new HashMap<>();

    static {
        put("SQL_INJECTION",
                "If a user types  ' OR '1'='1  into a login box, a concatenated query returns every row. "
                        + "Attackers can read, change or delete the whole database.",
                "String sql = \"SELECT * FROM users WHERE name='\" + name + \"'\";\nstmt.executeQuery(sql);",
                "PreparedStatement ps = conn.prepareStatement(\"SELECT * FROM users WHERE name = ?\");\nps.setString(1, name);\nResultSet rs = ps.executeQuery();");
        put("HARDCODED_SECRET",
                "Anyone who can see the code (or the Git history, or the .jar) can read the secret. "
                        + "Bots scan GitHub for keys within minutes of a push.",
                "String password = \"Admin@123\";",
                "String password = System.getenv(\"DB_PASSWORD\");");
        put("COMMAND_INJECTION",
                "With input like  file.txt; rm -rf /  the shell runs a second command chosen by the attacker.",
                "Runtime.getRuntime().exec(\"ping \" + host);",
                "if (!host.matches(\"[a-zA-Z0-9.-]+\")) throw new IllegalArgumentException();\nnew ProcessBuilder(\"ping\", \"-c\", \"1\", host).start();");
        put("WEAK_HASH",
                "MD5/SHA-1 hashes of common passwords are in public lookup tables, so they can be reversed instantly.",
                "MessageDigest md = MessageDigest.getInstance(\"MD5\");",
                "MessageDigest md = MessageDigest.getInstance(\"SHA-256\");  // for passwords use bcrypt/PBKDF2");
        put("INSECURE_RANDOM",
                "java.util.Random is seeded from the clock, so an attacker can guess the next OTP or token.",
                "int otp = new Random().nextInt(900000) + 100000;",
                "SecureRandom rnd = new SecureRandom();\nint otp = rnd.nextInt(900000) + 100000;");
        put("STRING_EQUALITY",
                "== checks if two variables point to the same object. Strings read from input are new objects, "
                        + "so \"admin\" == input is usually false even when the text matches.",
                "if (role == \"admin\") { ... }",
                "if (\"admin\".equals(role)) { ... }");
        put("EMPTY_CATCH",
                "When something fails, nobody finds out. The program continues in a broken state and the real "
                        + "cause is very hard to find later.",
                "try { save(); } catch (IOException e) { }",
                "try { save(); } catch (IOException e) {\n    LOG.log(Level.SEVERE, \"Could not save file\", e);\n    throw new UncheckedIOException(e);\n}");
        put("RESOURCE_LEAK",
                "If an exception happens before close(), the file stays open. After enough leaks the program "
                        + "hits 'Too many open files' and crashes.",
                "BufferedReader br = new BufferedReader(new FileReader(path));\nString line = br.readLine();",
                "try (BufferedReader br = new BufferedReader(new FileReader(path))) {\n    String line = br.readLine();\n}");
        put("STRING_CONCAT_IN_LOOP",
                "Strings are immutable. Every += creates a new copy, so 10,000 iterations copy millions of characters.",
                "String s = \"\";\nfor (String w : words) s += w;",
                "StringBuilder sb = new StringBuilder();\nfor (String w : words) sb.append(w);\nString s = sb.toString();");
        put("HIGH_COMPLEXITY",
                "Each if/loop doubles the number of paths. Complexity above 10 means the method is hard to test fully "
                        + "and bugs hide in the paths nobody tried.",
                "if (a) { if (b) { ... } else { ... } } else if (c) { ... }",
                "if (!a) return handleNotA();\nif (!b) return handleNotB();\nreturn handleAll();");
        put("LONG_METHOD",
                "A method should do one thing. Long methods mix several jobs and are hard to name, test and reuse.",
                "void processOrder() { /* validate ... 80 lines ... save ... email ... */ }",
                "void processOrder() {\n    validate(order);\n    save(order);\n    sendConfirmation(order);\n}");
        put("EQUALS_WITHOUT_HASHCODE",
                "HashSet/HashMap look in the bucket chosen by hashCode() first. Equal objects with different "
                        + "hash codes are never found.",
                "public boolean equals(Object o) { ... }",
                "public boolean equals(Object o) { ... }\n@Override public int hashCode() { return Objects.hash(id, name); }");
        put("TLS_VALIDATION_DISABLED",
                "Anyone on the same Wi-Fi can pretend to be your server and read or change all traffic.",
                "public void checkServerTrusted(X509Certificate[] c, String a) { }",
                "// remove the custom TrustManager; import the server's CA into a trust store instead");
        put("SENSITIVE_DATA_LOGGED",
                "Log files are copied to many systems and kept for a long time. Secrets in logs are a common breach cause.",
                "System.out.println(\"Login \" + user + \" pwd=\" + password);",
                "LOG.info(\"Login attempt for user \" + user);");
        put("CATCH_GENERIC_EXCEPTION",
                "catch (Exception e) also catches bugs like NullPointerException, hiding them behind a generic message.",
                "catch (Exception e) { showError(); }",
                "catch (IOException | SQLException e) { showError(); LOG.log(Level.WARNING, \"...\", e); }");
        put("MISSING_BRACES",
                "Adding a second statement later looks like it is inside the if but it always runs.",
                "if (isAdmin)\n    grantAccess();\n    logAccess();   // always runs!",
                "if (isAdmin) {\n    grantAccess();\n    logAccess();\n}");
        put("MAGIC_NUMBER",
                "A reader can't tell what 86400 or 3 means, and changing it means hunting every copy.",
                "if (attempts > 3) lock();",
                "private static final int MAX_LOGIN_ATTEMPTS = 3;\nif (attempts > MAX_LOGIN_ATTEMPTS) lock();");
        put("FLOAT_MONEY",
                "0.1 + 0.2 is 0.30000000000000004 in double. Over many transactions the totals become wrong.",
                "double balance = 0.1 + 0.2;",
                "BigDecimal balance = new BigDecimal(\"0.1\").add(new BigDecimal(\"0.2\"));");
        put("SWITCH_NO_DEFAULT",
                "When a new value appears later, the switch silently does nothing and the bug is invisible.",
                "switch (type) { case 1: ...; break; case 2: ...; break; }",
                "switch (type) { case 1: ...; break; case 2: ...; break;\n    default: throw new IllegalArgumentException(\"Unknown type: \" + type); }");
        put("DUPLICATE_CODE",
                "A bug fixed in one copy stays alive in the other copies.",
                "// same 8 lines in deposit() and withdraw()",
                "private void validateAmount(BigDecimal amount) { ... }  // called from both");
        put("PRINT_STACK_TRACE",
                "In a server or GUI app the console is not visible, so the error is effectively lost.",
                "catch (IOException e) { e.printStackTrace(); }",
                "catch (IOException e) { LOG.log(Level.SEVERE, \"Failed to read config\", e); }");
        put("ASSIGNMENT_IN_CONDITION",
                "if (x = 5) assigns instead of comparing. In Java it only compiles for booleans, which makes the bug sneaky.",
                "if (isValid = true) { ... }",
                "if (isValid) { ... }");
        put("PATH_TRAVERSAL",
                "A request for  ../../../etc/passwd  escapes the upload folder and reads system files.",
                "File f = new File(\"uploads/\" + request.getParameter(\"name\"));",
                "Path base = Paths.get(\"uploads\").toAbsolutePath().normalize();\nPath p = base.resolve(name).normalize();\nif (!p.startsWith(base)) throw new SecurityException(\"Bad path\");");
        put("INSECURE_DESERIALIZATION",
                "Java deserialization can run code from 'gadget' classes on the classpath - this caused many real breaches.",
                "Object o = new ObjectInputStream(socket.getInputStream()).readObject();",
                "// send JSON instead, and map it to a known class\nUser u = mapper.readValue(json, User.class);");
    }

    private static void put(String id, String why, String before, String after) {
        ADVICE.put(id, new Advice(why, before, after));
    }

    /** Returns advice for a rule, or null if there is no specific entry. */
    public static Advice forRule(String ruleId) {
        return ADVICE.get(ruleId);
    }
}
