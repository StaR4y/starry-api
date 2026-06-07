package xyz.star4y.api.proxy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import org.springframework.stereotype.Service;

@Service
public class ProxyModelService {

    private final ObjectMapper objectMapper;
    private final long startedAt = Instant.now().toEpochMilli();

    public ProxyModelService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ObjectNode health() {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("status", "ok");
        root.put("service", "starryapi-service");
        root.put("uptime_ms", Instant.now().toEpochMilli() - startedAt);
        return root;
    }
}
