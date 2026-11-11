package io.taiyiflow.sdk.worker;

public record Ability(String name) {
    public Ability {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Ability name must not be blank");
        }
    }

    public static Ability shell() {
        return new Ability("shell");
    }

    public static Ability files() {
        return new Ability("files");
    }

    public static Ability search() {
        return new Ability("search");
    }

    public static Ability of(String name) {
        return new Ability(name);
    }

    @Override
    public String toString() {
        return name;
    }
}
