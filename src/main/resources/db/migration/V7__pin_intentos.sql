-- ============================================================================
-- V7 — Intentos fallidos en los códigos de un solo uso (issue #8, R11).
-- La confirmación y el reset pasan de token-enlace a PIN de 6 dígitos: con 10^6
-- combinaciones, la vigencia corta y un contador de intentos son obligatorios.
-- Al agotar los intentos el código se invalida y hay que pedir otro.
-- Forward-only: una vez aplicada, esta migración no se edita (DBDD §6).
-- ============================================================================

ALTER TABLE tokens_verificacion_email ADD COLUMN intentos integer NOT NULL DEFAULT 0;
ALTER TABLE tokens_restablecimiento_contrasena ADD COLUMN intentos integer NOT NULL DEFAULT 0;

ALTER TABLE tokens_verificacion_email ADD CONSTRAINT chk_intentos_verificacion_no_negativos
    CHECK (intentos >= 0);
ALTER TABLE tokens_restablecimiento_contrasena ADD CONSTRAINT chk_intentos_reset_no_negativos
    CHECK (intentos >= 0);
