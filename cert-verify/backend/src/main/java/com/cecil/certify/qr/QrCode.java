package com.cecil.certify.qr;

import java.nio.charset.StandardCharsets;

/**
 * A small QR code maker, written from the QR standard (ISO/IEC 18004) with the JDK only.
 * It supports what Pramaan needs: byte mode, error-correction level M (about 15% of the code can be damaged
 * and it still scans), versions 1 to 10 (up to 213 bytes).
 *
 * Steps:
 *  1. Turn the text into bits: mode (0100 = bytes), length, the bytes, then padding.
 *  2. Split into blocks and add Reed-Solomon error-correction bytes to each block (maths over GF(256)).
 *  3. Draw the fixed patterns: three big finder squares, timing lines, small alignment squares.
 *  4. Lay the bits in a zig-zag from the bottom-right corner.
 *  5. Try all 8 masks, keep the one with the lowest "penalty" (easiest to scan), and write the format bits.
 */
public final class QrCode {

    // Level M tables, index = version (0 unused)
    private static final int[] EC_PER_BLOCK = {0, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26};
    private static final int[] NUM_BLOCKS = {0, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5};
    private static final int[] TOTAL_CODEWORDS = {0, 26, 44, 70, 100, 134, 172, 196, 242, 292, 346};
    private static final int[][] ALIGN = {{}, {}, {6, 18}, {6, 22}, {6, 26}, {6, 30}, {6, 34},
            {6, 22, 38}, {6, 24, 42}, {6, 26, 46}, {6, 28, 50}};

    public final int version;
    public final int size;
    public final int mask;
    private final boolean[][] modules;     // [y][x], true = dark
    private final boolean[][] isFunction;

    public boolean get(int x, int y) {
        return x >= 0 && y >= 0 && x < size && y < size && modules[y][x];
    }

    public static QrCode encode(String text) {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        for (int v = 1; v <= 10; v++) {
            int dataCodewords = TOTAL_CODEWORDS[v] - EC_PER_BLOCK[v] * NUM_BLOCKS[v];
            int countBits = v <= 9 ? 8 : 16;
            if (4 + countBits + data.length * 8 <= dataCodewords * 8) return new QrCode(v, data);
        }
        throw new IllegalArgumentException("Text too long for a QR code (max about 200 characters)");
    }

    private QrCode(int version, byte[] data) {
        this.version = version;
        this.size = version * 4 + 17;
        modules = new boolean[size][size];
        isFunction = new boolean[size][size];

        drawFunctionPatterns();
        byte[] all = addErrorCorrection(dataCodewords(data));
        drawCodewords(all);

        int best = 0;
        long bestPenalty = Long.MAX_VALUE;
        for (int m = 0; m < 8; m++) {
            applyMask(m);
            drawFormatBits(m);
            long p = penalty();
            if (p < bestPenalty) { bestPenalty = p; best = m; }
            applyMask(m); // undo (XOR twice)
        }
        mask = best;
        applyMask(best);
        drawFormatBits(best);
    }

    // ------------------------------------------------------------------ 1. data bits

    private byte[] dataCodewords(byte[] data) {
        int cap = TOTAL_CODEWORDS[version] - EC_PER_BLOCK[version] * NUM_BLOCKS[version];
        BitBuffer bb = new BitBuffer();
        bb.append(0b0100, 4);
        bb.append(data.length, version <= 9 ? 8 : 16);
        for (byte b : data) bb.append(b & 0xFF, 8);
        bb.append(0, Math.min(4, cap * 8 - bb.length));
        bb.append(0, (8 - bb.length % 8) % 8);
        for (int pad = 0xEC; bb.length < cap * 8; pad ^= 0xEC ^ 0x11) bb.append(pad, 8);
        return bb.toBytes();
    }

    private static final class BitBuffer {
        boolean[] bits = new boolean[4000];
        int length;

        void append(int value, int count) {
            for (int i = count - 1; i >= 0; i--) bits[length++] = ((value >>> i) & 1) != 0;
        }

        byte[] toBytes() {
            byte[] out = new byte[length / 8];
            for (int i = 0; i < length; i++) if (bits[i]) out[i >>> 3] |= (byte) (1 << (7 - (i & 7)));
            return out;
        }
    }

    // ------------------------------------------------------------------ 2. error correction

    private byte[] addErrorCorrection(byte[] data) {
        int numBlocks = NUM_BLOCKS[version];
        int ec = EC_PER_BLOCK[version];
        int raw = TOTAL_CODEWORDS[version];
        int numShort = numBlocks - raw % numBlocks;
        int shortLen = raw / numBlocks;
        byte[] divisor = rsDivisor(ec);

        byte[][] blocks = new byte[numBlocks][];
        for (int i = 0, k = 0; i < numBlocks; i++) {
            int dataLen = shortLen - ec + (i < numShort ? 0 : 1);
            byte[] dat = java.util.Arrays.copyOfRange(data, k, k + dataLen);
            k += dataLen;
            byte[] rem = rsRemainder(dat, divisor);
            byte[] block = new byte[shortLen + 1];
            // short blocks get a gap at the end of their data part, so all blocks line up
            System.arraycopy(dat, 0, block, 0, dat.length);
            System.arraycopy(rem, 0, block, block.length - ec, ec);
            blocks[i] = block;
        }
        byte[] out = new byte[raw];
        int n = 0;
        for (int i = 0; i < shortLen + 1; i++) {
            for (int j = 0; j < numBlocks; j++) {
                if (i != shortLen - ec || j >= numShort) out[n++] = blocks[j][i];
            }
        }
        return out;
    }

    private static byte[] rsDivisor(int degree) {
        byte[] result = new byte[degree];
        result[degree - 1] = 1;
        int root = 1;
        for (int i = 0; i < degree; i++) {
            for (int j = 0; j < degree; j++) {
                result[j] = (byte) gfMul(result[j] & 0xFF, root);
                if (j + 1 < degree) result[j] ^= result[j + 1];
            }
            root = gfMul(root, 0x02);
        }
        return result;
    }

    private static byte[] rsRemainder(byte[] data, byte[] divisor) {
        byte[] result = new byte[divisor.length];
        for (byte b : data) {
            int factor = (b ^ result[0]) & 0xFF;
            System.arraycopy(result, 1, result, 0, result.length - 1);
            result[result.length - 1] = 0;
            for (int i = 0; i < result.length; i++) result[i] ^= (byte) gfMul(divisor[i] & 0xFF, factor);
        }
        return result;
    }

    /** Multiplication in GF(256) with the QR polynomial x^8 + x^4 + x^3 + x^2 + 1. */
    private static int gfMul(int x, int y) {
        int z = 0;
        for (int i = 7; i >= 0; i--) {
            z = (z << 1) ^ ((z >>> 7) * 0x11D);
            z ^= ((y >>> i) & 1) * x;
        }
        return z & 0xFF;
    }

    // ------------------------------------------------------------------ 3. fixed patterns

    private void set(int x, int y, boolean dark) {
        modules[y][x] = dark;
        isFunction[y][x] = true;
    }

    private void drawFunctionPatterns() {
        for (int i = 0; i < size; i++) {
            set(6, i, i % 2 == 0);
            set(i, 6, i % 2 == 0);
        }
        finder(3, 3);
        finder(size - 4, 3);
        finder(3, size - 4);
        int[] pos = ALIGN[version];
        int n = pos.length;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if ((i == 0 && j == 0) || (i == 0 && j == n - 1) || (i == n - 1 && j == 0)) continue;
                for (int dy = -2; dy <= 2; dy++)
                    for (int dx = -2; dx <= 2; dx++)
                        set(pos[i] + dx, pos[j] + dy, Math.max(Math.abs(dx), Math.abs(dy)) != 1);
            }
        }
        drawFormatBits(0); // reserve the area; real bits are written after choosing the mask
        drawVersion();
    }

    private void finder(int cx, int cy) {
        for (int dy = -4; dy <= 4; dy++) {
            for (int dx = -4; dx <= 4; dx++) {
                int d = Math.max(Math.abs(dx), Math.abs(dy));
                int x = cx + dx, y = cy + dy;
                if (x >= 0 && x < size && y >= 0 && y < size) set(x, y, d != 2 && d != 4);
            }
        }
    }

    private void drawFormatBits(int msk) {
        int data = (0 << 3) | msk; // level M = 00
        int rem = data;
        for (int i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
        int bits = ((data << 10) | rem) ^ 0x5412;
        for (int i = 0; i <= 5; i++) set(8, i, bit(bits, i));
        set(8, 7, bit(bits, 6));
        set(8, 8, bit(bits, 7));
        set(7, 8, bit(bits, 8));
        for (int i = 9; i < 15; i++) set(14 - i, 8, bit(bits, i));
        for (int i = 0; i < 8; i++) set(size - 1 - i, 8, bit(bits, i));
        for (int i = 8; i < 15; i++) set(8, size - 15 + i, bit(bits, i));
        set(8, size - 8, true); // the "dark module"
    }

    private void drawVersion() {
        if (version < 7) return;
        int rem = version;
        for (int i = 0; i < 12; i++) rem = (rem << 1) ^ ((rem >>> 11) * 0x1F25);
        int bits = (version << 12) | rem;
        for (int i = 0; i < 18; i++) {
            boolean b = bit(bits, i);
            int a = size - 11 + i % 3, c = i / 3;
            set(a, c, b);
            set(c, a, b);
        }
    }

    private static boolean bit(int x, int i) {
        return ((x >>> i) & 1) != 0;
    }

    // ------------------------------------------------------------------ 4. zig-zag placement

    private void drawCodewords(byte[] data) {
        int i = 0;
        for (int right = size - 1; right >= 1; right -= 2) {
            if (right == 6) right = 5;
            for (int vert = 0; vert < size; vert++) {
                for (int j = 0; j < 2; j++) {
                    int x = right - j;
                    boolean upward = ((right + 1) & 2) == 0;
                    int y = upward ? size - 1 - vert : vert;
                    if (!isFunction[y][x] && i < data.length * 8) {
                        modules[y][x] = bit(data[i >>> 3] & 0xFF, 7 - (i & 7));
                        i++;
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ 5. masks

    private void applyMask(int m) {
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                if (isFunction[y][x]) continue;
                boolean flip = switch (m) {
                    case 0 -> (x + y) % 2 == 0;
                    case 1 -> y % 2 == 0;
                    case 2 -> x % 3 == 0;
                    case 3 -> (x + y) % 3 == 0;
                    case 4 -> (x / 3 + y / 2) % 2 == 0;
                    case 5 -> x * y % 2 + x * y % 3 == 0;
                    case 6 -> (x * y % 2 + x * y % 3) % 2 == 0;
                    default -> ((x + y) % 2 + x * y % 3) % 2 == 0;
                };
                if (flip) modules[y][x] = !modules[y][x];
            }
        }
    }

    /** The four standard penalty rules. Lower = easier for cameras to read. */
    private long penalty() {
        long p = 0;
        for (int pass = 0; pass < 2; pass++) {
            for (int a = 0; a < size; a++) {
                int run = 1;
                for (int b = 1; b < size; b++) {
                    boolean cur = pass == 0 ? modules[a][b] : modules[b][a];
                    boolean prev = pass == 0 ? modules[a][b - 1] : modules[b - 1][a];
                    if (cur == prev) {
                        run++;
                        if (run == 5) p += 3; else if (run > 5) p++;
                    } else run = 1;
                }
                for (int b = -4; b < size; b++) {
                    if (finderLike(pass, a, b, true) || finderLike(pass, a, b, false)) p += 40;
                }
            }
        }
        for (int y = 0; y + 1 < size; y++)
            for (int x = 0; x + 1 < size; x++) {
                boolean c = modules[y][x];
                if (c == modules[y][x + 1] && c == modules[y + 1][x] && c == modules[y + 1][x + 1]) p += 3;
            }
        int dark = 0;
        for (boolean[] row : modules) for (boolean m : row) if (m) dark++;
        int total = size * size;
        int k = (Math.abs(dark * 20 - total * 10) + total - 1) / total - 1;
        p += Math.max(0, k) * 10L;
        return p;
    }

    private static final boolean[] PATTERN = {true, false, true, true, true, false, true};

    /** 1:1:3:1:1 dark-light pattern with 4 light modules before (or after) it. Outside the code counts as light. */
    private boolean finderLike(int pass, int a, int start, boolean lightBefore) {
        for (int i = 0; i < 11; i++) {
            int b = start + i;
            boolean m = b >= 0 && b < size && (pass == 0 ? modules[a][b] : modules[b][a]);
            boolean want = lightBefore ? (i >= 4 && PATTERN[i - 4]) : (i < 7 && PATTERN[i]);
            if (m != want) return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ output

    /** An SVG picture of the code. Each dark module is one 1x1 square inside a single path (small file). */
    public String toSvg(int border, String darkColour) {
        StringBuilder sb = new StringBuilder();
        int dim = size + border * 2;
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 ").append(dim).append(' ').append(dim)
                .append("\" shape-rendering=\"crispEdges\" role=\"img\" aria-label=\"QR code\">");
        sb.append("<rect width=\"100%\" height=\"100%\" fill=\"#ffffff\"/><path fill=\"").append(darkColour).append("\" d=\"");
        for (int y = 0; y < size; y++)
            for (int x = 0; x < size; x++)
                if (modules[y][x]) sb.append('M').append(x + border).append(',').append(y + border).append("h1v1h-1z");
        sb.append("\"/></svg>");
        return sb.toString();
    }
}
