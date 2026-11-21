package io.taiyiflow.sdk.core.security;

import io.taiyiflow.sdk.hooks.PermissionPolicy;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable permission configuration consumed by {@link PermissionGate}. Values originate only
 * from the SDK builder / worker.yml / Spring properties; there is no runtime mutation channel, so
 * skill content and model arguments cannot alter rules or policies.
 */
public final class PermissionSettings {

    private final PermissionPolicy policy;
    private final List<PermissionRule> rules;
    private final WriteConfirmation writeConfirmation;
    private final BrowserPermissionMode browserPermissionMode;
    private final Map<ToolCategory, PermissionPolicy> categoryPolicies;

    private PermissionSettings(Builder builder) {
        this.policy = builder.policy;
        this.rules = List.copyOf(builder.rules);
        this.writeConfirmation = builder.writeConfirmation;
        this.browserPermissionMode = builder.browserPermissionMode;
        this.categoryPolicies = Map.copyOf(builder.categoryPolicies);
    }

    public static PermissionSettings defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public PermissionPolicy getPolicy() {
        return policy;
    }

    public List<PermissionRule> getRules() {
        return rules;
    }

    public WriteConfirmation getWriteConfirmation() {
        return writeConfirmation;
    }

    public BrowserPermissionMode getBrowserPermissionMode() {
        return browserPermissionMode;
    }

    public Map<ToolCategory, PermissionPolicy> getCategoryPolicies() {
        return categoryPolicies;
    }

    public static final class Builder {
        private PermissionPolicy policy = PermissionPolicy.READONLY;
        private final List<PermissionRule> rules = new java.util.ArrayList<>();
        private WriteConfirmation writeConfirmation = WriteConfirmation.CONFIRM;
        private BrowserPermissionMode browserPermissionMode = BrowserPermissionMode.ASK;
        private final Map<ToolCategory, PermissionPolicy> categoryPolicies = new EnumMap<>(ToolCategory.class);

        public Builder policy(PermissionPolicy value) {
            this.policy = Objects.requireNonNull(value, "policy");
            return this;
        }

        public Builder rules(List<PermissionRule> values) {
            this.rules.clear();
            if (values != null) {
                values.stream().filter(Objects::nonNull).forEach(this.rules::add);
            }
            return this;
        }

        public Builder addRule(PermissionRule rule) {
            if (rule != null) {
                this.rules.add(rule);
            }
            return this;
        }

        public Builder writeConfirmation(WriteConfirmation value) {
            this.writeConfirmation = Objects.requireNonNull(value, "writeConfirmation");
            return this;
        }

        public Builder browserPermissionMode(BrowserPermissionMode value) {
            this.browserPermissionMode = Objects.requireNonNull(value, "browserPermissionMode");
            return this;
        }

        public Builder categoryPolicy(ToolCategory category, PermissionPolicy value) {
            this.categoryPolicies.put(Objects.requireNonNull(category, "category"),
                    Objects.requireNonNull(value, "policy"));
            return this;
        }

        public PermissionSettings build() {
            return new PermissionSettings(this);
        }
    }
}