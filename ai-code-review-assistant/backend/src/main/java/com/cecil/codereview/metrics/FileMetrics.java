package com.cecil.codereview.metrics;

import java.util.Collections;
import java.util.List;

/** Size and complexity numbers for one file. */
public final class FileMetrics {
    private final int totalLines;
    private final int codeLines;
    private final int commentLines;
    private final int blankLines;
    private final int classCount;
    private final List<MethodInfo> methods;

    public FileMetrics(int totalLines, int codeLines, int commentLines, int blankLines,
                       int classCount, List<MethodInfo> methods) {
        this.totalLines = totalLines;
        this.codeLines = codeLines;
        this.commentLines = commentLines;
        this.blankLines = blankLines;
        this.classCount = classCount;
        this.methods = Collections.unmodifiableList(methods);
    }

    public int totalLines() { return totalLines; }
    public int codeLines() { return codeLines; }
    public int commentLines() { return commentLines; }
    public int blankLines() { return blankLines; }
    public int classCount() { return classCount; }
    public List<MethodInfo> methods() { return methods; }

    public double commentRatio() {
        int denom = codeLines + commentLines;
        return denom == 0 ? 0 : (double) commentLines / denom;
    }

    public double averageComplexity() {
        if (methods.isEmpty()) return 0;
        return methods.stream().mapToInt(MethodInfo::complexity).average().orElse(0);
    }

    public int maxComplexity() {
        return methods.stream().mapToInt(MethodInfo::complexity).max().orElse(0);
    }
}
