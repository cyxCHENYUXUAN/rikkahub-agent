package com.rikkahub.agent.execution;

/** A live interactive terminal session. */
public interface TerminalHandle extends AutoCloseable {
    void write(String data);

    void resize(int columns, int rows);

    default String mode() { return "UNKNOWN"; }

    default ProcessOutput output() {
        throw new UnsupportedOperationException("terminal output unavailable");
    }

    /** Present only after exit and output drain. */
    default java.util.OptionalInt exitCode() {
        throw new UnsupportedOperationException("terminal exit query unavailable");
    }

    default void closeInput() {
        throw new UnsupportedOperationException("terminal input EOF unavailable");
    }

    @Override
    void close();
}
