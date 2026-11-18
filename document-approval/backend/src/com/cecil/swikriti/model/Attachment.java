package com.cecil.swikriti.model;

import com.cecil.swikriti.util.Json;

import java.util.Map;

/** A file attached to a document (PDF, PNG or JPG, at most 1 MB). The bytes are kept in data/files/<sha>.bin. */
public class Attachment implements Json.Writable {
    public String name;
    public String mime;
    public long size;
    public String sha;     // SHA-256 of the bytes = the file's fingerprint (and its file name in data/files)

    @Override public Map<String, Object> toJson() { return Json.obj("name", name, "mime", mime, "size", size, "sha", sha); }

    public static Attachment fromJson(Object o) {
        if (!(o instanceof Map<?, ?> raw)) return null;
        Map<String, Object> m = Template.cast(raw);
        Attachment a = new Attachment();
        a.name = Json.str(m, "name");
        a.mime = Json.str(m, "mime");
        a.size = Json.num(m, "size", 0);
        a.sha = Json.str(m, "sha");
        return a.sha == null ? null : a;
    }
}
