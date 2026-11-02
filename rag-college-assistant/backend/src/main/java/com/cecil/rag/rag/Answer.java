package com.cecil.rag.rag;

import java.util.ArrayList;
import java.util.List;

/** What a generator gives back. */
public final class Answer {
    public String text = "";          // the answer with [1] [2] citation marks
    public String shortAnswer = "";   // e.g. "₹38,000" (empty if there is no single value)
    public String mode = "offline";   // offline | claude
    public boolean found = true;      // false = the sources do not contain the answer
    public double confidence;         // 0..1
    public double grounded = 1;       // share of answer sentences supported by the sources
    public List<String> unsupported = new ArrayList<>();
    public String note = "";          // e.g. "Claude not reachable, used the offline answer"
    public String prompt = "";        // what was sent to Claude (shown in the Press Room)
}
