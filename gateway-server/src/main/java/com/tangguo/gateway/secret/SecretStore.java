package com.tangguo.gateway.secret;

import java.util.Optional;

public interface SecretStore {
    void put(String account, String value);

    Optional<String> get(String account);

    void delete(String account);
}
