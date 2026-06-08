package org.ai.agent.ddbknowledge.audit;

public class AuditContextHolder {
    private static final ThreadLocal<AuditContext> CONTEXT = new ThreadLocal<>();

    public static void set(AuditContext context) {
        CONTEXT.set(context);
    }

    public static AuditContext get() {
        return CONTEXT.get();
    }

    public static void clear() {
        CONTEXT.remove();
    }
    
    public static void updateCapturedPrompt(String prompt) {
        AuditContext current = CONTEXT.get();
        if (current != null) {
            current.setCapturedPrompt(prompt);
        }
    }
}
