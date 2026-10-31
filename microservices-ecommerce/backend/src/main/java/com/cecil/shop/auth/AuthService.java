package com.cecil.shop.auth;

import com.cecil.shop.common.ApiError;
import com.cecil.shop.common.Config;
import com.cecil.shop.common.Json;
import com.cecil.shop.common.Passwords;
import com.cecil.shop.common.Req;
import com.cecil.shop.common.Router;
import com.cecil.shop.common.ServiceBase;
import com.cecil.shop.common.Tokens;
import com.cecil.shop.seed.SeedData;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * AUTH SERVICE - owns user accounts and passwords. Nobody else stores passwords.
 *
 *  POST /auth/login      check password, return a signed token (8 hours)
 *  POST /auth/register   new customer account; publishes "user.registered"
 *  GET  /auth/users      list of accounts (admin)
 *
 * After 5 wrong passwords the username is locked for 5 minutes (stops password guessing).
 */
public class AuthService extends ServiceBase {
    private static final String USERS = "users";
    private static final long TOKEN_LIFE_MS = 8 * 3_600_000L;
    private static final Pattern USERNAME = Pattern.compile("[a-z0-9_.]{3,20}");
    private static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");

    private record Tries(int count, long firstAt, long lockedUntil) {}
    private final Map<String, Tries> failed = new ConcurrentHashMap<>();

    public AuthService(Config cfg) { super(Config.AUTH, cfg); }

    @Override public String role() { return "User accounts, passwords and login tokens"; }

    @Override
    protected void seed() {
        if (!store.isEmpty(USERS)) return;
        long now = System.currentTimeMillis();
        for (Object[] u : SeedData.users()) {
            store.insert(USERS, Json.obj("id", (long) (int) u[0], "username", u[1], "name", u[2], "email", u[3], "role", u[4],
                    "password", Passwords.hash((String) u[5]), "createdAt", now - 90 * 86_400_000L));
        }
    }

    @Override
    protected void routes(Router r) {
        r.post("/auth/login", this::login);
        r.post("/auth/register", this::register);
        r.get("/auth/me", req -> publicUser(user(req.requireUser())));
        r.get("/auth/users", req -> {
            req.requireAdmin();
            List<Object> out = new ArrayList<>();
            for (Map<String, Object> u : store.all(USERS)) out.add(publicUser(u));
            return out;
        });
    }

    private Map<String, Object> user(long id) {
        Map<String, Object> u = store.get(USERS, id);
        if (u == null) throw ApiError.notFound("User not found");
        return u;
    }

    private Object login(Req r) {
        Map<String, Object> b = r.json();
        String username = Json.str(b, "username", "").trim().toLowerCase();
        String password = Json.str(b, "password", "");
        long now = System.currentTimeMillis();
        Tries t = failed.get(username);
        if (t != null && t.lockedUntil > now) {
            long mins = Math.max(1, (t.lockedUntil - now + 59_999) / 60_000);
            throw new ApiError(429, "Too many wrong passwords. Try again in " + mins + " minute(s).");
        }
        Map<String, Object> u = store.findFirst(USERS, x -> username.equals(Json.str(x, "username")));
        if (u == null || !Passwords.matches(password, Json.str(u, "password"))) {
            Tries old = (t == null || now - t.firstAt > 5 * 60_000L) ? new Tries(0, now, 0) : t;
            int c = old.count + 1;
            failed.put(username, new Tries(c, old.firstAt, c >= 5 ? now + 5 * 60_000L : 0));
            throw new ApiError(401, "Wrong username or password");
        }
        failed.remove(username);
        return Json.obj("token", tokenFor(u, now), "user", publicUser(u));
    }

    private Object register(Req r) {
        Map<String, Object> b = r.json();
        String username = requireText(b, "username", "Username", 20).toLowerCase();
        String name = requireText(b, "name", "Full name", 60);
        String email = requireText(b, "email", "Email", 100).toLowerCase();
        String password = Json.str(b, "password", "");
        if (!USERNAME.matcher(username).matches()) throw ApiError.bad("Username: 3 to 20 small letters, digits, _ or .");
        if (!EMAIL.matcher(email).matches()) throw ApiError.bad("Please enter a valid email");
        if (password.length() < 6 || password.length() > 100) throw ApiError.bad("Password must be at least 6 characters");
        Map<String, Object> u;
        synchronized (this) {
            if (store.findFirst(USERS, x -> username.equals(Json.str(x, "username"))) != null) throw ApiError.conflict("That username is taken");
            if (store.findFirst(USERS, x -> email.equals(Json.str(x, "email"))) != null) throw ApiError.conflict("An account with that email already exists");
            u = store.insert(USERS, Json.obj("username", username, "name", name, "email", email, "role", "CUSTOMER",
                    "password", Passwords.hash(password), "createdAt", System.currentTimeMillis()));
        }
        // Other services react to this event: payment opens a wallet, notification says welcome.
        publish("user.registered", Json.obj("userId", u.get("id"), "name", name, "username", username), r.traceId);
        return com.cecil.shop.common.Resp.created(Json.obj("token", tokenFor(u, System.currentTimeMillis()), "user", publicUser(u)));
    }

    private String tokenFor(Map<String, Object> u, long now) {
        return Tokens.sign(Json.obj("uid", u.get("id"), "name", u.get("name"), "role", u.get("role"),
                "username", u.get("username"), "exp", now + TOKEN_LIFE_MS), cfg.internalKey);
    }

    private static Map<String, Object> publicUser(Map<String, Object> u) {
        return Json.obj("id", u.get("id"), "username", u.get("username"), "name", u.get("name"), "email", u.get("email"),
                "role", u.get("role"), "createdAt", u.get("createdAt"));
    }
}
