package managestore.server.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
}
