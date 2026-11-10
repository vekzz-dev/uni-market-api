-- ============================================================================
-- V6 — Los índices trigram del catálogo pasan a ser FUNCIONALES (issue #7, hallazgo).
-- La búsqueda del catálogo consulta `lower(nombre) LIKE '%término%'` y
-- `lower(descripcion) LIKE ...`, pero los índices de V1 estaban sobre las columnas
-- desnudas: Postgres solo usa un índice cuya expresión coincide con la consulta, así
-- que esos dos GIN nunca se usaron (seq scan medido). Se recrean con `lower(...)`
-- manteniendo el mismo nombre; DDL es transaccional en PostgreSQL, no hay estado
-- intermedio visible.
-- Forward-only: una vez aplicada, esta migración no se edita (DBDD §6).
-- ============================================================================

DROP INDEX idx_anuncios_nombre_trgm;
DROP INDEX idx_anuncios_descripcion_trgm;

CREATE INDEX idx_anuncios_nombre_trgm ON anuncios USING gin (lower(nombre) gin_trgm_ops);
CREATE INDEX idx_anuncios_descripcion_trgm ON anuncios USING gin (lower(descripcion) gin_trgm_ops);
