package io.taiyiflow.sdk.worker.auth;

/** Persistent storage for worker credentials, kept separate from ordinary workspaces. */
public interface CredentialStore {

    PersistentCredentials load() throws Exception;

    void save(PersistentCredentials credentials) throws Exception;

    void clear() throws Exception;
}
