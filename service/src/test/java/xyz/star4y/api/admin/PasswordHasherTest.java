package xyz.star4y.api.admin;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PasswordHasherTest {

    private final PasswordHasher hasher = new PasswordHasher();

    @Test
    void hashesAndVerifiesPassword() {
        String salt = hasher.newSalt();
        String hash = hasher.hash("secret", salt);

        assertThat(hash).hasSize(64);
        assertThat(hasher.matches("secret", salt, hash)).isTrue();
        assertThat(hasher.matches("wrong", salt, hash)).isFalse();
    }

    @Test
    void randomPasswordIsGenerated() {
        assertThat(hasher.randomPassword()).hasSize(36);
    }
}
