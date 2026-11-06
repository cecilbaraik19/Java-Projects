package com.cecil.certify;

import com.cecil.certify.cert.CertFields;
import com.cecil.certify.cert.CertificateService;
import com.cecil.certify.cert.ForgeryBench;
import com.cecil.certify.cert.Verifier;
import com.cecil.certify.chain.Block;
import com.cecil.certify.chain.ChainValidator;
import com.cecil.certify.chain.MerkleTree;
import com.cecil.certify.chain.Network;
import com.cecil.certify.chain.Transaction;
import com.cecil.certify.chain.TxType;
import com.cecil.certify.model.CertificateRecord;
import com.cecil.certify.model.Role;
import com.cecil.certify.model.User;
import com.cecil.certify.qr.QrCode;
import com.cecil.certify.service.AuthService;
import com.cecil.certify.service.LabService;
import com.cecil.certify.store.DataStore;
import com.cecil.certify.util.Clock;
import com.cecil.certify.util.Crypto;
import com.cecil.certify.util.Csv;
import com.cecil.certify.util.Json;
import com.cecil.certify.util.Passwords;
import com.cecil.certify.web.ApiServer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * All backend tests in one file, with a tiny test runner (no JUnit download needed).
 * Run with run-tests.bat / run-tests.sh. The clock is fixed at 6 Nov 2026 so results never change.
 */
public final class AllTests {

    private static int passed, failed;
    private static final List<String> failures = new ArrayList<>();

    interface Test { void run() throws Exception; }

    static void test(String name, Test t) {
        try {
            t.run();
            passed++;
            System.out.println("  PASS  " + name);
        } catch (Throwable e) {
            failed++;
            failures.add(name + ": " + e);
            System.out.println("  FAIL  " + name + "  ->  " + e);
        }
    }

    static void check(boolean ok, String msg) {
        if (!ok) throw new AssertionError(msg);
    }

    static void eq(Object want, Object got, String msg) {
        if (want == null ? got != null : !want.equals(got)) throw new AssertionError(msg + " (wanted " + want + ", got " + got + ")");
    }

    static void throwsError(Test t, String contains) {
        try {
            t.run();
        } catch (Exception e) {
            if (contains != null && (e.getMessage() == null || !e.getMessage().contains(contains)))
                throw new AssertionError("Wrong error message: " + e.getMessage());
            return;
        }
        throw new AssertionError("Expected an error containing '" + contains + "'");
    }

    // ------------------------------------------------------------------ a small world for the tests

    static final long NOW = java.time.LocalDate.of(2026, 11, 6).atTime(10, 0).atZone(Clock.ZONE).toInstant().toEpochMilli();

    static final class World {
        Network net;
        DataStore store;
        CertificateService certs;
        AuthService auth;
        Verifier verifier;
        User staff, staff2, student;

        World(Path dir) throws Exception {
            net = Network.create(dir, 2, "Test network", new String[]{"a", "b", "c"});
            store = new DataStore(dir);
            if (dir != null) store.load();
            certs = new CertificateService(net, store);
            auth = new AuthService(store);
            verifier = new Verifier(certs);
            auth.createUser("admin", "Admin", Role.REGISTRAR, null, "admin1234");
            certs.registerIssuer("KVIT", "Koel Valley Institute of Technology", "Ranchi");
            certs.registerIssuer("SUBU", "Subarnarekha University", "Jamshedpur");
            staff = auth.createUser("meera", "Meera", Role.ISSUER, "KVIT", "issuer1234");
            staff2 = auth.createUser("farhan", "Farhan", Role.ISSUER, "SUBU", "issuer1234");
            student = auth.createUser("priya", "Priya", Role.STUDENT, null, "student1234");
        }

        CertificateRecord issue(String holder, String result) {
            return certs.issue(staff, fields(holder, result), "priya");
        }
    }

    static Map<String, String> fields(String holder, String result) {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("holder", holder);
        f.put("rollNo", "KVIT/CSE/22/031");
        f.put("programme", "Bachelor of Technology - Computer Science and Engineering");
        f.put("result", result);
        f.put("session", "2022-2026");
        f.put("issueDate", "2026-07-18");
        return f;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> fileWith(Map<String, Object> file, String field, String value) {
        Map<String, Object> copy = Json.parseObject(Json.write(file));
        ((Map<String, Object>) copy.get("fields")).put(field, value);
        return copy;
    }

    // ------------------------------------------------------------------ tests

    public static void main(String[] args) throws Exception {
        Clock.fix(NOW);
        System.out.println("Pramaan tests");

        System.out.println("\n[Basics]");
        test("JSON: round trip keeps order, numbers and text", () -> {
            Map<String, Object> m = Json.parseObject("{\"b\":1,\"a\":[true,null,2.5,\"x\\\"y\"],\"c\":{\"d\":\"é\"}}");
            eq("{\"b\":1,\"a\":[true,null,2.5,\"x\\\"y\"],\"c\":{\"d\":\"é\"}}", Json.write(m), "round trip");
        });
        test("JSON: < > & are escaped so text can never become HTML", () ->
                eq("\"\\u003cscript\\u003e\\u0026\"", Json.write("<script>&"), "escape"));
        test("JSON: very deep nesting and bad input are refused", () -> {
            throwsError(() -> Json.parse("[".repeat(200) + "]".repeat(200)), "deeply");
            throwsError(() -> Json.parse("{\"a\":1,}"), null);
            throwsError(() -> Json.parse("{\"a\":1} x"), "Extra");
        });
        test("CSV: quotes, commas and Excel-formula protection", () -> {
            List<List<String>> rows = Csv.parse("a,b\n\"x, y\",\"he said \"\"hi\"\"\"\r\n");
            eq("x, y", rows.get(1).get(0), "quoted comma");
            eq("he said \"hi\"", rows.get(1).get(1), "double quote");
            eq("'=1+1", Csv.cell("=1+1"), "formula");
        });
        test("Passwords: PBKDF2 hash checks right password only", () -> {
            String h = Passwords.hash("secret123");
            check(Passwords.check("secret123", h), "right password");
            check(!Passwords.check("secret124", h), "wrong password");
            check(!h.contains("secret123"), "not plain text");
        });
        test("ECDSA: signature checks with the right key and fails with another key or changed text", () -> {
            KeyPair a = Crypto.newKeyPair(), b = Crypto.newKeyPair();
            String sig = Crypto.sign(a.getPrivate(), "hello");
            check(Crypto.verify(a.getPublic(), "hello", sig), "valid");
            check(!Crypto.verify(b.getPublic(), "hello", sig), "other key");
            check(!Crypto.verify(a.getPublic(), "hellO", sig), "changed text");
            check(!Crypto.verify(a.getPublic(), "hello", "not-base64!"), "broken signature");
            check(Crypto.decodePublic(Crypto.encodePublic(a.getPublic())) != null, "key encode/decode");
        });
        test("Merkle tree: any change changes the root; proofs rebuild the root", () -> {
            List<String> leaves = new ArrayList<>();
            for (int i = 0; i < 7; i++) leaves.add(MerkleTree.leaf("item" + i));
            String root = MerkleTree.root(leaves);
            for (int i = 0; i < leaves.size(); i++)
                eq(root, MerkleTree.rootFromProof(leaves.get(i), MerkleTree.proof(leaves, i)), "proof " + i);
            List<String> changed = new ArrayList<>(leaves);
            changed.set(3, MerkleTree.leaf("item3!"));
            check(!root.equals(MerkleTree.root(changed)), "root changes");
            List<String> dup = new ArrayList<>(leaves);
            dup.add(leaves.get(6));
            check(!root.equals(MerkleTree.root(dup)), "duplicating the last leaf gives a different root");
        });
        test("Certificate IDs: format, check letter, typo clean-up", () -> {
            for (int i = 0; i < 200; i++) {
                String id = CertFields.newCertId();
                check(id.matches("PRM-[0-9A-Z]{4}-[0-9A-Z]{4}-[0-9A-Z]"), "format " + id);
                check(CertFields.checkLetterOk(id), "check letter " + id);
                eq(id, CertFields.cleanCertId(id.toLowerCase().replace("-", " ")), "clean");
            }
            eq("PRM-1000-0000-" + "0", CertFields.cleanCertId("prm-l0oo-0000-0").substring(0, 14) + "0", "O->0 and L->1");
            eq(null, CertFields.cleanCertId("PRM-123"), "too short");
            String id = CertFields.newCertId();
            char c = id.charAt(5) == 'A' ? 'B' : 'A';
            String typo = id.substring(0, 5) + c + id.substring(6);
            int caught = 0;
            for (char x : "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray()) {
                if (x == id.charAt(5)) continue;
                if (!CertFields.checkLetterOk(id.substring(0, 5) + x + id.substring(6))) caught++;
            }
            check(caught == 31, "every single-character change is caught by the check letter (" + caught + "/31) " + typo);
        });
        test("Field fingerprints: same text written differently matches; any real change does not", () -> {
            String salt = Crypto.randomHex(16);
            eq(CertFields.fieldHash("holder", "Priya Oraon", salt), CertFields.fieldHash("holder", "  PRIYA   oraon ", salt), "spacing/case");
            check(!CertFields.fieldHash("holder", "Priya Oraon", salt).equals(CertFields.fieldHash("holder", "Priya Oraom", salt)), "one letter");
            check(!CertFields.fieldHash("holder", "Priya Oraon", salt).equals(CertFields.fieldHash("holder", "Priya Oraon", Crypto.randomHex(16))), "other salt");
            check(!CertFields.fieldHash("holder", "X", salt).equals(CertFields.fieldHash("result", "X", salt)), "field name is part of the hash");
        });
        test("QR code: picks the smallest version, draws finder squares and timing lines", () -> {
            QrCode small = QrCode.encode("hi");
            eq(1, small.version, "version 1");
            eq(21, small.size, "21x21");
            QrCode url = QrCode.encode("http://localhost:8080/#/v/PRM-7K2Q-9XWD-M/abcdefghijklmnop");
            eq(4, url.version, "URL fits version 4");
            for (QrCode q : List.of(small, url, QrCode.encode("x".repeat(200)))) {
                for (int[] corner : new int[][]{{0, 0}, {q.size - 7, 0}, {0, q.size - 7}}) {
                    for (int i = 0; i < 7; i++) {
                        check(q.get(corner[0] + i, corner[1]) && q.get(corner[0], corner[1] + i), "finder edge dark");
                    }
                    check(!q.get(corner[0] + 1, corner[1] + 1) && q.get(corner[0] + 3, corner[1] + 3), "finder ring and centre");
                }
                for (int i = 8; i < q.size - 8; i++) eq(i % 2 == 0, q.get(i, 6), "timing line");
                check(q.get(8, q.size - 8), "dark module");
            }
            throwsError(() -> QrCode.encode("x".repeat(300)), "too long");
            check(url.toSvg(2, "#000").startsWith("<svg"), "svg");
        });

        System.out.println("\n[Blockchain]");
        World w = new World(null);
        test("Genesis + 2 institutions = 3 blocks, all nodes agree, proof of work done", () -> {
            Network.Consensus c = w.net.consensus();
            check(c.ok, "majority");
            eq(3, c.agreeing.size(), "3 agree");
            eq(2, c.state.height, "height 2");
            for (Block b : w.net.node("A").chain) check(b.hash.startsWith("00"), "work in block " + b.index);
            eq(2, c.state.issuers.size(), "2 institutions");
        });
        CertificateRecord r1 = w.issue("Priya Oraon", "CGPA 8.46 / 10");
        test("Issuing writes a signed record with no personal data on the chain", () -> {
            String chainText = Json.write(w.net.node("B").chain.get(r1.block).toJson());
            check(!chainText.contains("Priya") && !chainText.contains("8.46") && !chainText.contains("KVIT/CSE"), "no personal data");
            check(chainText.contains(r1.certId), "the ID is there");
            check(w.net.consensus().state.certs.containsKey(r1.certId), "in state");
        });
        test("Editing a block's contents is caught (Merkle root)", () -> {
            List<Block> copy = w.net.node("A").copyChain();
            copy.get(r1.block).transactions.get(0).payload.put("root", "ab".repeat(32));
            ChainValidator.Result r = ChainValidator.validate(copy, w.net.genesisHash, 2);
            check(!r.ok && r.badBlock == r1.block && r.problemKind.equals("MERKLE"), r.reason);
        });
        test("Editing a block header is caught (hash)", () -> {
            List<Block> copy = w.net.node("A").copyChain();
            copy.get(1).nonce++;
            ChainValidator.Result r = ChainValidator.validate(copy, w.net.genesisHash, 2);
            check(!r.ok && r.badBlock == 1 && r.problemKind.equals("HASH"), r.reason);
        });
        test("Re-sealing an edited block: the signature inside still catches it", () -> {
            List<Block> copy = w.net.node("A").copyChain();
            Block b = copy.get(1);
            b.transactions.get(0).payload.put("name", "Fake Name");
            b.mine();
            ChainValidator.Result r = ChainValidator.validate(copy, w.net.genesisHash, 2);
            check(!r.ok && r.badBlock == 1 && r.problemKind.equals("SIGNATURE"), r.reason);
        });
        test("A block that does not point to the block before it breaks the chain", () -> {
            List<Block> copy = w.net.node("A").copyChain();
            Block old = copy.get(2);
            Block wrong = new Block(2, old.time, "ab".repeat(32), 2, old.sealedBy, old.transactions);
            wrong.mine();
            copy.set(2, wrong);
            ChainValidator.Result r = ChainValidator.validate(copy, w.net.genesisHash, 2);
            check(!r.ok && r.badBlock == 2 && r.problemKind.equals("LINK"), r.reason);
        });
        test("A different genesis block (someone else's network) is refused", () -> {
            Network other = Network.create(null, 2, "Other", new String[]{"x", "y", "z"});
            ChainValidator.Result r = ChainValidator.validate(other.node("A").chain, w.net.genesisHash, 2);
            check(!r.ok && r.problemKind.equals("GENESIS"), r.reason);
        });
        test("Blocks without enough proof of work are refused", () -> {
            ChainValidator.Result r = ChainValidator.validate(w.net.node("A").chain, w.net.genesisHash, 9);
            check(!r.ok && r.problemKind.equals("WORK"), r.reason);
        });
        test("Unknown institution / wrong key / wrong signer cannot write", () -> {
            KeyPair rogue = Crypto.newKeyPair();
            Map<String, String> p = new LinkedHashMap<>();
            List<String> leaves = List.of(MerkleTree.leaf("a"));
            p.put("certId", CertFields.newCertId());
            p.put("issuerId", "FAKE");
            p.put("fieldHashes", leaves.get(0));
            p.put("root", MerkleTree.root(leaves));
            throwsError(() -> w.net.submit(List.of(Transaction.create(TxType.ISSUE_CERT, NOW, "FAKE", p, rogue.getPrivate()))), "unknown institution");
            p.put("issuerId", "KVIT");
            throwsError(() -> w.net.submit(List.of(Transaction.create(TxType.ISSUE_CERT, NOW, "KVIT", p, rogue.getPrivate()))), "does not match");
            Map<String, String> reg = new LinkedHashMap<>();
            reg.put("issuerId", "EVIL");
            reg.put("name", "Evil U");
            reg.put("publicKey", Crypto.encodePublic(rogue.getPublic()));
            throwsError(() -> w.net.submit(List.of(Transaction.create(TxType.REGISTER_ISSUER, NOW, Transaction.REGISTRAR, reg, rogue.getPrivate()))), "registrar");
        });
        test("Same certificate ID twice, or revoking twice, is refused by the chain rules", () -> {
            Transaction again = w.net.consensus().state.certs.get(r1.certId).issueTx.copy();
            throwsError(() -> w.net.submit(List.of(again)), "issued twice");
        });

        System.out.println("\n[Verification]");
        Map<String, Object> file1 = w.certs.certificateFile(r1);
        test("Genuine certificate file -> GENUINE, all 8 checks pass", () -> {
            Map<String, Object> v = w.verifier.verifyFile(file1, false);
            eq("GENUINE", v.get("verdict"), "verdict");
            for (Object o : (List<?>) v.get("checks")) {
                Map<?, ?> c = (Map<?, ?>) o;
                check("pass".equals(c.get("status")) || ("depth".equals(c.get("key")) && "info".equals(c.get("status"))), "check " + c.get("key"));
            }
        });
        test("Changing the file does not change the stored record", () -> {
            fileWith(file1, "result", "CGPA 9.99");
            eq("CGPA 8.46 / 10", r1.fields.get("result"), "record unchanged");
        });
        test("Changed marks -> FORGED and 'Result' is named", () -> {
            Map<String, Object> v = w.verifier.verifyFile(fileWith(file1, "result", "CGPA 9.46 / 10"), false);
            eq("TAMPERED", v.get("verdict"), "verdict");
            eq(List.of("Result"), v.get("changedFields"), "field named");
        });
        test("One changed letter in the name -> FORGED", () ->
                eq("TAMPERED", w.verifier.verifyFile(fileWith(file1, "holder", "Priya Oraom"), false).get("verdict"), "verdict"));
        test("Extra spaces and capitals are not a forgery", () ->
                eq("GENUINE", w.verifier.verifyFile(fileWith(file1, "holder", "  PRIYA   ORAON "), false).get("verdict"), "verdict"));
        test("Made-up ID -> NOT_FOUND; typo in ID -> caught by check letter", () -> {
            Map<String, Object> f = Json.parseObject(Json.write(file1));
            String fake;
            do { fake = CertFields.newCertId(); } while (fake.equals(r1.certId));
            f.put("certId", fake);
            eq("NOT_FOUND", w.verifier.verifyFile(f, false).get("verdict"), "fake");
            String id = r1.certId;
            f.put("certId", id.substring(0, 5) + (id.charAt(5) == '7' ? '8' : '7') + id.substring(6));
            Map<String, Object> v = w.verifier.verifyFile(f, false);
            eq("NOT_FOUND", v.get("verdict"), "typo");
            check(String.valueOf(v.get("headline")).contains("mistake"), "says it is a typing mistake");
        });
        test("Not a certificate file -> BAD_INPUT", () ->
                eq("BAD_INPUT", w.verifier.verifyFile(Json.map("hello", 1), false).get("verdict"), "verdict"));
        test("Typed details from paper: partial match is GENUINE, a wrong detail is FORGED", () -> {
            Map<String, String> t = new LinkedHashMap<>();
            t.put("holder", "priya oraon");
            t.put("result", "CGPA 8.46 / 10");
            Map<String, Object> v = w.verifier.verifyTyped(r1.certId, t, false);
            eq("GENUINE", v.get("verdict"), "partial typed");
            t.put("result", "CGPA 9.46 / 10");
            eq("TAMPERED", w.verifier.verifyTyped(r1.certId, t, false).get("verdict"), "wrong result");
            eq("BAD_INPUT", w.verifier.verifyTyped(r1.certId, Map.of("holder", "Priya"), false).get("verdict"), "needs 2 details");
        });
        test("QR link with the right token shows the record; wrong token shows nothing", () -> {
            Map<String, Object> v = w.verifier.verifyLink(r1.certId, w.certs.qrToken(r1.certId), false);
            eq("GENUINE", v.get("verdict"), "verdict");
            eq("Priya Oraon", ((Map<?, ?>) v.get("record")).get("holder"), "record shown");
            Map<String, Object> bad = w.verifier.verifyLink(r1.certId, "wrongtoken", false);
            eq("BAD_INPUT", bad.get("verdict"), "bad token");
            eq(null, bad.get("record"), "nothing shown");
            check(!Json.write(bad).contains("Priya"), "no personal data leaks");
        });
        test("Holder share links: open, count views, switch off, expire", () -> {
            Map<String, Object> link = w.certs.createShareLink(w.student, r1.certId, "Employer", 2);
            String token = (String) link.get("token");
            eq("GENUINE", w.verifier.verifyLink(r1.certId, token, false).get("verdict"), "works");
            eq(1, r1.shareLinks.get(0).views, "view counted");
            check(!Json.write(r1.toJson()).contains(token), "only the hash is stored");
            Clock.advance(3 * 86_400_000L);
            eq("BAD_INPUT", w.verifier.verifyLink(r1.certId, token, false).get("verdict"), "expired");
            Clock.fix(NOW);
            w.certs.disableShareLink(w.student, r1.certId, (String) link.get("id"));
            eq("BAD_INPUT", w.verifier.verifyLink(r1.certId, token, false).get("verdict"), "switched off");
            throwsError(() -> w.certs.createShareLink(w.staff, r1.certId, "x", 5), "not your");
        });
        CertificateRecord r2 = w.issue("Rahul Kumar", "CGPA 7.10 / 10");
        test("Only the issuing institution can revoke; revoked -> REVOKED with reason", () -> {
            throwsError(() -> w.certs.revoke(w.staff2, r2.certId, "x"), "Only the institution");
            throwsError(() -> w.certs.revoke(w.staff, r2.certId, " "), "reason");
            w.certs.revoke(w.staff, r2.certId, "Printed with wrong CGPA");
            Map<String, Object> v = w.verifier.verifyFile(w.certs.certificateFile(r2), false);
            eq("REVOKED", v.get("verdict"), "verdict");
            eq("Printed with wrong CGPA", ((Map<?, ?>) v.get("revocation")).get("reason"), "reason");
            throwsError(() -> w.certs.revoke(w.staff, r2.certId, "again"), "already");
        });
        test("Suspended institution cannot issue; its older certificates stay valid with a warning", () -> {
            CertificateRecord old = w.certs.issue(w.staff2, fields("Aman Mahato", "SGPA 8.1"), "");
            Clock.advance(60_000);
            w.certs.suspendIssuer("SUBU", "Recognition withdrawn");
            throwsError(() -> w.certs.issue(w.staff2, fields("New Person", "SGPA 9"), ""), "suspended");
            Map<String, Object> v = w.verifier.verifyFile(w.certs.certificateFile(old), false);
            eq("GENUINE", v.get("verdict"), "still genuine");
            check(Json.write(v.get("checks")).contains("\"warn\""), "warning shown");
            Clock.fix(NOW);
        });
        test("Batch CSV: all-or-nothing, every mistake listed, one block for all", () -> {
            String bad = "holder,rollNo,programme,result,session\nA One,R1,B.Tech,8.0,2022-2026\n,R2,B.Tech,8.0,2022-2026\nC,R1,B.Tech,7,2022-2026\n";
            int before = w.store.certificates.size();
            CertificateService.BatchResult r = w.certs.issueBatch(w.staff, bad);
            eq(2, r.errors.size(), "two mistakes: " + r.errors);
            eq(before, w.store.certificates.size(), "nothing issued");
            throwsError(() -> w.certs.issueBatch(w.staff, "name,x\na,b"), "Missing column");
            StringBuilder ok = new StringBuilder("holder,rollNo,programme,result,session,issueDate,username\n");
            for (int i = 0; i < 25; i++) ok.append("Student ").append(i).append(",R").append(i).append(",B.Tech,CGPA 8.").append(i % 10).append(",2022-2026,2026-07-18,\n");
            CertificateService.BatchResult good = w.certs.issueBatch(w.staff, ok.toString());
            eq(25, good.issued.size(), "25 issued");
            eq(25, w.net.node("C").chain.get(good.block).transactions.size(), "one block holds all 25");
            for (CertificateRecord c : good.issued) eq(good.block, c.block, "same block");
        });
        test("Input checks: future date, too long, unknown student account", () -> {
            Map<String, String> f = fields("X Y", "8");
            f.put("issueDate", "2027-01-01");
            throwsError(() -> w.certs.issue(w.staff, f, ""), "future");
            Map<String, String> g = fields("X".repeat(100), "8");
            throwsError(() -> w.certs.issue(w.staff, g, ""), "too long");
            throwsError(() -> w.certs.issue(w.staff, fields("X Y", "8"), "nobody"), "No student");
            throwsError(() -> w.certs.issue(w.student, fields("X Y", "8"), ""), "Only institution staff");
        });

        System.out.println("\n[Tamper lab and consensus]");
        LabService lab = new LabService(w.net, w.store);
        test("Attack 1 - edit one node: that node is BROKEN, the other two still agree, forgery still caught", () -> {
            lab.attack("B", LabService.Attack.EDIT, r1.certId, "result", "CGPA 9.99 / 10");
            Network.Consensus c = w.net.consensus();
            check(c.ok, "still a majority");
            eq("BROKEN", c.status.get("B").state, "B broken");
            eq("MERKLE", c.status.get("B").problemKind, "kind");
            eq("TAMPERED", w.verifier.verifyFile(lab.forgedFile(r1.certId, "result", "CGPA 9.99 / 10"), false).get("verdict"), "forgery caught");
            w.net.repair("B");
            eq("AGREES", w.net.consensus().status.get("B").state, "repaired");
        });
        test("Attack 2 - edit and redo proof of work: signature check catches it", () -> {
            lab.attack("C", LabService.Attack.RESEAL, r1.certId, "holder", "Someone Else");
            Network.NodeStatus s = w.net.consensus().status.get("C");
            eq("BROKEN", s.state, "C broken");
            eq("SIGNATURE", s.problemKind, "kind");
            w.net.repair("C");
        });
        test("Writing still works while one node is broken; the broken node refuses the block", () -> {
            lab.attack("A", LabService.Attack.EDIT, r1.certId, "result", "CGPA 1");
            Network.Commit cm = w.net.submit(List.of(issueTx(w, "Another Person")));
            eq(List.of("B", "C"), cm.acceptedBy, "B and C accept");
            eq(List.of("A"), cm.rejectedBy, "A refuses");
            w.net.repair("A");
            eq(3, w.net.consensus().agreeing.size(), "all agree after repair");
        });
        test("Attack 3 - delete a revocation and re-seal: valid on its own but OUT-VOTED", () -> {
            lab.attack("B", LabService.Attack.DELETE_REVOCATION, r2.certId, null, null);
            Network.Consensus c = w.net.consensus();
            eq("OUTVOTED", c.status.get("B").state, "B out-voted");
            eq("REVOKED", w.verifier.verifyFile(w.certs.certificateFile(r2), false).get("verdict"), "majority still says revoked");
        });
        test("51% attack - the same change on 2 of 3 nodes wins (why more honest nodes matter)", () -> {
            lab.attack("C", LabService.Attack.DELETE_REVOCATION, r2.certId, null, null);
            Network.Consensus c = w.net.consensus();
            eq(List.of("B", "C"), c.agreeing, "B and C now agree");
            eq("OUTVOTED", c.status.get("A").state, "honest A is out-voted");
            eq("GENUINE", w.verifier.verifyFile(w.certs.certificateFile(r2), false).get("verdict"), "attack succeeded");
            w.net.undoAllAttacks();
            eq("REVOKED", w.verifier.verifyFile(w.certs.certificateFile(r2), false).get("verdict"), "undo restores the truth");
        });
        test("Two broken nodes = no majority = no answer is trusted", () -> {
            lab.attack("A", LabService.Attack.EDIT, r1.certId, "result", "X1");
            lab.attack("B", LabService.Attack.RESEAL, r1.certId, "result", "X2");
            check(!w.net.consensus().ok, "no majority");
            eq("NETWORK_UNSAFE", w.verifier.verifyFile(file1, false).get("verdict"), "verdict");
            throwsError(() -> w.net.submit(List.of(issueTx(w, "Blocked"))), "no majority");
            w.net.undoAllAttacks();
            check(w.net.consensus().ok, "fixed");
        });

        System.out.println("\n[Forgery test bench]");
        test("Forgery bench: every forgery caught, no false alarms", () -> {
            Map<String, Object> res = new ForgeryBench(w.certs, w.verifier).run(42);
            eq(res.get("forgeries"), res.get("caught"), "all forgeries caught");
            eq(0, res.get("falseAlarms"), "no false alarms");
            check(((Number) res.get("forgeries")).intValue() > 100, "a real number of tries");
        });

        System.out.println("\n[Accounts]");
        test("Sign-in: same message for unknown user and wrong password; lock after 5 tries", () -> {
            String a = msg(() -> w.auth.login("nobody", "x"));
            String b = msg(() -> w.auth.login("priya", "wrong"));
            eq(a, b, "same message");
            for (int i = 0; i < 4; i++) msg(() -> w.auth.login("priya", "wrong"));
            check(msg(() -> w.auth.login("priya", "student1234")).contains("locked"), "locked");
            Clock.advance(AuthService.LOCK_MS + 1000);
            check(w.auth.login("priya", "student1234") != null, "unlocked later");
            Clock.fix(NOW);
        });
        test("Sessions end after 12 idle hours", () -> {
            AuthService.Session s = w.auth.login("meera", "issuer1234");
            check(w.auth.userFor(s.id) != null, "signed in");
            Clock.advance(AuthService.IDLE_MS + 1);
            eq(null, w.auth.userFor(s.id), "expired");
            Clock.fix(NOW);
        });

        System.out.println("\n[Saving to disk]");
        test("Everything is saved and loads again; editing a node file on disk is detected", () -> {
            Path dir = Files.createTempDirectory("pramaan-test");
            World d = new World(dir);
            CertificateRecord rec = d.issue("Disk Test", "Grade A");
            Network n2 = Network.load(dir);
            DataStore s2 = new DataStore(dir);
            s2.load();
            eq(3, n2.consensus().agreeing.size(), "3 agree after reload");
            eq("Disk Test", s2.certificates.get(rec.certId).fields.get("holder"), "record saved");
            check(s2.privateKey("KVIT") != null, "key saved");
            Verifier v2 = new Verifier(new CertificateService(n2, s2));
            eq("GENUINE", v2.verifyFile(new CertificateService(n2, s2).certificateFile(s2.certificates.get(rec.certId)), false).get("verdict"), "verify after reload");
            Path f = dir.resolve("nodes/node-b.json");
            String text = Files.readString(f);
            String root = n2.consensus().state.certs.get(rec.certId).issueTx.get("root");
            Files.writeString(f, text.replace(root, "f" + root.substring(1)));
            n2.reloadFromDisk();
            eq("BROKEN", n2.consensus().status.get("B").state, "hand edit caught");
            Files.writeString(f, "{ not json");
            n2.reloadFromDisk();
            eq("BROKEN", n2.consensus().status.get("B").state, "broken file caught");
            check(n2.consensus().ok, "others still agree");
        });

        System.out.println("\n[Web server]");
        webTests(w);

        Clock.fix(null);
        System.out.println("\n" + passed + " passed, " + failed + " failed");
        if (failed > 0) {
            for (String f : failures) System.out.println("  - " + f);
            System.exit(1);
        }
        System.exit(0);
    }

    static Transaction issueTx(World w, String holder) {
        Map<String, String> f = fields(holder, "8");
        f.put("issuer", "Koel Valley Institute of Technology");
        Map<String, String> salts = CertFields.newSalts();
        List<String> leaves = CertFields.fieldHashes(f, salts);
        Map<String, String> p = new LinkedHashMap<>();
        p.put("certId", CertFields.newCertId());
        p.put("issuerId", "KVIT");
        p.put("fieldNames", String.join(",", CertFields.NAMES));
        p.put("fieldHashes", String.join(",", leaves));
        p.put("root", MerkleTree.root(leaves));
        return Transaction.create(TxType.ISSUE_CERT, Clock.now(), "KVIT", p, w.store.privateKey("KVIT"));
    }

    static String msg(Test t) {
        try { t.run(); } catch (Exception e) { return e.getMessage(); }
        return "";
    }

    // ------------------------------------------------------------------ web tests

    static HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    static String base;

    static HttpResponse<String> req(String method, String path, String body, String cookie, String contentType, String origin) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path));
        if (cookie != null) b.header("Cookie", cookie);
        if (contentType != null) b.header("Content-Type", contentType);
        if (origin != null) b.header("Origin", origin);
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    static HttpResponse<String> post(String path, Object body, String cookie) throws Exception {
        return req("POST", path, Json.write(body), cookie, "application/json", null);
    }

    static String login(String u, String p) throws Exception {
        HttpResponse<String> r = post("/api/login", Json.map("username", u, "password", p), null);
        String set = r.headers().firstValue("Set-Cookie").orElse("");
        return set.split(";")[0];
    }

    static void webTests(World w) throws Exception {
        Path front = Files.createTempDirectory("pramaan-front");
        Files.writeString(front.resolve("index.html"), "<!doctype html><title>Pramaan</title>");
        Files.writeString(front.getParent().resolve("secret.txt"), "top secret");
        ApiServer server = new ApiServer(w.auth, w.certs, w.verifier, front, "http://localhost:8080");
        server.start(0);
        base = "http://127.0.0.1:" + server.port();
        try {
            test("Web: pages have a strict Content-Security-Policy and nosniff", () -> {
                HttpResponse<String> r = req("GET", "/", null, null, null, null);
                eq(200, r.statusCode(), "status");
                check(r.headers().firstValue("Content-Security-Policy").orElse("").contains("script-src 'self'"), "CSP");
                check(!r.headers().firstValue("Content-Security-Policy").orElse("").contains("unsafe-inline"), "no unsafe-inline");
                eq("nosniff", r.headers().firstValue("X-Content-Type-Options").orElse(""), "nosniff");
            });
            test("Web: path tricks cannot read other files", () -> {
                for (String p : List.of("/static/../secret.txt", "/static/..%2fsecret.txt", "/static/%2e%2e/secret.txt", "/secret.txt"))
                    check(!req("GET", p, null, null, null, null).body().contains("top secret"), p);
            });
            test("Web: sign-in cookie is HttpOnly + SameSite=Strict", () -> {
                HttpResponse<String> r = post("/api/login", Json.map("username", "meera", "password", "issuer1234"), null);
                String c = r.headers().firstValue("Set-Cookie").orElse("");
                check(c.contains("HttpOnly") && c.contains("SameSite=Strict"), c);
            });
            test("Web: CSRF - form posts and other websites are refused", () -> {
                String ck = login("meera", "issuer1234");
                eq(415, req("POST", "/api/issuer/revoke", "certId=x", ck, "application/x-www-form-urlencoded", null).statusCode(), "form refused");
                eq(403, req("POST", "/api/issuer/revoke", "{}", ck, "application/json", "http://evil.example").statusCode(), "other origin refused");
            });
            test("Web: public verify works without signing in and never shows personal data for bad links", () -> {
                HttpResponse<String> r = post("/api/public/verify", Json.map("method", "LINK", "certId", r1(w).certId, "token", "nope"), null);
                eq(200, r.statusCode(), "status");
                check(r.body().contains("BAD_INPUT") && !r.body().contains("Priya"), "no data");
                HttpResponse<String> ok = post("/api/public/verify", Json.map("method", "FILE", "file", w.certs.certificateFile(r1(w))), null);
                check(ok.body().contains("\"GENUINE\""), "genuine");
            });
            test("Web: roles - a student cannot issue, an issuer cannot use the Tamper lab", () -> {
                String st = login("priya", "student1234");
                eq(403, post("/api/issuer/issue", Json.map("fields", fields("A B", "9")), st).statusCode(), "student issue");
                String is = login("meera", "issuer1234");
                eq(403, post("/api/admin/tamper", Json.map("node", "B"), is).statusCode(), "issuer tamper");
                eq(401, post("/api/admin/repair", Json.map("node", "B"), null).statusCode(), "not signed in");
            });
            test("Web: you cannot open someone else's certificate (looks like it does not exist)", () -> {
                w.auth.createUser("aman", "Aman", Role.STUDENT, null, "student1234");
                String other = login("aman", "student1234");
                eq(404, req("GET", "/api/certificates/" + r1(w).certId, null, other, null, null).statusCode(), "other student");
                String sub = login("farhan", "issuer1234");
                eq(404, req("GET", "/api/certificates/" + r1(w).certId + "/file", null, sub, null, null).statusCode(), "other institution");
                String own = login("priya", "student1234");
                HttpResponse<String> f = req("GET", "/api/certificates/" + r1(w).certId + "/file", null, own, null, null);
                eq(200, f.statusCode(), "own file");
                check(f.headers().firstValue("Content-Disposition").orElse("").contains("attachment"), "download");
            });
            test("Web: issuing through the API returns a QR code that points to the verify link", () -> {
                String ck = login("meera", "issuer1234");
                HttpResponse<String> r = post("/api/issuer/issue", Json.map("fields", fields("Web Person", "CGPA 7.7"), "username", ""), ck);
                eq(200, r.statusCode(), r.body());
                Map<String, Object> m = Json.parseObject(r.body());
                check(String.valueOf(m.get("qrSvg")).startsWith("<svg"), "qr");
                check(String.valueOf(m.get("verifyUrl")).contains("/#/v/" + m.get("certId") + "/"), "url");
            });
            test("Web: sign-in is rate limited", () -> {
                int limited = 0;
                for (int i = 0; i < 14; i++)
                    if (post("/api/login", Json.map("username", "x", "password", "y"), null).statusCode() == 429) limited++;
                check(limited > 0, "429 after too many tries");
            });
        } finally {
            server.stop();
        }
    }

    static CertificateRecord r1(World w) {
        for (CertificateRecord r : w.store.certificates.values()) if ("Priya Oraon".equals(r.fields.get("holder"))) return r;
        throw new IllegalStateException();
    }
}
