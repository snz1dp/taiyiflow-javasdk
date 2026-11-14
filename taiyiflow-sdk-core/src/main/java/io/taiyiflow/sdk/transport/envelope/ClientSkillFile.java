package io.taiyiflow.sdk.transport.envelope;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ClientSkillFile(@JsonProperty("path") String path,
                              @JsonProperty("source") String source,
                              @JsonProperty("description") String description) {
}
