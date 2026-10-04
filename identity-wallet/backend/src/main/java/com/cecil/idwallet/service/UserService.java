package com.cecil.idwallet.service;

import com.cecil.idwallet.auth.PasswordHasher;
import com.cecil.idwallet.crypto.EcKeys;
import com.cecil.idwallet.crypto.KeyVault;
import com.cecil.idwallet.domain.Role;
import com.cecil.idwallet.domain.UserAccount;
import com.cecil.idwallet.repo.UserRepository;
import com.cecil.idwallet.web.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.KeyPair;
import java.security.PrivateKey;
import java.util.regex.Pattern;

@Service
public class UserService {

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private final UserRepository users;
    private final KeyVault vault;

    public UserService(UserRepository users, KeyVault vault) {
        this.users = users;
        this.vault = vault;
    }

    /** Creates an account with its own signing key pair, DID and encryption key. */
    @Transactional
    public UserAccount create(String email, String fullName, String password, Role role, Long issuerId) {
        email = email == null ? "" : email.trim().toLowerCase();
        if (!EMAIL.matcher(email).matches()) throw ApiException.bad("Enter a valid email address");
        if (fullName == null || fullName.isBlank()) throw ApiException.bad("Name is required");
        String pwErr = PasswordHasher.policyError(password);
        if (pwErr != null) throw ApiException.bad(pwErr);
        if (users.findByEmailIgnoreCase(email).isPresent()) throw ApiException.bad("An account with this email already exists");

        KeyPair kp = EcKeys.generate();
        UserAccount u = new UserAccount();
        u.email = email;
        u.fullName = fullName.trim();
        u.role = role;
        u.issuerId = issuerId;
        u.passwordHash = PasswordHasher.hash(password);
        u.did = EcKeys.didFor(kp.getPublic());
        u.publicKey = EcKeys.encodePublic(kp.getPublic());
        u.wrappedPrivateKey = vault.wrapPrivateKey(kp.getPrivate(), u.did);
        u.wrappedDek = vault.wrapDek(vault.newDek(), u.did);
        return users.save(u);
    }

    public UserAccount get(Long id) {
        return users.findById(id).filter(u -> u.active).orElseThrow(() -> ApiException.unauthorized("Account not found"));
    }

    public byte[] dek(UserAccount u) {
        return vault.unwrapDek(u.wrappedDek, u.did);
    }

    public PrivateKey signingKey(UserAccount u) {
        return vault.unwrapPrivateKey(u.wrappedPrivateKey, u.did);
    }
}
