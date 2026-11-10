-- ============================================================================
-- V5 — Índices trigram para la búsqueda de usuarios del panel admin (issue #7).
-- `q` busca por nombre y email con `lower(columna) LIKE '%término%'`, un patrón
-- que ningún btree puede servir: sin estos GIN la consulta es un seq scan sobre
-- `usuarios`. La extensión `pg_trgm` la habilita el baseline (V1).
-- Forward-only: una vez aplicada, esta migración no se edita (DBDD §6).
-- ============================================================================

-- Funcionales: el índice debe declarar la MISMA expresión que la consulta.
CREATE INDEX idx_usuarios_nombre_trgm ON usuarios USING gin (lower(nombre) gin_trgm_ops);
CREATE INDEX idx_usuarios_email_trgm ON usuarios USING gin (lower(email) gin_trgm_ops);
