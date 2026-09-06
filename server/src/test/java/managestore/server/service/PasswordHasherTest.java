package managestore.server.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PasswordHasherTest {

    @Test
    void correctPasswordMatchesItsOwnHash() {
        String salt = PasswordHasher.newSalt();
        String hash = PasswordHasher.hash("Secret123", salt);

        assertTrue(PasswordHasher.matches("Secret123", salt, hash));
    }

    @Test
    void wrongPasswordDoesNotMatch() {
        String salt = PasswordHasher.newSalt();
        String hash = PasswordHasher.hash("Secret123", salt);

        assertFalse(PasswordHasher.matches("wrongPassword", salt, hash));
    }

    @Test
    void sameRawPasswordProducesDifferentHashesUnderDifferentSalts() {
        String hashA = PasswordHasher.hash("Secret123", PasswordHasher.newSalt());
        String hashB = PasswordHasher.hash("Secret123", PasswordHasher.newSalt());

        assertNotEquals(hashA, hashB, "two random salts should (overwhelmingly likely) never collide");
    }

    @Test
    void neverStoresOrReturnsThePlaintextPassword() {
        String salt = PasswordHasher.newSalt();
        String hash = PasswordHasher.hash("Secret123", salt);

        assertFalse(hash.contains("Secret123"));
    }

    @Test
    void aMissingSaltFailsWithAClearMessageInsteadOfANullPointerException() {
        // A corrupted accounts.json entry (a hand-edited file, or a partial write left over
        // from a crash) could have no salt recorded at all. Before this was checked explicitly,
        // Base64-decoding a null salt threw a bare NullPointerException, which reached the
        // client as the unhelpful "Request failed: null" -- with no hint that the stored
        // account data itself was the problem.
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> PasswordHasher.hash("Secret123", null));
        assertTrue(e.getMessage().contains("salt"), "the message should point at the actual problem: " + e.getMessage());
    }
}
