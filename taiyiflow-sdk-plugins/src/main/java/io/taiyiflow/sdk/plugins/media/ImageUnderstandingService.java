package io.taiyiflow.sdk.plugins.media;

import com.fasterxml.jackson.databind.JsonNode;
import io.taiyiflow.sdk.core.dispatch.ToolExecutionContext;
import io.taiyiflow.sdk.transport.rest.RestClient;

public final class ImageUnderstandingService implements AutoCloseable {
    private final MediaUnderstandingPlugin media;

    public ImageUnderstandingService(RestClient restClient) {
        this.media = new MediaUnderstandingPlugin(restClient);
    }

    public String understandScreenshot(ToolExecutionContext context, JsonNode arguments) throws Exception {
        return media.understandScreenshot(context, arguments);
    }

    @Override
    public void close() {
        media.close();
    }
}
