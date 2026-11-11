package io.taiyiflow.sdk.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.file.Path;

public final class WorkerConfigLoader {
    private final ObjectMapper mapper;

    public WorkerConfigLoader() {
        this.mapper = new ObjectMapper(new YAMLFactory())
                .registerModule(new JavaTimeModule())
                .setPropertyNamingStrategy(PropertyNamingStrategies.KEBAB_CASE);
    }

    public WorkerConfig load(Path path) throws IOException {
        return mapper.readValue(path.toFile(), WorkerConfig.class);
    }
}
