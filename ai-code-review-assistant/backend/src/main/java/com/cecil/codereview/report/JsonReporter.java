package com.cecil.codereview.report;

import com.cecil.codereview.metrics.FileMetrics;
import com.cecil.codereview.metrics.MethodInfo;
import com.cecil.codereview.model.FileReport;
import com.cecil.codereview.model.Issue;
import com.cecil.codereview.model.ReviewReport;
import com.cecil.codereview.model.Severity;

import java.util.Locale;
import java.util.Map;

/** Machine-readable report, useful for CI pipelines and other tools. */
public final class JsonReporter {
    private JsonReporter() { }

    public static String render(ReviewReport r) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"tool\": \"AI Code Review Assistant\",\n");
        sb.append("  \"createdAt\": ").append(Json.str(r.createdAt().toString())).append(",\n");
        sb.append("  \"mode\": ").append(Json.str(r.reviewMode())).append(",\n");
        sb.append("  \"overallScore\": ").append(num(r.overallScore())).append(",\n");
        sb.append("  \"grade\": ").append(Json.str(r.grade())).append(",\n");
        sb.append("  \"counts\": {");
        Map<Severity, Integer> counts = r.countBySeverity();
        int k = 0;
        for (Severity s : Severity.values()) {
            sb.append(k++ > 0 ? ", " : "").append(Json.str(s.name())).append(": ").append(counts.get(s));
        }
        sb.append("},\n");
        sb.append("  \"summary\": [");
        for (int i = 0; i < r.aiSummary().size(); i++) {
            sb.append(i > 0 ? ", " : "").append(Json.str(r.aiSummary().get(i).trim()));
        }
        sb.append("],\n");
        sb.append("  \"files\": [\n");
        for (int f = 0; f < r.files().size(); f++) {
            FileReport fr = r.files().get(f);
            FileMetrics m = fr.metrics();
            sb.append("    {\n");
            sb.append("      \"file\": ").append(Json.str(fr.file())).append(",\n");
            sb.append("      \"language\": ").append(Json.str(fr.language())).append(",\n");
            sb.append("      \"score\": ").append(num(fr.score())).append(",\n");
            sb.append("      \"grade\": ").append(Json.str(fr.grade())).append(",\n");
            sb.append("      \"metrics\": {\"totalLines\": ").append(m.totalLines())
                    .append(", \"codeLines\": ").append(m.codeLines())
                    .append(", \"commentLines\": ").append(m.commentLines())
                    .append(", \"blankLines\": ").append(m.blankLines())
                    .append(", \"classes\": ").append(m.classCount())
                    .append(", \"methods\": ").append(m.methods().size())
                    .append(", \"averageComplexity\": ").append(num(m.averageComplexity()))
                    .append(", \"maxComplexity\": ").append(m.maxComplexity()).append("},\n");
            sb.append("      \"methodDetails\": [");
            for (int i = 0; i < m.methods().size(); i++) {
                MethodInfo mi = m.methods().get(i);
                sb.append(i > 0 ? ", " : "").append("{\"name\": ").append(Json.str(mi.name()))
                        .append(", \"line\": ").append(mi.startLine())
                        .append(", \"lines\": ").append(mi.length())
                        .append(", \"params\": ").append(mi.parameterCount())
                        .append(", \"complexity\": ").append(mi.complexity())
                        .append(", \"nesting\": ").append(mi.maxNesting()).append('}');
            }
            sb.append("],\n");
            sb.append("      \"aiReview\": ").append(Json.str(fr.aiReview())).append(",\n");
            sb.append("      \"issues\": [\n");
            for (int i = 0; i < fr.issues().size(); i++) {
                Issue is = fr.issues().get(i);
                sb.append("        {\"rule\": ").append(Json.str(is.ruleId()))
                        .append(", \"name\": ").append(Json.str(is.ruleName()))
                        .append(", \"severity\": ").append(Json.str(is.severity().name()))
                        .append(", \"category\": ").append(Json.str(is.category().label()))
                        .append(", \"line\": ").append(is.line())
                        .append(", \"message\": ").append(Json.str(is.message()))
                        .append(", \"suggestion\": ").append(Json.str(is.suggestion()))
                        .append(", \"snippet\": ").append(Json.str(is.snippet()))
                        .append(", \"autoFixable\": ").append(is.autoFixable()).append('}')
                        .append(i < fr.issues().size() - 1 ? ",\n" : "\n");
            }
            sb.append("      ]\n");
            sb.append("    }").append(f < r.files().size() - 1 ? ",\n" : "\n");
        }
        sb.append("  ]\n}\n");
        return sb.toString();
    }

    private static String num(double d) {
        return String.format(Locale.ROOT, "%.1f", d);
    }
}
