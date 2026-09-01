package managestore.server.service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Salted, iterated SHA-256 password hashing so {@link managestore.server.model.Account}
 * never stores a plaintext password, even in the local JSON file store.
 *
 * <p>A single SHA-256 round is fast enough that an attacker who obtains
 * accounts.json can brute-force it at billions of guesses/second on a GPU.
 * Re-hashing tens of thousands of times (the same manual-stretching idea
 * PBKDF2 formalizes) makes each guess proportionally slower to check without
 * adding a dependency — deliberately kept dependency-free like the rest of
 * this project's persistence layer.
 */
public final class PasswordHasher {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int ITERATIONS = 100_000;

    private PasswordHasher() {
    }

    public static String newSalt() {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        return Base64.getEncoder().encodeToString(salt);
    }

    public static String hash(String password, String salt) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] saltBytes = Base64.getDecoder().decode(salt);
            byte[] current = password.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            for (int i = 0; i < ITERATIONS; i++) {
                digest.update(saltBytes);
                current = digest.digest(current);
            }
            return Base64.getEncoder().encodeToString(current);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    public static boolean matches(String password, String salt, String expectedHash) {
        return hash(password, salt).equals(expectedHash);
    }
}
