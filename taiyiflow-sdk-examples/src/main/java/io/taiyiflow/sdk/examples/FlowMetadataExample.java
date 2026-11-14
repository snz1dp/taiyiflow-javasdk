package io.taiyiflow.sdk.examples;

import io.taiyiflow.sdk.TaiyiAgent;
import io.taiyiflow.sdk.transport.envelope.FlowMetadata;

public final class FlowMetadataExample {
    public static void main(String[] args) throws Exception {
        TaiyiAgent agent = TaiyiAgent.builder().baseUrl(env("TAIYIFLOW_BASE_URL"))
                .apiKey(env("TAIYIFLOW_API_KEY")).flowId(env("TAIYIFLOW_FLOW_ID")).build();
        try {
            FlowMetadata metadata = agent.getFlowMetadata();
            System.out.println("Flow: " + metadata.getName() + " (" + metadata.getId() + ")");
            System.out.println("Owner: " + metadata.getOwnerAppId());
            System.out.println("Features: " + metadata.getFeatures());
            System.out.println("Knowledges: " + metadata.getKnowledges().stream().map(FlowMetadata.Knowledge::name).toList());
        } finally {
            agent.shutdown();
        }
    }

    static String env(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing environment variable: " + name);
        return value;
    }
}
