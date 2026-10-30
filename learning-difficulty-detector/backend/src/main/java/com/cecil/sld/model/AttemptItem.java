package com.cecil.sld.model;

/** One answered question inside an attempt, with the signals used to detect difficulty. */
public class AttemptItem {
    public String questionId;
    public String topicId;
    /** Option chosen, or -1 if skipped. */
    public int chosen = -1;
    public boolean correct;
    /** Time spent on the question, in seconds. */
    public int seconds;
    /** How sure the student was: 1 = guessing, 2 = not sure, 3 = sure. */
    public int confidence = 2;
    public boolean hintUsed;
    public int level = 2;

    public AttemptItem() {}
}
