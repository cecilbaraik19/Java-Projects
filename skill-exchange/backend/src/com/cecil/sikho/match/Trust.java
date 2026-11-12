package com.cecil.sikho.match;

import com.cecil.sikho.model.Exchange;
import com.cecil.sikho.model.Review;
import com.cecil.sikho.store.DataStore;
import com.cecil.sikho.util.Json;

import java.util.HashMap;
import java.util.Map;

/**
 * How much the community can rely on a member, from two things:
 *
 *  1. Rating - a Bayesian average, so one 5-star review does not beat fifty 4.8-star reviews:
 *        rating = (C × m + sum of stars) / (C + number of reviews)
 *     m = the community's average stars, C = 3 (as if every member starts with 3 "average" reviews).
 *
 *  2. Reliability - do they turn up? With smoothing, so a brand-new member starts at a fair 67% (2 of 3):
 *        reliability = (completed + 2) / (completed + let-downs + 3)
 *     Let-downs = late cancellations (less than 24 hours before the session) + disputes decided against them.
 */
public class Trust {
    public static final double C = 3.0;

    public record Score(double rating, int reviews, double reliability, int completed, int letDowns, double hoursTaught) {
        public Map<String, Object> toJson() {
            return Json.obj("rating", Math.round(rating * 100) / 100.0, "reviews", reviews,
                    "reliability", Math.round(reliability * 100), "completed", completed, "letDowns", letDowns,
                    "hoursTaught", hoursTaught);
        }
    }

    private final Map<String, Score> scores = new HashMap<>();
    private final double communityMean;

    public Trust(DataStore store) {
        synchronized (store) {
            Map<String, double[]> stars = new HashMap<>();   // sum, count
            double all = 0;
            int n = 0;
            for (Review r : store.reviews.values()) {
                double[] s = stars.computeIfAbsent(r.toId, k -> new double[2]);
                s[0] += r.stars;
                s[1]++;
                all += r.stars;
                n++;
            }
            communityMean = n == 0 ? 4.0 : all / n;
            Map<String, int[]> rel = new HashMap<>();          // completed, letDowns, hoursTaught
            for (Exchange e : store.exchanges.values()) {
                if (e.status == Exchange.Status.COMPLETED) {
                    rel.computeIfAbsent(e.teacherId, k -> new int[3])[0]++;
                    rel.computeIfAbsent(e.learnerId, k -> new int[3])[0]++;
                    rel.get(e.teacherId)[2] += e.hours;
                }
                if (e.lateCancel && e.cancelledBy != null) rel.computeIfAbsent(e.cancelledBy, k -> new int[3])[1]++;
                if (e.faultOf != null) rel.computeIfAbsent(e.faultOf, k -> new int[3])[1]++;
            }
            for (String id : store.users.keySet()) {
                double[] s = stars.getOrDefault(id, new double[2]);
                int[] r = rel.getOrDefault(id, new int[3]);
                double rating = (C * communityMean + s[0]) / (C + s[1]);
                double reliability = (r[0] + 2.0) / (r[0] + r[1] + 3.0);
                scores.put(id, new Score(rating, (int) s[1], reliability, r[0], r[1], r[2]));
            }
        }
    }

    public double communityMean() { return communityMean; }

    public Score of(String userId) {
        return scores.getOrDefault(userId, new Score(communityMean, 0, 2.0 / 3.0, 0, 0, 0));
    }
}
