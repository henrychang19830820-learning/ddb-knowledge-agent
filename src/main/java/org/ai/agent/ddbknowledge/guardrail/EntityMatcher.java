package org.ai.agent.ddbknowledge.guardrail;

import org.springframework.stereotype.Component;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class EntityMatcher {
    private static final Pattern KEYWORD_PATTERN = Pattern.compile(
        "\\b(GSI|LSI|Global Secondary Index|Local Secondary Index|TTL|Time To Live|DAX|Streams|PITR|Global Tables|RCU|WCU|On-Demand|Provisioned|PartiQL|BatchWriteItem|BatchGetItem|PutItem|GetItem|UpdateItem|DeleteItem|Query|Scan|CreateTable|UpdateTable|DeleteTable|DescribeTable|Transactions|TransactWriteItems|TransactGetItems|IAM|Resource-based policy|ConditionExpression|FilterExpression|ProjectionExpression|Partition Key|Sort Key|Hash Key|Range Key|LSN|SequenceNumber)\\b",
        Pattern.CASE_INSENSITIVE
    );

    public Set<String> extractEntities(String text) {
        Set<String> entities = new HashSet<>();
        if (text == null) return entities;
        Matcher matcher = KEYWORD_PATTERN.matcher(text);
        while (matcher.find()) {
            entities.add(matcher.group().toUpperCase());
        }
        return entities;
    }
}
