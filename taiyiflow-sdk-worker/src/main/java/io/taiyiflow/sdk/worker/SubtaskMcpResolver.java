package io.taiyiflow.sdk.worker;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Set;

@FunctionalInterface
public interface SubtaskMcpResolver {
    ObjectNode resolve(String flowId, Set<String> serverNames) throws Exception;
}
