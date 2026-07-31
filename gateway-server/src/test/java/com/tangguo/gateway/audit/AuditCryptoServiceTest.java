package com.tangguo.gateway.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.tangguo.gateway.secret.InMemorySecretStore;
import org.junit.jupiter.api.Test;

class AuditCryptoServiceTest {

    @Test
    void encryptsWithRandomNonceAndAuthenticatesCiphertext() {
        AuditCryptoService crypto = new AuditCryptoService(new InMemorySecretStore());
        crypto.initializeKeys();

        String first = crypto.encrypt("production sql");
        String second = crypto.encrypt("production sql");

        assertThat(first).isNotEqualTo(second);
        assertThat(crypto.decrypt(first)).isEqualTo("production sql");
        assertThat(crypto.hmac("event")).hasSizeGreaterThan(30);
        assertThat(crypto.constantTimeEquals(crypto.hmac("event"), crypto.hmac("event"))).isTrue();
    }
}
