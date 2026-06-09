package org.ai.agent.ddbknowledge.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ai.agent.ddbknowledge.guardrail.EntityMatcher;
import org.ai.agent.ddbknowledge.guardrail.EntityVerifier;
import org.springframework.stereotype.Service;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class EntityGuardrailService {
    private final EntityMatcher matcher;
    private final EntityVerifier verifier;

    public boolean isValid(String newQuery, String cachedQuery, String cachedEntitiesStr) {
        Set<String> newEntities = matcher.extractEntities(newQuery);
        Set<String> cachedEntities = matcher.extractEntities(cachedEntitiesStr != null ? cachedEntitiesStr : "");

        // Tier 1: Regex Match
        if (newEntities.equals(cachedEntities)) {
            log.info("Guardrail: Exact entity match found: {}", newEntities);
            return true;
        }

        // Tier 2: LLM Fallback
        log.info("Guardrail: Entity mismatch ({} vs {}), falling back to LLM", newEntities, cachedEntities);
        boolean equivalent = verifier.areEquivalent(newQuery, cachedQuery);
        log.info("Guardrail: LLM verification result: {}", equivalent ? "EQUIVALENT" : "DIFFERENT");
        return equivalent;
    }
}
