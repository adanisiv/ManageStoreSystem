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
        // 16 random bytes (128 bits) is plenty of entropy to make two users' salts collide
        // with negligible probability, so two identical passwords never produce the same hash.
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        // Store the salt as Base64 text since it lives alongside the hash in a JSON file.
        return Base64.getEncoder().encodeToString(salt);
    }

    public static String hash(String password, String salt) {
        // A missing salt means the account record is corrupted — for example a hand-edited
        // accounts.json, or a partial write recovered from an older file format. Without this
        // check, Base64 decoding a null salt throws a bare NullPointerException, which reaches
        // the client as the unhelpful "Request failed: null" with no hint of what's actually
        // wrong, for every future login attempt on that one account.
        if (salt == null) {
            throw new IllegalStateException("Account has no password salt on record — the stored account data is corrupted");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] saltBytes = Base64.getDecoder().decode(salt);
            // Start from the raw UTF-8 bytes of the password itself.
            byte[] current = password.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            // Re-hash ITERATIONS (100,000) times, mixing the salt in before every round. Each
            // round feeds the previous round's output back in as the new input, so the final
            // result depends on having repeated the full computation that many times — this is
            // the "stretching" that makes brute-forcing the hash slow, one guess at a time.
            for (int i = 0; i < ITERATIONS; i++) {
                digest.update(saltBytes);
                current = digest.digest(current);
            }
            return Base64.getEncoder().encodeToString(current);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is a standard JDK algorithm, so this should be unreachable in practice.
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    public static boolean matches(String password, String salt, String expectedHash) {
        // Re-derive the hash from the candidate password using the account's stored salt, then
        // compare it to the stored hash — there is no way to "decrypt" the stored hash back to
        // a password, only to recompute and compare.
        String actualHash = hash(password, salt);
        // Comparing with String.equals stops at the first character that differs, so a wrong
        // guess that happens to share more of its prefix with the real hash takes a few
        // nanoseconds longer to reject than one that doesn't. That tiny timing difference is a
        // real, if hard to exploit, side channel over a network. MessageDigest.isEqual always
        // compares every byte no matter where the first mismatch is, so it takes the same amount
        // of time whether the guess is close or completely wrong.
        return MessageDigest.isEqual(
                actualHash.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                expectedHash.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
