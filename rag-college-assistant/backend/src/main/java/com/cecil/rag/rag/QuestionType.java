package com.cecil.rag.rag;

import com.cecil.rag.util.Text;
import java.util.regex.Pattern;

/**
 * What kind of answer the question wants. "How much is the hostel fee?" wants an amount of money,
 * so a sentence that contains "₹" is a better answer than one that does not.
 */
public enum QuestionType {
    TIME("a time", Text.TIME),
    MONEY("an amount", Text.MONEY),
    DATE("a date or day", Text.DATE),
    NUMBER("a number", Text.NUMBER),
    CONTACT("a person or contact", Text.CONTACT),
    GENERAL("an explanation", null);

    public final String label;
    public final Pattern pattern;

    QuestionType(String label, Pattern pattern) {
        this.label = label;
        this.pattern = pattern;
    }

    private static final Pattern Q_TIME = Pattern.compile("(?i)\\bwhat time\\b|\\bin-?time\\b|\\btimings?\\b|\\bhours\\b|\\bopen(?:s|ed)?\\b|\\bclos(?:e|es|ing)\\b|\\btill when\\b|\\buntil when\\b");
    private static final Pattern Q_MONEY = Pattern.compile("(?i)\\bhow much\\b|\\bfees?\\b|\\bcost\\b|\\bcharges?d?\\b|\\bfine\\b|\\bprice\\b|\\bdeposit\\b|\\brupees\\b|\\bpenalty\\b|\\bstipend\\b|\\bpackage\\b|\\bctc\\b|\\bamount\\b");
    private static final Pattern Q_DATE = Pattern.compile("(?i)\\bwhen\\b|\\bdate\\b|\\bdeadline\\b|\\blast day\\b|\\bwhich day\\b|\\bstarts?\\b|\\bbegins?\\b|\\bschedule\\b");
    private static final Pattern Q_NUMBER = Pattern.compile("(?i)\\bhow many\\b|\\bminimum\\b|\\bmaximum\\b|\\bpercent(?:age)?\\b|%|\\blimit\\b|\\bhow long\\b|\\bduration\\b|\\bnumber of\\b|\\bcredits?\\b|\\bpass(?:ing)? marks\\b");
    private static final Pattern Q_CONTACT = Pattern.compile("(?i)\\bwho\\b|\\bwhom\\b|\\bcontact\\b|\\bemail\\b|\\bphone\\b|\\bcall\\b|\\breach\\b|\\bin charge\\b|\\bwhere (?:is|can i find)\\b");

    private static final Pattern MONEY_WORD = Pattern.compile("(?i)\\bfees?\\b|\\bcost\\b|\\bcharges?\\b|\\bfine\\b|\\brupees\\b|\\bdeposit\\b|\\bpenalty\\b|\\bprice\\b|\\bamount\\b|\\bstipend\\b|\\bpackage\\b");
    private static final Pattern COUNTABLE = Pattern.compile("(?i)attendance|percent|marks|credits?|cgpa|sgpa|books|days|leaves?|students|hours|years|semesters|subjects");
    private static final Pattern Q_NUMBER_OR_MUCH = Pattern.compile("(?i)\\bhow much\\b|\\bhow many\\b|\\bminimum\\b|\\bmaximum\\b|\\blimit\\b");

    public static QuestionType of(String question) {
        if (question == null) return GENERAL;
        boolean moneyWord = MONEY_WORD.matcher(question).find();
        if (!moneyWord && COUNTABLE.matcher(question).find() && Q_NUMBER_OR_MUCH.matcher(question).find()) return NUMBER;
        if (Q_MONEY.matcher(question).find() && !Q_TIME.matcher(question).find()) return MONEY;
        if (Q_TIME.matcher(question).find()) return TIME;
        if (Q_NUMBER.matcher(question).find()) return NUMBER;
        if (Q_DATE.matcher(question).find()) return DATE;
        if (Q_CONTACT.matcher(question).find()) return CONTACT;
        return GENERAL;
    }

    public boolean foundIn(String text) {
        return pattern != null && pattern.matcher(text).find();
    }
}
