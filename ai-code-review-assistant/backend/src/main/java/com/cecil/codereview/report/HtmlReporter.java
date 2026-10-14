package com.cecil.codereview.report;

import com.cecil.codereview.ai.KnowledgeBase;
import com.cecil.codereview.metrics.FileMetrics;
import com.cecil.codereview.metrics.MethodInfo;
import com.cecil.codereview.model.Category;
import com.cecil.codereview.model.FileReport;
import com.cecil.codereview.model.Issue;
import com.cecil.codereview.model.ReviewReport;
import com.cecil.codereview.model.Severity;

import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;

/** A single self-contained HTML page (open it in any browser, no internet needed). */
public final class HtmlReporter {
    private HtmlReporter() { }

    public static String render(ReviewReport r) {
        StringBuilder sb = new StringBuilder(32_000);
        double score = r.overallScore();
        sb.append("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
                .append("<title>Code Review Report</title><style>").append(CSS).append("</style></head><body>");
        sb.append("<header><div class=\"wrap head\"><div><h1>AI Code Review Report</h1><p class=\"muted\">")
                .append(esc(r.reviewMode())).append(" &middot; ")
                .append(r.createdAt().format(DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm")))
                .append(" &middot; ").append(r.files().size()).append(" file(s), ")
                .append(r.totalCodeLines()).append(" lines of code</p></div>")
                .append(ring(score, r.grade())).append("</div></header><main class=\"wrap\">");

        // ---- severity cards
        Map<Severity, Integer> counts = r.countBySeverity();
        sb.append("<section class=\"cards\">");
        for (Severity s : Severity.values()) {
            sb.append("<div class=\"card sev-").append(s.name().toLowerCase(Locale.ROOT)).append("\"><b>")
                    .append(counts.get(s)).append("</b><span>").append(s).append("</span><small>")
                    .append(esc(s.meaning())).append("</small></div>");
        }
        sb.append("</section>");

        // ---- AI summary + categories
        sb.append("<section class=\"grid2\"><div class=\"panel\"><h2>AI reviewer summary</h2><ul class=\"summary\">");
        for (String line : r.aiSummary()) {
            String t = line.trim();
            boolean sub = line.startsWith("  ");
            sb.append("<li").append(sub ? " class=\"sub\"" : "").append('>').append(esc(t)).append("</li>");
        }
        sb.append("</ul></div><div class=\"panel\"><h2>Issues by category</h2>");
        Map<Category, Integer> cats = r.countByCategory();
        int max = cats.values().stream().mapToInt(Integer::intValue).max().orElse(1);
        for (Map.Entry<Category, Integer> e : cats.entrySet()) {
            int pct = max == 0 ? 0 : e.getValue() * 100 / Math.max(1, max);
            sb.append("<div class=\"bar\"><span>").append(e.getKey().label()).append("</span><div><i style=\"width:")
                    .append(pct).append("%\"></i></div><b>").append(e.getValue()).append("</b></div>");
        }
        sb.append("</div></section>");

        // ---- filters
        sb.append("<section class=\"filters\"><input id=\"q\" type=\"search\" placeholder=\"Search issues, rules, files...\">");
        for (Severity s : Severity.values()) {
            sb.append("<label class=\"chip sev-").append(s.name().toLowerCase(Locale.ROOT))
                    .append("\"><input type=\"checkbox\" checked data-sev=\"").append(s.name()).append("\"> ")
                    .append(s).append("</label>");
        }
        sb.append("</section>");

        // ---- files
        for (FileReport f : r.files()) {
            FileMetrics m = f.metrics();
            sb.append("<details class=\"file\"").append(f.issues().isEmpty() ? "" : " open").append("><summary>")
                    .append("<span class=\"grade g").append(f.grade()).append("\">").append(f.grade()).append("</span>")
                    .append("<code>").append(esc(f.file())).append("</code><span class=\"muted\">")
                    .append(String.format(Locale.ROOT, "%.1f/100 &middot; %d issues", f.score(), f.issues().size()))
                    .append("</span></summary>");
            sb.append("<div class=\"metrics\">")
                    .append(metric("Code lines", m.codeLines()))
                    .append(metric("Comment lines", m.commentLines()))
                    .append(metric("Classes", m.classCount()))
                    .append(metric("Methods", m.methods().size()))
                    .append(metric("Avg complexity", String.format(Locale.ROOT, "%.1f", m.averageComplexity())))
                    .append(metric("Max complexity", m.maxComplexity()))
                    .append(metric("Comment ratio", String.format(Locale.ROOT, "%.0f%%", m.commentRatio() * 100)))
                    .append("</div>");
            for (Issue i : f.issues()) {
                String sev = i.severity().name().toLowerCase(Locale.ROOT);
                sb.append("<article class=\"issue\" data-sev=\"").append(i.severity().name()).append("\"><div class=\"ihead\">")
                        .append("<span class=\"pill sev-").append(sev).append("\">").append(i.severity()).append("</span>")
                        .append("<span class=\"pill cat\">").append(i.category().label()).append("</span>")
                        .append("<b>").append(esc(i.message())).append("</b>")
                        .append("<span class=\"muted right\">line ").append(i.line()).append(" &middot; <code>")
                        .append(i.ruleId()).append("</code>").append(i.autoFixable() ? " &middot; auto-fixable" : "")
                        .append("</span></div>");
                if (!i.snippet().isEmpty()) sb.append("<pre class=\"snip\">").append(esc(i.snippet())).append("</pre>");
                sb.append("<p class=\"fix\"><b>How to fix:</b> ").append(esc(i.suggestion())).append("</p>");
                KnowledgeBase.Advice a = KnowledgeBase.forRule(i.ruleId());
                if (a != null) {
                    sb.append("<details class=\"learn\"><summary>Why it matters + example</summary><p>")
                            .append(esc(a.why)).append("</p><div class=\"ba\"><div><small>Before</small><pre class=\"bad\">")
                            .append(esc(a.before)).append("</pre></div><div><small>After</small><pre class=\"good\">")
                            .append(esc(a.after)).append("</pre></div></div></details>");
                }
                sb.append("</article>");
            }
            if (f.issues().isEmpty()) sb.append("<p class=\"ok\">No issues found in this file.</p>");
            if (!m.methods().isEmpty()) {
                sb.append("<details class=\"methods\"><summary>Method metrics (").append(m.methods().size())
                        .append(")</summary><table><tr><th>Method</th><th>Line</th><th>Lines</th><th>Params</th>")
                        .append("<th>Complexity</th><th>Nesting</th></tr>");
                for (MethodInfo mi : m.methods()) {
                    sb.append("<tr><td><code>").append(esc(mi.name())).append("()</code></td><td>").append(mi.startLine())
                            .append("</td><td>").append(mi.length()).append("</td><td>").append(mi.parameterCount())
                            .append("</td><td class=\"").append(mi.complexity() > 10 ? "hot" : "").append("\">")
                            .append(mi.complexity()).append("</td><td>").append(mi.maxNesting()).append("</td></tr>");
                }
                sb.append("</table></details>");
            }
            if (!f.aiReview().isEmpty()) {
                sb.append("<div class=\"claude\"><h3>Claude AI review</h3><pre>").append(esc(f.aiReview())).append("</pre></div>");
            }
            sb.append("</details>");
        }
        sb.append("</main><footer class=\"wrap muted\">Generated by AI Code Review Assistant</footer>");
        sb.append("<script>").append(JS).append("</script></body></html>");
        return sb.toString();
    }

    private static String ring(double score, String grade) {
        double c = 2 * Math.PI * 42;
        double dash = c * score / 100.0;
        String color = score >= 80 ? "var(--good)" : score >= 70 ? "var(--minor)" : "var(--critical)";
        return String.format(Locale.ROOT,
                "<div class=\"ring\"><svg viewBox=\"0 0 100 100\"><circle cx=\"50\" cy=\"50\" r=\"42\" class=\"track\"/>"
                        + "<circle cx=\"50\" cy=\"50\" r=\"42\" style=\"stroke:%s\" stroke-dasharray=\"%.1f %.1f\" "
                        + "transform=\"rotate(-90 50 50)\"/></svg><div><b>%s</b><span>%.1f</span></div></div>",
                color, dash, c, grade, score);
    }

    private static String metric(String label, Object value) {
        return "<div><b>" + value + "</b><span>" + label + "</span></div>";
    }

    static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static final String CSS =
            ":root{--bg:#f6f7f9;--panel:#fff;--text:#1c2330;--muted:#667085;--line:#e4e7ec;--good:#12a150;"
                    + "--critical:#d92d20;--major:#f04438;--minor:#f79009;--info:#2e90fa;--code:#f2f4f7}"
                    + "@media (prefers-color-scheme:dark){:root{--bg:#0f1218;--panel:#171b23;--text:#e6e9ef;--muted:#98a2b3;"
                    + "--line:#2a303b;--code:#1f2430}}"
                    + "*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--text);font:15px/1.5 system-ui,-apple-system,Segoe UI,Roboto,sans-serif}"
                    + ".wrap{max-width:1100px;margin:0 auto;padding:0 16px}header{background:var(--panel);border-bottom:1px solid var(--line);padding:20px 0;margin-bottom:20px}"
                    + ".head{display:flex;justify-content:space-between;align-items:center;gap:16px}h1{margin:0;font-size:24px}h2{font-size:16px;margin:0 0 10px}"
                    + ".muted{color:var(--muted)}code,pre{font-family:ui-monospace,Consolas,monospace;font-size:13px}"
                    + ".ring{position:relative;width:96px;height:96px;flex:none}.ring svg{width:96px;height:96px}.ring circle{fill:none;stroke-width:9;stroke-linecap:round}"
                    + ".ring .track{stroke:var(--line)}.ring div{position:absolute;inset:0;display:flex;flex-direction:column;align-items:center;justify-content:center}"
                    + ".ring b{font-size:28px;line-height:1}.ring span{font-size:12px;color:var(--muted)}"
                    + ".cards{display:grid;grid-template-columns:repeat(4,1fr);gap:12px;margin-bottom:16px}.card{background:var(--panel);border:1px solid var(--line);border-top:4px solid;border-radius:10px;padding:12px}"
                    + ".card b{font-size:26px;display:block}.card span{font-weight:600}.card small{display:block;color:var(--muted)}"
                    + ".sev-critical{border-color:var(--critical)}.sev-major{border-color:var(--major)}.sev-minor{border-color:var(--minor)}.sev-info{border-color:var(--info)}"
                    + ".grid2{display:grid;grid-template-columns:3fr 2fr;gap:12px;margin-bottom:16px}.panel{background:var(--panel);border:1px solid var(--line);border-radius:10px;padding:16px}"
                    + ".summary{margin:0;padding-left:18px}.summary li{margin:4px 0}.summary li.sub{list-style:none;margin-left:-4px;color:var(--muted)}"
                    + ".bar{display:grid;grid-template-columns:120px 1fr 30px;gap:8px;align-items:center;margin:6px 0;font-size:13px}.bar div{background:var(--code);border-radius:4px;height:10px}"
                    + ".bar i{display:block;height:10px;border-radius:4px;background:var(--info)}.bar b{text-align:right}"
                    + ".filters{display:flex;flex-wrap:wrap;gap:8px;align-items:center;margin-bottom:12px}#q{flex:1;min-width:200px;padding:9px 12px;border-radius:8px;border:1px solid var(--line);background:var(--panel);color:var(--text)}"
                    + ".chip{border:1px solid;border-radius:20px;padding:5px 10px;font-size:13px;background:var(--panel);cursor:pointer}"
                    + ".file{background:var(--panel);border:1px solid var(--line);border-radius:10px;margin-bottom:12px;padding:0 16px}"
                    + ".file>summary{cursor:pointer;padding:14px 0;display:flex;gap:10px;align-items:center;flex-wrap:wrap}"
                    + ".grade{display:inline-flex;width:28px;height:28px;border-radius:6px;align-items:center;justify-content:center;font-weight:700;color:#fff}"
                    + ".gA,.gB{background:var(--good)}.gC{background:var(--minor)}.gD,.gF{background:var(--critical)}"
                    + ".metrics{display:grid;grid-template-columns:repeat(auto-fit,minmax(110px,1fr));gap:8px;margin-bottom:12px}.metrics div{background:var(--code);border-radius:8px;padding:8px}"
                    + ".metrics b{display:block;font-size:18px}.metrics span{font-size:12px;color:var(--muted)}"
                    + ".issue{border-top:1px solid var(--line);padding:12px 0}.ihead{display:flex;gap:8px;align-items:center;flex-wrap:wrap}.right{margin-left:auto;font-size:13px}"
                    + ".pill{font-size:11px;font-weight:700;border-radius:4px;padding:2px 6px;color:#fff}.pill.sev-critical{background:var(--critical)}.pill.sev-major{background:var(--major)}"
                    + ".pill.sev-minor{background:var(--minor)}.pill.sev-info{background:var(--info)}.pill.cat{background:var(--code);color:var(--text);font-weight:500}"
                    + "pre{background:var(--code);border-radius:6px;padding:8px 10px;overflow-x:auto;white-space:pre-wrap;margin:8px 0}.snip{border-left:3px solid var(--line)}"
                    + ".fix{margin:6px 0}.learn summary,.methods summary{cursor:pointer;color:var(--info);font-size:13px}.learn p{margin:6px 0}"
                    + ".ba{display:grid;grid-template-columns:1fr 1fr;gap:8px}.bad{border-left:3px solid var(--critical)}.good{border-left:3px solid var(--good)}"
                    + "table{border-collapse:collapse;width:100%;font-size:13px;margin:8px 0 12px}td,th{border-bottom:1px solid var(--line);padding:5px;text-align:left}td.hot{color:var(--critical);font-weight:700}"
                    + ".claude{border-top:1px solid var(--line);padding:8px 0 12px}.ok{color:var(--good)}.methods{padding-bottom:10px}footer{padding:24px 16px;font-size:13px}"
                    + "@media (max-width:720px){.cards{grid-template-columns:repeat(2,1fr)}.grid2,.ba{grid-template-columns:1fr}.right{margin-left:0}}";

    private static final String JS =
            "const q=document.getElementById('q');const boxes=[...document.querySelectorAll('input[data-sev]')];"
                    + "function apply(){const on=new Set(boxes.filter(b=>b.checked).map(b=>b.dataset.sev));const t=q.value.toLowerCase();"
                    + "document.querySelectorAll('article.issue').forEach(a=>{const show=on.has(a.dataset.sev)&&(!t||a.textContent.toLowerCase().includes(t)"
                    + "||a.closest('.file').querySelector('summary code').textContent.toLowerCase().includes(t));a.style.display=show?'':'none';});}"
                    + "q.addEventListener('input',apply);boxes.forEach(b=>b.addEventListener('change',apply));";
}
