package tech.starryapi.admin;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

class AdminDtosValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void acceptsLegacyLoginPasswordsButBoundsRequestCost() {
        assertThat(validator.validate(new AdminDtos.LoginRequest("admin", "legacy"))).isEmpty();
        assertThat(validator.validate(new AdminDtos.LoginRequest("admin", "x".repeat(129)))).isNotEmpty();
    }

    @Test
    void requiresTwelveToOneHundredTwentyEightCharactersForNewPasswords() {
        assertThat(validator.validate(new AdminDtos.ChangePasswordRequest("legacy", "x".repeat(11))))
            .isNotEmpty();
        assertThat(validator.validate(new AdminDtos.ChangePasswordRequest("legacy", "x".repeat(12))))
            .isEmpty();
        assertThat(validator.validate(new AdminDtos.ChangePasswordRequest("legacy", "x".repeat(128))))
            .isEmpty();
        assertThat(validator.validate(new AdminDtos.ChangePasswordRequest("legacy", "x".repeat(129))))
            .isNotEmpty();
    }
}
