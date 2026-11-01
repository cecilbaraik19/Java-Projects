package com.cecil.invoice.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A supplier who sends invoices. Vendors are created automatically the first time
 * their GSTIN or name is seen, and they "learn" from corrections:
 * labelHints remembers which words this vendor prints in front of each field
 * (for example invoiceNo -> "bill ref"), so the next invoice is read better.
 */
public class Vendor {
    public String id;
    public String name;
    public String gstin = "";
    public List<String> aliases = new ArrayList<>();          // other spellings of the name
    public String defaultCategory = "";
    public Map<String, String> labelHints = new LinkedHashMap<>();
    public int corrections;                                  // how many fields people fixed
    public String createdAt;
}
