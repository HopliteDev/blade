package me.vaperion.blade.exception;

import java.util.function.Supplier;

public class BladeExitMessage extends RuntimeException {

    private final Supplier<String> messageSupplier; // Hoplite - support dynamic messages

    public BladeExitMessage() {
        this("Command execution failed.");
    }

    public BladeExitMessage(String message) {
        super(message);
        this.messageSupplier = () -> message; // Hoplite - support dynamic messages
    }

    // Hoplite start
    public BladeExitMessage(Supplier<String> messageSupplier) {
        this.messageSupplier = messageSupplier;
    }

    @Override
    public String getMessage() {
        return messageSupplier.get();
    }
    // Hoplite end
}
