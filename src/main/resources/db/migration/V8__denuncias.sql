-- ============================================================================
-- V8 — Denuncias de anuncios (issue #9).
-- Una denuncia INFORMA, no actúa: no oculta ni retrae el anuncio. La decisión
-- sigue siendo de un moderador identificado (R5). Una denuncia por persona y
-- anuncio: la UNIQUE impide inflar el contador con una sola cuenta.
-- Forward-only: una vez aplicada, esta migración no se edita (DBDD §6).
-- ============================================================================

CREATE TABLE denuncias (
    id                uuid        NOT NULL DEFAULT gen_random_uuid(),
    anuncio_id        uuid        NOT NULL,
    denunciante_id    uuid        NOT NULL,
    motivo            varchar(30) NOT NULL,
    detalle           text,
    estado            varchar(20) NOT NULL DEFAULT 'abierta',
    fecha_creacion    timestamptz NOT NULL DEFAULT now(),
    resuelto_por      uuid,
    fecha_resolucion  timestamptz,
    CONSTRAINT denuncias_pkey PRIMARY KEY (id),
    -- Una denuncia por persona y anuncio: el segundo intento es 409
    CONSTRAINT denuncias_anuncio_id_denunciante_id_key UNIQUE (anuncio_id, denunciante_id),
    CONSTRAINT denuncias_motivo_check CHECK (
        motivo IN ('prohibido', 'falsificacion', 'estafa', 'spam', 'inadecuado', 'otro')
    ),
    CONSTRAINT denuncias_estado_check CHECK (estado IN ('abierta', 'revisada', 'descartada')),
    -- 'otro' exige detalle no vacío; el resto puede llevarlo o no (R19)
    CONSTRAINT chk_detalle_obligatorio_en_otro CHECK (
        motivo <> 'otro' OR (detalle IS NOT NULL AND btrim(detalle) <> '')
    ),
    -- Trail de resolución consistente: abierta ⇒ sin revisor; resuelta ⇒ con auditoría
    CONSTRAINT chk_denuncia_resolucion_consistente CHECK (
        (estado = 'abierta' AND resuelto_por IS NULL AND fecha_resolucion IS NULL)
        OR (estado <> 'abierta' AND resuelto_por IS NOT NULL AND fecha_resolucion IS NOT NULL)
    ),
    CONSTRAINT denuncias_anuncio_id_fkey FOREIGN KEY (anuncio_id)
        REFERENCES anuncios (id) ON DELETE CASCADE,
    -- NO ACTION: una denuncia es evidencia de moderación y no debe desaparecer
    CONSTRAINT denuncias_denunciante_id_fkey FOREIGN KEY (denunciante_id)
        REFERENCES usuarios (id) ON DELETE NO ACTION,
    CONSTRAINT denuncias_resuelto_por_fkey FOREIGN KEY (resuelto_por)
        REFERENCES usuarios (id) ON DELETE NO ACTION
);

-- Cola de casos por anuncio y conteo de abiertas por anuncio
CREATE INDEX idx_denuncias_anuncio_estado ON denuncias (anuncio_id, estado);
-- Cola FIFO estable: (estado, fecha_creacion, id) como el resto de colas
CREATE INDEX idx_denuncias_estado_creacion ON denuncias (estado, fecha_creacion, id);
