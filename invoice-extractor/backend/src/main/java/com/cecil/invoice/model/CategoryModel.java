package com.cecil.invoice.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What the expense-category classifier has learned (a Naive Bayes model).
 *   docs  - how many invoices of each category it has seen
 *   words - for each category, how many times each word appeared
 * Saved in data/model.json, so learning survives a restart.
 */
public class CategoryModel {
    public Map<String, Double> docs = new LinkedHashMap<>();
    public Map<String, Map<String, Double>> words = new LinkedHashMap<>();
    public int trainedOn;
    public String updatedAt = "";
}
