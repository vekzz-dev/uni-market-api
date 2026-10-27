-- ============================================================================
-- V4 — Procedencia del rechazo en anuncios (R5, issue #4).
-- Una aprobación revocada y un rechazo de revisión comparten estado_revision
-- 'rechazado'; origen_rechazo conserva cuál de los dos fue, sin tocar el enum
-- de estado que ya consumen los clientes.
-- Forward-only: una vez aplicada, esta migración no se edita (DBDD §6).
-- ============================================================================

ALTER TABLE anuncios ADD COLUMN origen_rechazo varchar(20);

-- Backfill: toda fila ya rechazada viene de un rechazo de revisión; no existe
-- historial previo (el PRD §7 conserva solo la última revisión).
UPDATE anuncios SET origen_rechazo = 'rechazo' WHERE estado_revision = 'rechazado';

-- Dominio cerrado de la procedencia (DBDD §3.2)
ALTER TABLE anuncios ADD CONSTRAINT anuncios_origen_rechazo_check
    CHECK (origen_rechazo IS NULL OR origen_rechazo IN ('rechazo', 'retraccion'));

-- Toda fila rechazada tiene procedencia y ninguna otra la tiene: espejo de
-- chk_revision_trail_consistente para que el estado y su origen no se desalineen.
ALTER TABLE anuncios ADD CONSTRAINT chk_origen_rechazo_consistente
    CHECK ((estado_revision = 'rechazado') = (origen_rechazo IS NOT NULL));
