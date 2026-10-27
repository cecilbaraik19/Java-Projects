package com.cecil.pipeline.insights;

import com.cecil.pipeline.model.Activity;
import com.cecil.pipeline.model.Contact;
import com.cecil.pipeline.model.Deal;
import com.cecil.pipeline.model.Enums.ActivityType;
import com.cecil.pipeline.model.Enums.Priority;
import com.cecil.pipeline.model.Enums.Stage;
import com.cecil.pipeline.util.Json;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Gives every open deal a "health score" from 0 to 100, so reps know which deals to work on first.
 *
 * The score is the sum of simple, explainable points:
 *   stage reached (up to 35), recent activity (up to 20), how fresh the last contact is (up to 15),
 *   talking to a decision maker (10), a sensible close date (10), a next step planned (10),
 *   and minus points for being stuck in one stage too long or a close date that has already passed.
 * Each point comes with a one-line reason that is shown in the deal page.
 */
public final class DealScorer {

    public static final int HOT = 70;
    public static final int WARM = 45;

    private DealScorer() { }

    public record Factor(String name, int points, String reason) {
        public Map<String, Object> toMap() { return Json.obj("name", name, "points", points, "reason", reason); }
    }

    public record Result(int score, String label, List<Factor> factors) {
        public Map<String, Object> toMap() {
            List<Object> fs = new ArrayList<>();
            for (Factor f : factors) fs.add(f.toMap());
            return Json.obj("score", score, "label", label, "factors", fs);
        }
    }

    public static String label(int score) {
        return score >= HOT ? "HOT" : score >= WARM ? "WARM" : "COLD";
    }

    public static Result score(Deal d, List<Activity> acts, Contact contact, LocalDate today) {
        List<Factor> f = new ArrayList<>();
        ZoneId zone = ZoneId.systemDefault();

        if (d.stage == Stage.WON) return new Result(100, "WON", List.of(new Factor("Closed", 100, "This deal is won")));
        if (d.stage == Stage.LOST) return new Result(0, "LOST", List.of(new Factor("Closed", 0, "This deal is lost")));

        // 1. stage
        int stagePts = switch (d.stage) {
            case LEAD -> 5;
            case QUALIFIED -> 15;
            case PROPOSAL -> 25;
            case NEGOTIATION -> 35;
            default -> 0;
        };
        f.add(new Factor("Stage", stagePts, "In the " + d.stage.label.toLowerCase() + " stage"));

        // 2. activity in the last 14 days (done activities only)
        LocalDate twoWeeksAgo = today.minusDays(14);
        int recent = 0;
        boolean meetingDone = false;
        for (Activity a : acts) {
            if (!a.done || a.doneAt == null) continue;
            LocalDate doneDay = Instant.parse(a.doneAt).atZone(zone).toLocalDate();
            if (!doneDay.isBefore(twoWeeksAgo)) {
                recent++;
                if (a.type == ActivityType.MEETING || a.type == ActivityType.DEMO) meetingDone = true;
            }
        }
        int actPts = Math.min(recent, 3) * 5 + (meetingDone ? 5 : 0);
        f.add(new Factor("Engagement", actPts, recent == 0
            ? "No calls, emails or meetings in the last 2 weeks"
            : recent + " activit" + (recent == 1 ? "y" : "ies") + " in the last 2 weeks" + (meetingDone ? ", including a meeting or demo" : "")));

        // 3. freshness of the last touch
        String last = d.lastActivityAt != null ? d.lastActivityAt : d.createdAt;
        long idle = last == null ? 99 : ChronoUnit.DAYS.between(Instant.parse(last).atZone(zone).toLocalDate(), today);
        int freshPts = idle <= 3 ? 15 : idle <= 7 ? 10 : idle <= 14 ? 5 : 0;
        f.add(new Factor("Last contact", freshPts, idle == 0 ? "Worked on today" : "Last worked on " + idle + " day" + (idle == 1 ? "" : "s") + " ago"));

        // 4. decision maker
        if (contact != null && contact.decisionMaker) {
            f.add(new Factor("Decision maker", 10, contact.name + " can approve the purchase"));
        } else if (contact != null) {
            f.add(new Factor("Decision maker", 3, "Talking to " + contact.name + ", who is not the final decision maker"));
        } else {
            f.add(new Factor("Decision maker", 0, "No contact person linked yet"));
        }

        // 5. close date
        if (d.expectedClose == null) {
            f.add(new Factor("Close date", 0, "No expected close date set"));
        } else if (d.expectedClose.isBefore(today)) {
            long late = ChronoUnit.DAYS.between(d.expectedClose, today);
            f.add(new Factor("Close date", -10, "Expected close date passed " + late + " day" + (late == 1 ? "" : "s") + " ago"));
        } else {
            long days = ChronoUnit.DAYS.between(today, d.expectedClose);
            f.add(new Factor("Close date", days <= 45 ? 10 : 5, days <= 45 ? "Closing within " + days + " days" : "Close date is " + days + " days away"));
        }

        // 6. next step planned
        boolean planned = acts.stream().anyMatch(a -> !a.done && a.dueDate != null && !a.dueDate.isBefore(today));
        boolean overdue = acts.stream().anyMatch(a -> a.isOverdue(today));
        if (planned) f.add(new Factor("Next step", 10, "A next step is planned"));
        else if (overdue) f.add(new Factor("Next step", 0, "A planned activity is overdue"));
        else f.add(new Factor("Next step", 0, "No next step planned"));

        // 7. stuck in stage
        long inStage = ChronoUnit.DAYS.between(d.stageEnteredAt().atZone(zone).toLocalDate(), today);
        if (inStage > 30) f.add(new Factor("Stuck", -10, "In the same stage for " + inStage + " days"));

        // 8. priority (small nudge)
        if (d.priority == Priority.HIGH) f.add(new Factor("Priority", 5, "Marked as high priority"));

        int total = 0;
        for (Factor x : f) total += x.points();
        total = Math.max(0, Math.min(100, total));
        return new Result(total, label(total), f);
    }
}
