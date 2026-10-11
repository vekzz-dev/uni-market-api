-- ============================================================================
-- V2 — Seed de las 6 categorías base (DBDD §4).
-- Idempotente: re-ejecutar esta migración no duplica filas porque el
-- ON CONFLICT usa categorias_nombre_key y no toca las existentes.
-- ============================================================================

INSERT INTO categorias (nombre, icono, orden)
VALUES
    ('Electrónica', '💻', 1),
    ('Libros', '📚', 2),
    ('Ropa', '👕', 3),
    ('Deportes', '⚽', 4),
    ('Hogar', '🏠', 5),
    ('Otros', '📦', 6)
ON CONFLICT (nombre) DO NOTHING;
