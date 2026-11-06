package com.cecil.certify.cert;

import com.cecil.certify.chain.MerkleTree;
import com.cecil.certify.util.Crypto;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The fields printed on every certificate, and how each one is turned into a salted fingerprint.
 *
 *   field fingerprint = leafHash( name "=" normalised value "|" salt )
 *   certificate fingerprint (root) = Merkle root of the 7 field fingerprints
 *
 * Why a SALT (16 random bytes per field)? Without it, anyone could guess "result = 8.5 CGPA", hash it, and compare
 * with the public chain. With a secret salt the guess is useless, so the chain can be public.
 *
 * Why per-field fingerprints? So that when a certificate is forged we can say WHICH line was changed
 * ("Result was changed") - not just "something is wrong".
 */
public final class CertFields {
    private CertFields() {}

    public static final String[] NAMES = {"holder", "rollNo", "programme", "result", "session", "issueDate", "issuer"};
    public static final String[] LABELS = {"Awarded to", "Roll / Enrolment No.", "Programme", "Result", "Session",
            "Date of issue", "Issued by"};
    public static final int[] MAX_LENGTH = {80, 30, 120, 60, 20, 10, 120};

    public static String label(String name) {
        for (int i = 0; i < NAMES.length; i++) if (NAMES[i].equals(name)) return LABELS[i];
        return name;
    }

    public static int position(String name) {
        for (int i = 0; i < NAMES.length; i++) if (NAMES[i].equals(name)) return i;
        return -1;
    }

    /**
     * Same text, written differently, gives the same fingerprint:
     * "  Priya   SHARMA " and "priya sharma" match. Different letters or digits never match.
     */
    public static String normalise(String value) {
        if (value == null) return "";
        String s = Normalizer.normalize(value, Normalizer.Form.NFKC);
        s = s.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
        return s;
    }

    public static String fieldHash(String name, String value, String salt) {
        return MerkleTree.leaf(name + "=" + normalise(value) + "|" + salt);
    }

    public static Map<String, String> newSalts() {
        Map<String, String> salts = new LinkedHashMap<>();
        for (String n : NAMES) salts.put(n, Crypto.randomHex(16));
        return salts;
    }

    public static List<String> fieldHashes(Map<String, String> fields, Map<String, String> salts) {
        List<String> out = new ArrayList<>();
        for (String n : NAMES) out.add(fieldHash(n, fields.get(n), salts.get(n)));
        return out;
    }

    // ------------------------------------------------------------------ certificate IDs

    /** Crockford Base32: no I, L, O or U, so the ID is easy to read aloud and type. */
    private static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

    /** A new random ID like PRM-7K2Q-9XWD-M. The last letter is a check letter that catches typing mistakes. */
    public static String newCertId() {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 8; i++) body.append(ALPHABET.charAt(Crypto.RANDOM.nextInt(32)));
        String b = body.toString();
        return "PRM-" + b.substring(0, 4) + "-" + b.substring(4) + "-" + checkChar(b);
    }

    /**
     * Check letter = weighted sum of the 8 characters, mod 32. The weights 1, 3, 5 ... 15 are all ODD, so changing
     * any ONE character always changes the check letter (a typing mistake is always noticed).
     */
    public static char checkChar(String body) {
        int sum = 0;
        for (int i = 0; i < body.length(); i++) sum += (2 * i + 1) * ALPHABET.indexOf(body.charAt(i));
        return ALPHABET.charAt(sum % 32);
    }

    /** Cleans what a person typed: upper case, O->0, I/L->1, adds the dashes. Returns null if it cannot be an ID. */
    public static String cleanCertId(String typed) {
        if (typed == null) return null;
        String s = typed.toUpperCase(Locale.ROOT).replaceAll("[^0-9A-Z]", "");
        if (s.startsWith("PRM")) s = s.substring(3);
        s = s.replace('O', '0').replace('I', '1').replace('L', '1');
        if (s.length() != 9) return null;
        for (char c : s.toCharArray()) if (ALPHABET.indexOf(c) < 0) return null;
        return "PRM-" + s.substring(0, 4) + "-" + s.substring(4, 8) + "-" + s.charAt(8);
    }

    /** True if the check letter matches (so the ID was typed correctly). */
    public static boolean checkLetterOk(String cleanId) {
        if (cleanId == null) return false;
        String body = cleanId.substring(4, 8) + cleanId.substring(9, 13);
        return cleanId.charAt(14) == checkChar(body);
    }
}
