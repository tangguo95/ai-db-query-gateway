package com.tangguo.gateway.secret;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class InMemorySecretStore implements SecretStore {
    private final ConcurrentHashMap<String, String> secrets = new ConcurrentHashMap<>();

    @Override
    public void put(String account, String value) {
        secrets.put(account, value);
    }

    @Override
    public Optional<String> get(String account) {
        return Optional.ofNullable(secrets.get(account));
    }

    @Override
    public void delete(String account) {
        secrets.remove(account);
    }
}
