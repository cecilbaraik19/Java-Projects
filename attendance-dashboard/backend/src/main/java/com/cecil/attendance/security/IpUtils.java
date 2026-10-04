package com.cecil.attendance.security;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** IP address and CIDR helpers (IPv4 and IPv6, including IPv4-mapped IPv6). */
public final class IpUtils {

    private IpUtils() {
    }

    /** A parsed network such as 192.168.1.0/24 or a single host. */
    public record Cidr(byte[] network, int prefix, String text) {

        public boolean contains(byte[] address) {
            if (address == null || address.length != network.length) return false;
            int fullBytes = prefix / 8;
            int remBits = prefix % 8;
            for (int i = 0; i < fullBytes; i++) {
                if (address[i] != network[i]) return false;
            }
            if (remBits == 0) return true;
            int mask = (0xFF << (8 - remBits)) & 0xFF;
            return (address[fullBytes] & mask) == (network[fullBytes] & mask);
        }
    }

    /**
     * Parses a literal IP address only (never does a DNS lookup, so hostnames are rejected).
     * Returns null if the text is not an IP literal.
     */
    public static byte[] parseIp(String text) {
        if (text == null) return null;
        String s = text.trim();
        if (s.startsWith("[") && s.endsWith("]")) s = s.substring(1, s.length() - 1);
        int zone = s.indexOf('%');
        if (zone >= 0) s = s.substring(0, zone);
        if (s.isEmpty()) return null;
        boolean looksV4 = s.matches("\\d{1,3}(\\.\\d{1,3}){3}");
        boolean looksV6 = s.contains(":") && s.matches("[0-9a-fA-F:.]+");
        if (!looksV4 && !looksV6) return null;
        try {
            byte[] b = InetAddress.getByName(s).getAddress();
            return normalize(b);
        } catch (UnknownHostException e) {
            return null;
        }
    }

    /** Converts IPv4-mapped IPv6 (::ffff:a.b.c.d) to plain IPv4 so ranges match either way. */
    private static byte[] normalize(byte[] b) {
        if (b.length == 16) {
            boolean mapped = true;
            for (int i = 0; i < 10; i++) if (b[i] != 0) { mapped = false; break; }
            if (mapped && (b[10] & 0xFF) == 0xFF && (b[11] & 0xFF) == 0xFF) {
                return Arrays.copyOfRange(b, 12, 16);
            }
        }
        return b;
    }

    /** Parses "10.0.0.0/8", "192.168.1.25" or "2001:db8::/32". */
    public static Cidr parseCidr(String text) {
        String s = text.trim();
        int slash = s.indexOf('/');
        byte[] ip = parseIp(slash >= 0 ? s.substring(0, slash) : s);
        if (ip == null) throw new IllegalArgumentException("'" + s + "' is not a valid IP address or range");
        int max = ip.length * 8;
        int prefix = max;
        if (slash >= 0) {
            try {
                prefix = Integer.parseInt(s.substring(slash + 1).trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("'" + s + "' has an invalid prefix length");
            }
            if (prefix < 0 || prefix > max) throw new IllegalArgumentException("'" + s + "' prefix must be 0-" + max);
        }
        return new Cidr(ip, prefix, s);
    }

    /** Splits a comma / whitespace separated list and parses each entry. */
    public static List<Cidr> parseList(String text) {
        List<Cidr> out = new ArrayList<>();
        if (text == null) return out;
        for (String part : text.split("[,;\\s]+")) {
            if (!part.isBlank()) out.add(parseCidr(part));
        }
        return out;
    }

    public static boolean matchesAny(String ip, List<Cidr> ranges) {
        byte[] addr = parseIp(ip);
        if (addr == null) return false;
        return ranges.stream().anyMatch(c -> c.contains(addr));
    }
}
