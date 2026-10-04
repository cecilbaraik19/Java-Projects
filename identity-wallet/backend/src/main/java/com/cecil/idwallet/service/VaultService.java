package com.cecil.idwallet.service;

import com.cecil.idwallet.auth.AuthUser;
import com.cecil.idwallet.crypto.AesGcm;
import com.cecil.idwallet.crypto.CryptoUtil;
import com.cecil.idwallet.domain.UserAccount;
import com.cecil.idwallet.domain.VaultItem;
import com.cecil.idwallet.repo.VaultItemRepository;
import com.cecil.idwallet.web.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/** Encrypted personal document vault. */
@Service
public class VaultService {

    public static final List<String> CATEGORIES = List.of("PASSPORT", "NATIONAL_ID", "PAN_CARD", "DRIVING_LICENSE",
            "VOTER_ID", "BIRTH_CERTIFICATE", "EDUCATION", "MEDICAL", "FINANCE", "OTHER");

    private final VaultItemRepository repo;
    private final UserService userService;
    private final AuditService audit;
    private final ObjectMapper json = new ObjectMapper();

    public VaultService(VaultItemRepository repo, UserService userService, AuditService audit) {
        this.repo = repo;
        this.userService = userService;
        this.audit = audit;
    }

    public record FileData(String name, String contentType, byte[] bytes) {}

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(AuthUser actor) {
        return repo.findByOwnerIdOrderByUpdatedAtDesc(actor.id()).stream().map(this::summary).toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> detail(AuthUser actor, Long id) {
        VaultItem v = owned(actor, id);
        UserAccount u = userService.get(actor.id());
        Map<String, Object> m = summary(v);
        m.putAll(fields(u, v));
        return m;
    }

    @Transactional
    public Map<String, Object> save(AuthUser actor, Long id, String title, String category, String documentNumber,
                                    String notes, String expiryDate, MultipartFile file, String ip) {
        UserAccount u = userService.get(actor.id());
        VaultItem v = id == null ? new VaultItem() : owned(actor, id);
        if (title == null || title.isBlank() || title.length() > 120) throw ApiException.bad("Title is required (max 120 characters)");
        if (category == null || !CATEGORIES.contains(category)) throw ApiException.bad("Choose a valid category");
        if (documentNumber != null && documentNumber.length() > 100) throw ApiException.bad("Document number is too long");
        if (notes != null && notes.length() > 2000) throw ApiException.bad("Notes are too long");
        v.ownerId = u.id;
        v.title = title.trim();
        v.category = category;
        try {
            v.expiryDate = (expiryDate == null || expiryDate.isBlank()) ? null : LocalDate.parse(expiryDate);
        } catch (Exception e) {
            throw ApiException.bad("Expiry date must be YYYY-MM-DD");
        }
        v.updatedAt = Instant.now();
        if (v.id == null) v = repo.save(v); // need the id for the encryption context
        byte[] dek = userService.dek(u);
        try {
            Map<String, String> f = new LinkedHashMap<>();
            f.put("documentNumber", documentNumber == null ? "" : documentNumber.trim());
            f.put("notes", notes == null ? "" : notes.trim());
            v.fieldsEnc = AesGcm.encryptString(dek, json.writeValueAsString(f), "vault-fields:" + v.id);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        if (file != null && !file.isEmpty()) {
            byte[] bytes;
            try {
                bytes = file.getBytes();
            } catch (Exception e) {
                throw ApiException.bad("Could not read the upload");
            }
            String type = sniff(bytes);
            if (type == null) throw ApiException.bad("Only PDF, PNG, JPEG or WEBP files are allowed");
            v.contentType = type;
            v.fileName = safeName(file.getOriginalFilename());
            v.fileSize = bytes.length;
            v.fileSha256 = CryptoUtil.sha256Hex(bytes);
            v.fileEnc = AesGcm.encrypt(dek, bytes, "vault-file:" + v.id);
        }
        repo.save(v);
        audit.log(u.id, u.email, id == null ? "VAULT_ADDED" : "VAULT_UPDATED", v.title, ip);
        return detail(actor, v.id);
    }

    @Transactional(readOnly = true)
    public FileData download(AuthUser actor, Long id, String ip) {
        VaultItem v = owned(actor, id);
        if (v.fileEnc == null) throw ApiException.notFound("No file attached");
        UserAccount u = userService.get(actor.id());
        byte[] bytes = AesGcm.decrypt(userService.dek(u), v.fileEnc, "vault-file:" + v.id);
        if (!CryptoUtil.sha256Hex(bytes).equals(v.fileSha256)) throw new IllegalStateException("Integrity check failed");
        audit.log(u.id, u.email, "VAULT_DOWNLOADED", v.title, ip);
        return new FileData(v.fileName, v.contentType, bytes);
    }

    @Transactional
    public void delete(AuthUser actor, Long id, String ip) {
        VaultItem v = owned(actor, id);
        repo.delete(v);
        audit.log(actor.id(), actor.email(), "VAULT_DELETED", v.title, ip);
    }

    private VaultItem owned(AuthUser actor, Long id) {
        return repo.findById(id).filter(v -> v.ownerId.equals(actor.id()))
                .orElseThrow(() -> ApiException.notFound("Document not found"));
    }

    private Map<String, Object> fields(UserAccount u, VaultItem v) {
        if (v.fieldsEnc == null) return Map.of("documentNumber", "", "notes", "");
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> f = json.readValue(AesGcm.decryptString(userService.dek(u), v.fieldsEnc, "vault-fields:" + v.id), Map.class);
            return f;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Map<String, Object> summary(VaultItem v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", v.id);
        m.put("title", v.title);
        m.put("category", v.category);
        m.put("expiryDate", v.expiryDate);
        m.put("hasFile", v.fileEnc != null);
        m.put("fileName", v.fileName);
        m.put("contentType", v.contentType);
        m.put("fileSize", v.fileSize);
        m.put("fileSha256", v.fileSha256);
        m.put("createdAt", v.createdAt);
        m.put("updatedAt", v.updatedAt);
        return m;
    }

    /** Decide the file type from its first bytes ("magic numbers"), never from the name or browser-sent type. */
    static String sniff(byte[] b) {
        if (b.length >= 5 && b[0] == '%' && b[1] == 'P' && b[2] == 'D' && b[3] == 'F' && b[4] == '-') return "application/pdf";
        if (b.length >= 8 && (b[0] & 0xff) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return "image/png";
        if (b.length >= 3 && (b[0] & 0xff) == 0xFF && (b[1] & 0xff) == 0xD8 && (b[2] & 0xff) == 0xFF) return "image/jpeg";
        if (b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F' && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') return "image/webp";
        return null;
    }

    static String safeName(String n) {
        if (n == null || n.isBlank()) return "document";
        n = n.replaceAll(".*[/\\\\]", "").replaceAll("[^A-Za-z0-9._ -]", "_");
        return n.length() > 100 ? n.substring(n.length() - 100) : n;
    }
}
