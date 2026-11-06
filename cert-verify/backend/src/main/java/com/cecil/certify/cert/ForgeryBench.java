package com.cecil.certify.cert;

import com.cecil.certify.chain.Block;
import com.cecil.certify.chain.ChainValidator;
import com.cecil.certify.chain.MerkleTree;
import com.cecil.certify.chain.Network;
import com.cecil.certify.chain.Transaction;
import com.cecil.certify.chain.TxType;
import com.cecil.certify.model.CertificateRecord;
import com.cecil.certify.util.Clock;
import com.cecil.certify.util.Crypto;
import com.cecil.certify.util.Json;

import java.security.KeyPair;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Forgery test bench: tries hundreds of realistic forgeries against the live network and counts how many
 * are caught. Also checks the opposite mistake: genuine certificates must NOT be flagged ("false alarms").
 * Nothing is written to the chain and nothing is logged.
 */
public final class ForgeryBench {

    private final CertificateService certs;
    private final Verifier verifier;

    public ForgeryBench(CertificateService certs, Verifier verifier) {
        this.certs = certs;
        this.verifier = verifier;
    }

    private static final class Tally {
        final String key, label, expected, how;
        int tried, caught;
        String example = "";

        Tally(String key, String label, String expected, String how) {
            this.key = key; this.label = label; this.expected = expected; this.how = how;
        }
    }

    public Map<String, Object> run(long seed) {
        long start = System.currentTimeMillis();
        Random rnd = new Random(seed);
        List<CertificateRecord> all = new ArrayList<>(certs.store().certificates.values());
        List<CertificateRecord> live = new ArrayList<>();
        List<CertificateRecord> revoked = new ArrayList<>();
        for (CertificateRecord r : all) (r.revoked ? revoked : live).add(r);

        Tally genuine = new Tally("genuine", "Genuine certificate, untouched", "GENUINE", "Should pass - a failure here is a false alarm");
        Tally spacing = new Tally("spacing", "Genuine, but typed with extra spaces / different capitals", "GENUINE", "Should pass - same text, written differently");
        Tally oneField = new Tally("oneField", "One detail changed (marks, name, date, programme ...)", "TAMPERED", "Fingerprint of that field no longer matches");
        Tally smallEdit = new Tally("smallEdit", "Tiny edit: one character / one digit", "TAMPERED", "Even one character changes the SHA-256 hash completely");
        Tally twoFields = new Tally("twoFields", "Two details changed together", "TAMPERED", "Both changed fields are named");
        Tally idSwap = new Tally("idSwap", "Real ID copied onto someone else's details", "TAMPERED", "The ID is real but the details belong to a different record");
        Tally saltSwap = new Tally("saltSwap", "Salts shuffled in the file", "TAMPERED", "Each salt is tied to one field");
        Tally fakeId = new Tally("fakeId", "Made-up ID in the right format", "NOT_FOUND", "Not on the chain");
        Tally typoId = new Tally("typoId", "Real ID with one character changed", "NOT_FOUND", "Check letter or chain lookup fails");
        Tally revokedT = new Tally("revoked", "Revoked certificate shown as valid", "REVOKED", "Revocation is on the chain");
        Tally rogue = new Tally("rogue", "Fake institution writes its own 'certificate' to the chain", "REFUSED", "Nodes refuse a record signed by an unknown key");
        Tally impersonate = new Tally("impersonate", "Fake institution signs in a real institution's name", "REFUSED", "Signature does not match the real institution's key");
        List<Tally> tallies = List.of(genuine, spacing, oneField, smallEdit, twoFields, idSwap, saltSwap, fakeId, typoId, revokedT, rogue, impersonate);

        int rounds = Math.min(40, Math.max(live.size(), 1));
        for (int k = 0; k < rounds && !live.isEmpty(); k++) {
            CertificateRecord r = live.get(rnd.nextInt(live.size()));

            Map<String, String> f = new LinkedHashMap<>(r.fields);
            expect(genuine, verifier.verifyFile(file(r.certId, f, r.salts), false), "GENUINE", r.certId);

            Map<String, String> sp = new LinkedHashMap<>(r.fields);
            sp.put("holder", "  " + r.fields.get("holder").toUpperCase() + "  ");
            sp.put("programme", r.fields.get("programme").replace(" ", "   "));
            expect(spacing, verifier.verifyFile(file(r.certId, sp, r.salts), false), "GENUINE", r.certId);

            for (int t = 0; t < 3; t++) {
                String n = CertFields.NAMES[rnd.nextInt(CertFields.NAMES.length)];
                Map<String, String> m = new LinkedHashMap<>(r.fields);
                m.put(n, forge(n, m.get(n), rnd));
                expect(oneField, verifier.verifyFile(file(r.certId, m, r.salts), false), "TAMPERED",
                        CertFields.label(n) + ": '" + r.fields.get(n) + "' → '" + m.get(n) + "'");
            }

            Map<String, String> tiny = new LinkedHashMap<>(r.fields);
            tiny.put("holder", oneChar(tiny.get("holder"), rnd));
            expect(smallEdit, verifier.verifyFile(file(r.certId, tiny, r.salts), false), "TAMPERED",
                    "'" + r.fields.get("holder") + "' → '" + tiny.get("holder") + "'");

            Map<String, String> two = new LinkedHashMap<>(r.fields);
            two.put("result", forge("result", two.get("result"), rnd));
            two.put("issueDate", forge("issueDate", two.get("issueDate"), rnd));
            expect(twoFields, verifier.verifyFile(file(r.certId, two, r.salts), false), "TAMPERED", r.certId);

            if (all.size() > 1) {
                CertificateRecord other = all.get(rnd.nextInt(all.size()));
                if (!other.certId.equals(r.certId) && !CertFields.normalise(other.fields.get("holder")).equals(CertFields.normalise(r.fields.get("holder")))) {
                    expect(idSwap, verifier.verifyFile(file(r.certId, other.fields, other.salts), false), "TAMPERED",
                            other.fields.get("holder") + " using " + r.certId);
                }
            }

            Map<String, String> salts = new LinkedHashMap<>(r.salts);
            String a = salts.get("holder"), b = salts.get("result");
            salts.put("holder", b);
            salts.put("result", a);
            expect(saltSwap, verifier.verifyFile(file(r.certId, r.fields, salts), false), "TAMPERED", r.certId);

            String fake;
            do { fake = CertFields.newCertId(); } while (certs.store().certificates.containsKey(fake));
            expect(fakeId, verifier.verifyFile(file(fake, r.fields, r.salts), false), "NOT_FOUND", fake);

            String typo = typoInId(r.certId, rnd);
            expect(typoId, verifier.verifyFile(file(typo, r.fields, r.salts), false), "NOT_FOUND", r.certId + " → " + typo);
        }

        for (CertificateRecord r : revoked) {
            expect(revokedT, verifier.verifyFile(file(r.certId, r.fields, r.salts), false), "REVOKED", r.certId);
        }

        // Attacks on the chain itself: build a block and ask every honest node to accept it (nothing is saved).
        Network net = certs.network();
        Network.Consensus c = net.consensus();
        if (c.ok) {
            for (int t = 0; t < 10; t++) {
                KeyPair rogueKey = Crypto.newKeyPair();
                CertificateRecord sample = live.isEmpty() ? null : live.get(rnd.nextInt(live.size()));
                Map<String, String> fields = sample == null ? demoFields() : new LinkedHashMap<>(sample.fields);
                Map<String, String> salts = CertFields.newSalts();
                List<String> leaves = CertFields.fieldHashes(fields, salts);

                Map<String, String> p = new LinkedHashMap<>();
                p.put("certId", CertFields.newCertId());
                p.put("issuerId", "FAKEUNIV");
                p.put("fieldNames", String.join(",", CertFields.NAMES));
                p.put("fieldHashes", String.join(",", leaves));
                p.put("root", MerkleTree.root(leaves));
                Transaction tx = Transaction.create(TxType.ISSUE_CERT, Clock.now(), "FAKEUNIV", p, rogueKey.getPrivate());
                tryBlock(rogue, c, tx, "FAKEUNIV");

                String realIssuer = c.state.issuers.keySet().iterator().next();
                Map<String, String> p2 = new LinkedHashMap<>(p);
                p2.put("certId", CertFields.newCertId());
                p2.put("issuerId", realIssuer);
                Transaction tx2 = Transaction.create(TxType.ISSUE_CERT, Clock.now(), realIssuer, p2, rogueKey.getPrivate());
                tryBlock(impersonate, c, tx2, "signed as " + realIssuer);
            }
        }

        int tried = 0, ok = 0, forgeTried = 0, forgeCaught = 0;
        List<Object> rows = new ArrayList<>();
        for (Tally t : tallies) {
            if (t.tried == 0) continue;
            tried += t.tried;
            ok += t.caught;
            boolean isForgery = !t.expected.equals("GENUINE");
            if (isForgery) { forgeTried += t.tried; forgeCaught += t.caught; }
            rows.add(Json.map("key", t.key, "label", t.label, "expected", t.expected, "how", t.how,
                    "tried", t.tried, "correct", t.caught, "forgery", isForgery, "example", t.example));
        }
        int falseAlarms = (genuine.tried - genuine.caught) + (spacing.tried - spacing.caught);
        return Json.map("rows", rows, "total", tried, "correct", ok,
                "forgeries", forgeTried, "caught", forgeCaught,
                "genuineTried", genuine.tried + spacing.tried, "falseAlarms", falseAlarms,
                "millis", System.currentTimeMillis() - start, "seed", seed);
    }

    private void tryBlock(Tally t, Network.Consensus c, Transaction tx, String example) {
        List<Block> chain = new ArrayList<>(c.leader.chain);
        Block tip = chain.get(chain.size() - 1);
        Block b = new Block(chain.size(), Math.max(Clock.now(), tip.time), tip.hash, certs.network().difficulty, "X", List.of(tx));
        b.mine();
        chain.add(b);
        ChainValidator.Result r = ChainValidator.validate(chain, certs.network().genesisHash, certs.network().difficulty);
        t.tried++;
        if (!r.ok) {
            t.caught++;
            if (t.example.isEmpty()) t.example = example + " → refused: " + r.reason;
        }
    }

    private static void expect(Tally t, Map<String, Object> result, String want, String example) {
        t.tried++;
        String got = String.valueOf(result.get("verdict"));
        if (got.equals(want)) {
            t.caught++;
            if (t.example.isEmpty()) {
                Object ch = result.get("changedFields");
                t.example = example + (ch instanceof List<?> l && !l.isEmpty() ? " → flagged: " + l : " → " + got);
            }
        } else {
            t.example = "MISSED: " + example + " → " + got;
        }
    }

    private static Map<String, Object> file(String certId, Map<String, String> fields, Map<String, String> salts) {
        return Json.map("format", "pramaan-certificate/1", "certId", certId,
                "fields", new LinkedHashMap<String, Object>(fields), "salts", new LinkedHashMap<String, Object>(salts));
    }

    /** Makes a believable forged value for a field. */
    static String forge(String field, String v, Random rnd) {
        switch (field) {
            case "result": {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)(\\.\\d+)?").matcher(v);
                if (m.find()) {
                    int whole = Integer.parseInt(m.group(1));
                    int nw = whole >= 9 ? whole - 1 : whole + 1;
                    return v.substring(0, m.start(1)) + nw + v.substring(m.end(1));
                }
                return v.contains("Second") ? v.replace("Second", "First") : "First Division with Distinction";
            }
            case "issueDate": {
                try { return java.time.LocalDate.parse(v).minusYears(1).toString(); } catch (Exception e) { return "2020-01-01"; }
            }
            case "holder": return v + (rnd.nextBoolean() ? " Kumar" : " Singh");
            case "rollNo": return oneChar(v, rnd);
            case "programme": return v.contains("Diploma") ? v.replace("Diploma", "Degree") : "Master of Technology (Computer Science)";
            case "session": return v.replaceAll("\\d", "1");
            default: return v + " (Autonomous)";
        }
    }

    static String oneChar(String v, Random rnd) {
        if (v == null || v.isEmpty()) return "x";
        char[] c = v.toCharArray();
        for (int tries = 0; tries < 20; tries++) {
            int i = rnd.nextInt(c.length);
            if (Character.isLetter(c[i])) { c[i] = c[i] == 'a' ? 'e' : (c[i] == 'A' ? 'E' : (Character.isUpperCase(c[i]) ? 'A' : 'a')); break; }
            if (Character.isDigit(c[i])) { c[i] = c[i] == '9' ? '8' : (char) (c[i] + 1); break; }
        }
        String out = new String(c);
        return out.equals(v) ? v + "1" : out;
    }

    private static String typoInId(String id, Random rnd) {
        String alphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
        int[] pos = {4, 5, 6, 7, 9, 10, 11, 12};
        char[] c = id.toCharArray();
        int p = pos[rnd.nextInt(pos.length)];
        char n;
        do { n = alphabet.charAt(rnd.nextInt(32)); } while (n == c[p]);
        c[p] = n;
        return new String(c);
    }

    private static Map<String, String> demoFields() {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("holder", "Test Person");
        f.put("rollNo", "X-1");
        f.put("programme", "B.Tech");
        f.put("result", "CGPA 9.9");
        f.put("session", "2022-2026");
        f.put("issueDate", "2026-06-30");
        f.put("issuer", "Fake University");
        return f;
    }
}
