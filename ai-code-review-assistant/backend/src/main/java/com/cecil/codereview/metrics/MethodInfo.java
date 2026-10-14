package com.cecil.codereview.metrics;

/** Facts about one method (or constructor) found in a source file. */
public final class MethodInfo {
    private final String name;
    private final int startLine;
    private final int endLine;
    private final int parameterCount;
    private final int complexity;
    private final int maxNesting;
    private final boolean isPublic;

    public MethodInfo(String name, int startLine, int endLine, int parameterCount,
                      int complexity, int maxNesting, boolean isPublic) {
        this.name = name;
        this.startLine = startLine;
        this.endLine = endLine;
        this.parameterCount = parameterCount;
        this.complexity = complexity;
        this.maxNesting = maxNesting;
        this.isPublic = isPublic;
    }

    public String name() { return name; }
    public int startLine() { return startLine; }
    public int endLine() { return endLine; }
    public int length() { return endLine - startLine + 1; }
    public int parameterCount() { return parameterCount; }
    /** Cyclomatic complexity: 1 + number of decision points. */
    public int complexity() { return complexity; }
    public int maxNesting() { return maxNesting; }
    public boolean isPublic() { return isPublic; }
}
