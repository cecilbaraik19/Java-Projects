package com.cecil.docqa.service.answer;

import java.util.List;

/** Something that writes an answer from the best matching document pieces. */
public interface AnswerGenerator {

    /** A numbered piece of a document given to the answerer. Number is used for citations like [1]. */
    record Context(int number, String fileName, Integer pageNumber, String text) {}

    /** "mode" tells the UI which engine answered: "claude" or "offline". */
    record Answer(String text, String mode) {}

    Answer generate(String question, List<Context> contexts) throws Exception;
}
