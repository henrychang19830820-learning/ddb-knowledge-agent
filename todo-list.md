# Audit System Future Enhancements

- [ ] **Listener-Driven (Fully Automatic) Auditing**: Move all `AuditService.recordAudit` calls directly into the `ChatModelListener`. This will ensure that *every* internal reasoning step and intermediate model call is logged as a separate row in the database, without any manual code in the services.
