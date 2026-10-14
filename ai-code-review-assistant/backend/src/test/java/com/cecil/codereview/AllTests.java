package com.cecil.codereview;

import com.cecil.codereview.ai.AutoFixer;
import com.cecil.codereview.analyzer.ReviewEngine;
import com.cecil.codereview.analyzer.SourceFile;
import com.cecil.codereview.config.ReviewConfig;
import com.cecil.codereview.metrics.MethodInfo;
import com.cecil.codereview.model.FileReport;
import com.cecil.codereview.model.Issue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Built-in tests (no JUnit needed). Run with run-tests.bat.
 * Each test reviews a small piece of code and checks that the right
 * rules fire - and that wrong ones do NOT fire (no false alarms).
 */
public final class AllTests {
    private AllTests() { }

    public static void main(String[] args) {
        boolean ok = run();
        ok &= ApiTests.run();
        System.exit(ok ? 0 : 1);
    }

    private static int passed;
    private static int failed;
    private static final List<String> failures = new ArrayList<>();

    public static boolean run() {
        passed = 0;
        failed = 0;
        failures.clear();
        System.out.println("Running self-tests...\n");

        expect("SQL injection is detected",
                "class A { void f(java.sql.Statement st, String id) throws Exception {\n"
                        + "  st.executeQuery(\"SELECT * FROM t WHERE id=\" + id);\n} }",
                set("SQL_INJECTION"), set());
        expect("PreparedStatement is NOT flagged",
                "class A { void f(java.sql.Connection c, int id) throws Exception {\n"
                        + "  try (var ps = c.prepareStatement(\"SELECT * FROM t WHERE id = ?\")) { ps.setInt(1, id); ps.executeQuery(); }\n} }",
                set(), set("SQL_INJECTION", "RESOURCE_LEAK"));
        expect("Hard-coded password is detected",
                "class A { private String password = \"S3cret!pass\"; String p() { return password; } }",
                set("HARDCODED_SECRET"), set());
        expect("Password from environment is NOT flagged",
                "class A { String password = System.getenv(\"DB_PASSWORD\"); }",
                set(), set("HARDCODED_SECRET"));
        expect("String == literal is detected",
                "class A { boolean f(String s) { return s == \"yes\"; } }",
                set("STRING_EQUALITY"), set());
        expect("== inside a comment or string is NOT flagged",
                "class A { // if (s == \"x\")\n String m = \"a == b\"; int f(int a, int b) { return a == b ? 1 : 0; } }",
                set(), set("STRING_EQUALITY"));
        expect("Empty catch is detected",
                "class A { void f() { try { g(); } catch (RuntimeException e) { } } void g() {} }",
                set("EMPTY_CATCH"), set());
        expect("Catch with explanation comment is NOT flagged",
                "class A { void f() { try { g(); } catch (IllegalStateException e) { /* expected when closed */ } } void g() {} }",
                set(), set("EMPTY_CATCH"));
        expect("Resource leak is detected",
                "class A { String f(String p) throws Exception {\n java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(p));\n return br.readLine(); } }",
                set("RESOURCE_LEAK"), set());
        expect("String += in loop is detected",
                "class A { String f(String[] a) { String s = \"\";\n for (String x : a) {\n s += x;\n }\n return s; } }",
                set("STRING_CONCAT_IN_LOOP"), set());
        expect("Insecure random for OTP is detected",
                "class A { int otp() { int otp = new java.util.Random().nextInt(999999); return otp; } }",
                set("INSECURE_RANDOM"), set());
        expect("Weak hash is detected",
                "class A { void f() throws Exception { java.security.MessageDigest.getInstance(\"MD5\"); } }",
                set("WEAK_HASH"), set());
        expect("Command injection is detected",
                "class A { void f(String host) throws Exception { Runtime.getRuntime().exec(\"ping \" + host); } }",
                set("COMMAND_INJECTION"), set());
        expect("equals without hashCode is detected",
                "class P { int x; @Override public boolean equals(Object o) { return o instanceof P && ((P) o).x == x; } }",
                set("EQUALS_WITHOUT_HASHCODE"), set());
        expect("switch without default is detected",
                "class A { void f(int x) { switch (x) { case 1: g(); break; case 2: g(); break; } } void g() {} }",
                set("SWITCH_NO_DEFAULT"), set());
        expect("Unused import is detected, used import is not",
                "import java.util.List;\nimport java.util.Map;\nclass A { List<String> l; }",
                set("UNUSED_IMPORT"), set());
        expect("Missing braces is detected",
                "class A { int f(int x) {\n if (x > 0)\n  return 1;\n return 0; } }",
                set("MISSING_BRACES"), set());
        expect("High complexity is detected",
                "class A { int f(int a) {\n" + repeat("  if (a > %d && a < 99) a++;\n", 11) + " return a; } }",
                set("HIGH_COMPLEXITY"), set());
        expect("Naming conventions are checked",
                "class bad_name { static final int maxSize = 3; void Do_Thing() { int my_var = 1; } }",
                set("NAMING_CONVENTION"), set());
        expect("TLS checks disabled is detected",
                "class T { public void checkServerTrusted(java.security.cert.X509Certificate[] c, String a) { } }",
                set("TLS_VALIDATION_DISABLED"), set());
        expect("review-ignore suppresses an issue",
                "class A { boolean f(String s) { return s == \"yes\"; } // review-ignore: STRING_EQUALITY\n}",
                set(), set("STRING_EQUALITY"));
        expectFile("Python: bare except and mutable default", "app.py",
                "def add(item, items=[]):\n    try:\n        items.append(item)\n    except:\n        pass\n    return items\n",
                set("BARE_EXCEPT", "MUTABLE_DEFAULT_ARG"), set());
        expectFile("JavaScript: eval and loose equality", "app.js",
                "function run(code) {\n  if (code == '1') { eval(code); }\n}\n",
                set("DANGEROUS_EVAL", "LOOSE_EQUALITY"), set());

        // ---- metrics
        SourceFile mf = SourceFile.fromText("M.java",
                "class M {\n  // comment\n  public int f(int a, int b) {\n    if (a > b && b > 0) { return a; }\n    for (int i = 0; i < a; i++) { b++; }\n    return b;\n  }\n}\n");
        List<MethodInfo> methods = mf.metrics().methods();
        check("Metrics: finds 1 method", methods.size() == 1);
        check("Metrics: complexity = 4", !methods.isEmpty() && methods.get(0).complexity() == 4);
        check("Metrics: 2 parameters", !methods.isEmpty() && methods.get(0).parameterCount() == 2);
        check("Metrics: 1 comment line", mf.metrics().commentLines() == 1);

        // ---- auto-fix
        SourceFile ff = SourceFile.fromText("F.java",
                "import java.util.Map;\nclass F {\n  boolean f(String s, java.util.List<String> l) {   \n    return s == \"ok\" || l.size() == 0;\n  }\n}\n");
        FileReport fr = new ReviewEngine(ReviewConfig.defaults()).reviewFile(ff);
        AutoFixer.FixResult fix = AutoFixer.fix(ff, fr.issues());
        check("AutoFix: string equality fixed", fix.fixedText.contains("\"ok\".equals(s)"));
        check("AutoFix: isEmpty used", fix.fixedText.contains("l.isEmpty()"));
        check("AutoFix: unused import removed", !fix.fixedText.contains("import java.util.Map"));
        check("AutoFix: trailing spaces removed", !fix.fixedText.contains("{   \n"));
        FileReport after = new ReviewEngine(ReviewConfig.defaults()).reviewFile(SourceFile.fromText("F.java", fix.fixedText));
        check("AutoFix: score improves", after.score() > fr.score());

        System.out.println();
        System.out.println("Result: " + passed + " passed, " + failed + " failed.");
        for (String f : failures) System.out.println("  FAILED: " + f);
        return failed == 0;
    }

    private static void expect(String name, String code, Set<String> must, Set<String> mustNot) {
        expectFile(name, "Test.java", code, must, mustNot);
    }

    private static void expectFile(String name, String file, String code, Set<String> must, Set<String> mustNot) {
        FileReport r = new ReviewEngine(ReviewConfig.defaults()).reviewFile(SourceFile.fromText(file, code));
        Set<String> found = new HashSet<>();
        for (Issue i : r.issues()) found.add(i.ruleId());
        Set<String> missing = new HashSet<>(must);
        missing.removeAll(found);
        Set<String> unexpected = new HashSet<>(mustNot);
        unexpected.retainAll(found);
        boolean ok = missing.isEmpty() && unexpected.isEmpty();
        String detail = ok ? "" : "  (missing " + missing + ", unexpected " + unexpected + ", found " + found + ")";
        check(name + detail, ok);
    }

    private static void check(String name, boolean ok) {
        System.out.println((ok ? "  PASS  " : "  FAIL  ") + name);
        if (ok) passed++;
        else {
            failed++;
            failures.add(name);
        }
    }

    private static Set<String> set(String... s) {
        return new HashSet<>(Arrays.asList(s));
    }

    private static String repeat(String template, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(String.format(template, i + 2));
        return sb.toString();
    }
}
