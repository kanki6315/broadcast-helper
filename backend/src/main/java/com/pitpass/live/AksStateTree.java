package com.pitpass.live;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The client half of the protocol's "difference only" contract: the server
 * sends a full snapshot on JOIN and thereafter only what changed, so the
 * complete picture exists nowhere but here. A null — field or whole channel —
 * means delete. No single message is meaningful on its own.
 */
public final class AksStateTree {

    private final ObjectNode root = JsonNodeFactory.instance.objectNode();

    public synchronized void merge(ObjectNode diff) {
        merge(root, diff);
    }

    /** A new connection starts from a fresh snapshot; nothing carries over. */
    public synchronized void clear() {
        root.removeAll();
    }

    /**
     * A detached copy of the node at a dotted path ("timing.session.info"), or
     * null when absent. Copied so callers can serialise it while the reader
     * thread keeps merging.
     */
    public synchronized JsonNode copyOf(String dottedPath) {
        JsonNode node = root;
        if (dottedPath != null && !dottedPath.isBlank()) {
            for (String key : dottedPath.split("\\.")) {
                node = node.get(key);
                if (node == null) {
                    return null;
                }
            }
        }
        return node.deepCopy();
    }

    private static void merge(ObjectNode target, ObjectNode diff) {
        for (var field : diff.properties()) {
            String key = field.getKey();
            JsonNode value = field.getValue();
            if (value == null || value.isNull()) {
                target.remove(key);
            } else if (value instanceof ObjectNode child) {
                // Merge even into a fresh node, so a null nested inside a
                // newly created channel is dropped rather than stored.
                ObjectNode existing = target.get(key) instanceof ObjectNode o ? o : target.putObject(key);
                merge(existing, child);
            } else {
                target.set(key, value);
            }
        }
    }
}
