-- ============================================================================
-- V9 — Motivo de la resolución de una denuncia (R19, issue #9).
-- El contrato ya aceptaba `motivo` al resolver y el servicio lo descartaba:
-- entrada del usuario perdida en silencio. Se persiste para que el cierre de un
-- caso quede justificado ("descartada: duplicado"), no solo auditado por quién.
-- Forward-only: una vez aplicada, esta migración no se edita (DBDD §6).
-- ============================================================================

ALTER TABLE denuncias ADD COLUMN motivo_resolucion text;

-- Una denuncia abierta no tiene justificación de cierre; una resuelta puede tenerla
ALTER TABLE denuncias ADD CONSTRAINT chk_motivo_resolucion_solo_resuelta CHECK (
    estado <> 'abierta' OR motivo_resolucion IS NULL
);
