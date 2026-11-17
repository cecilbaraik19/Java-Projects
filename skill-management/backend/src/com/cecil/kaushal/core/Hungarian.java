package com.cecil.kaushal.core;

import java.util.Arrays;

/**
 * The HUNGARIAN ALGORITHM (Kuhn-Munkres): given a cost for every (row, column) pair, pick one column for every row,
 * no column twice, with the smallest total cost - in O(n² · m) time.
 *
 * Kaushal uses it for staffing: rows = open project slots, columns = people (plus "leave empty" columns),
 * cost = 100 - fit. Picking the best person for each slot one by one ("greedy") can be worse: the best person for
 * slot 1 may be the ONLY good person for slot 2. The Hungarian method finds the best total.
 *
 * How it works (potentials version): every row i has a number u[i] and every column j a number v[j] with
 * u[i] + v[j] <= cost[i][j]. Pairs where this is equal are "tight". Rows are added one at a time; for each new row
 * we look for a path of tight pairs to a free column (like Dijkstra), raising/lowering u and v by the smallest slack
 * until such a path appears, then flip the path. At the end all chosen pairs are tight, which proves the total is minimal.
 */
public final class Hungarian {
    private Hungarian() { }

    /** cost[n][m] with n <= m. Returns for every row the chosen column. */
    public static int[] solve(double[][] cost) {
        int n = cost.length;
        if (n == 0) return new int[0];
        int m = cost[0].length;
        if (m < n) throw new IllegalArgumentException("Need at least as many columns as rows");
        double[] u = new double[n + 1], v = new double[m + 1];
        int[] p = new int[m + 1], way = new int[m + 1];          // p[j] = row matched to column j (1-based, 0 = free)
        for (int i = 1; i <= n; i++) {
            p[0] = i;
            int j0 = 0;
            double[] minv = new double[m + 1];
            Arrays.fill(minv, Double.POSITIVE_INFINITY);
            boolean[] used = new boolean[m + 1];
            do {
                used[j0] = true;
                int i0 = p[j0], j1 = 0;
                double delta = Double.POSITIVE_INFINITY;
                for (int j = 1; j <= m; j++) {
                    if (used[j]) continue;
                    double cur = cost[i0 - 1][j - 1] - u[i0] - v[j];
                    if (cur < minv[j]) { minv[j] = cur; way[j] = j0; }
                    if (minv[j] < delta) { delta = minv[j]; j1 = j; }
                }
                for (int j = 0; j <= m; j++) {
                    if (used[j]) { u[p[j]] += delta; v[j] -= delta; }
                    else minv[j] -= delta;
                }
                j0 = j1;
            } while (p[j0] != 0);
            do {                                                  // flip the path
                int j1 = way[j0];
                p[j0] = p[j1];
                j0 = j1;
            } while (j0 != 0);
        }
        int[] rowToCol = new int[n];
        for (int j = 1; j <= m; j++) if (p[j] != 0) rowToCol[p[j] - 1] = j - 1;
        return rowToCol;
    }

    public static double total(double[][] cost, int[] pick) {
        double t = 0;
        for (int i = 0; i < pick.length; i++) t += cost[i][pick[i]];
        return t;
    }
}
