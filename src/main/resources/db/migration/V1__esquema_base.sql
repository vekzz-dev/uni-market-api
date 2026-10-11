-- ============================================================================
-- V1 — Esquema base de Uni Market (DBDD §3 columnas, §3.1 índices, §3.2 CHECKs, §2 FKs)
-- 5 tablas de negocio: usuarios, categorias, anuncios, conversaciones, mensajes.
-- Forward-only: una vez aplicada, esta migración no se edita (DBDD §6).
-- ============================================================================

-- Búsqueda difusa por trigrama (habilita idx_anuncios_nombre_trgm e
-- idx_anuncios_descripcion_trgm). gen_random_uuid() es nativo de PostgreSQL 18:
-- no requiere la extensión uuid-ossp (DBDD §1).
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- ----------------------------------------------------------------------------
-- Tabla: usuarios — identidad única (perfil, credenciales y rol; sin tabla de perfil).
-- Soft-delete selectivo: la baja escribe fecha_eliminacion, nunca DELETE físico.
-- ----------------------------------------------------------------------------
CREATE TABLE usuarios (
    id                      uuid         NOT NULL DEFAULT gen_random_uuid(),
    email                   varchar(255) NOT NULL,
    nombre                  varchar(255) NOT NULL,
    url_avatar              text,
    hash_contrasena         text         NOT NULL,
    fecha_verificacion_email timestamptz,
    habilitado              boolean      NOT NULL DEFAULT true,
    fecha_eliminacion       timestamptz,
    rol                     varchar(20)  NOT NULL DEFAULT 'miembro',
    fecha_creacion          timestamptz  NOT NULL DEFAULT now(),
    fecha_actualizacion     timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT usuarios_pkey PRIMARY KEY (id),
    -- Nombre visible no vacío ni solo espacios (DBDD §3.2)
    CONSTRAINT chk_nombre_no_vacio CHECK (btrim(nombre) <> ''),
    -- Dominio cerrado del rol (DBDD §3.2)
    CONSTRAINT usuarios_rol_check CHECK (rol IN ('miembro', 'moderador', 'admin'))
);

-- ----------------------------------------------------------------------------
-- Tabla: categorias — catálogo de categorías del marketplace (única PK no-UUID).
-- ----------------------------------------------------------------------------
CREATE TABLE categorias (
    id                  serial       NOT NULL,
    nombre              varchar(100) NOT NULL,
    descripcion         text,
    icono               varchar(50),
    orden               int          NOT NULL DEFAULT 0,
    fecha_creacion      timestamptz  NOT NULL DEFAULT now(),
    fecha_actualizacion timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT categorias_pkey PRIMARY KEY (id),
    -- Nombre único; el seed idempotente de V2 usa este constraint como destino
    -- del ON CONFLICT (nombre) (DBDD §4, §6)
    CONSTRAINT categorias_nombre_key UNIQUE (nombre),
    -- Emoji o ícono de máximo 10 caracteres; la columna deja margen a 50 (DBDD §3.2)
    CONSTRAINT chk_icono_longitud CHECK (char_length(icono) <= 10)
);

-- ----------------------------------------------------------------------------
-- Tabla: anuncios — anuncio publicable por un vendedor. La baja soportada es
-- estado='eliminado' (no borrado físico); estado_revision audita la moderación.
-- ----------------------------------------------------------------------------
CREATE TABLE anuncios (
    id                  uuid         NOT NULL DEFAULT gen_random_uuid(),
    nombre              varchar(255) NOT NULL,
    precio              numeric(10, 2) NOT NULL,
    descripcion         text,
    imagenes_urls       jsonb        NOT NULL DEFAULT '[]'::jsonb,
    imagenes_paths      jsonb        NOT NULL DEFAULT '[]'::jsonb,
    vendedor_id         uuid         NOT NULL,
    categoria_id        int,
    estado              varchar(20)  NOT NULL DEFAULT 'disponible',
    estado_revision     varchar(20)  NOT NULL DEFAULT 'pendiente',
    revisado_por        uuid,
    fecha_revision      timestamptz,
    motivo_rechazo      text,
    fecha_creacion      timestamptz  NOT NULL DEFAULT now(),
    fecha_actualizacion timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT anuncios_pkey PRIMARY KEY (id),
    CONSTRAINT chk_nombre_no_vacio CHECK (btrim(nombre) <> ''),
    -- Rango del precio informativo (DBDD §3.2)
    CONSTRAINT chk_precio_rango CHECK (precio >= 0 AND precio <= 99999999.99),
    -- Máximo 5 imágenes y arrays acoplados por posición (DBDD §3.2)
    CONSTRAINT chk_max_imagenes CHECK (jsonb_array_length(imagenes_urls) <= 5),
    CONSTRAINT chk_imagenes_pareadas CHECK (jsonb_array_length(imagenes_urls) = jsonb_array_length(imagenes_paths)),
    -- Dominios cerrados de disponibilidad y moderación (DBDD §3.2)
    CONSTRAINT anuncios_estado_check CHECK (estado IN ('disponible', 'inactivo', 'vendido', 'eliminado')),
    CONSTRAINT anuncios_estado_revision_check CHECK (estado_revision IN ('pendiente', 'aprobado', 'rechazado')),
    -- Trail de moderación consistente: pendiente ⇒ sin trail; revisado ⇒ revisado_por
    -- y fecha_revision poblados (DBDD §3.2)
    CONSTRAINT chk_revision_trail_consistente CHECK (
        (estado_revision = 'pendiente' AND revisado_por IS NULL AND fecha_revision IS NULL AND motivo_rechazo IS NULL)
        OR (estado_revision <> 'pendiente' AND revisado_por IS NOT NULL AND fecha_revision IS NOT NULL)
    ),
    -- Todo rechazo/retracción exige motivo no vacío (DBDD §3.2)
    CONSTRAINT chk_motivo_rechazo_obligatorio CHECK (
        estado_revision <> 'rechazado' OR (motivo_rechazo IS NOT NULL AND btrim(motivo_rechazo) <> '')
    ),
    -- Ownership: borrar la cuenta destruiría sus anuncios; la baja soportada es
    -- soft-delete, por lo que este CASCADE no se ejecuta en el flujo normal (DBDD §2, §5)
    CONSTRAINT anuncios_vendedor_id_fkey FOREIGN KEY (vendedor_id)
        REFERENCES usuarios (id) ON DELETE CASCADE,
    -- Al borrarse la categoría el anuncio queda con categoria_id NULL y sigue visible (DBDD §2)
    CONSTRAINT anuncios_categoria_id_fkey FOREIGN KEY (categoria_id)
        REFERENCES categorias (id) ON DELETE SET NULL,
    -- NO ACTION: protege la auditoría de moderación ante un DELETE físico del revisor (DBDD §2)
    CONSTRAINT anuncios_revisado_por_fkey FOREIGN KEY (revisado_por)
        REFERENCES usuarios (id) ON DELETE NO ACTION
);

-- ----------------------------------------------------------------------------
-- Tabla: conversaciones — hilo de chat por anuncio entre comprador y vendedor.
-- Una conversación por par (anuncio, comprador); sin auto-conversación.
-- ----------------------------------------------------------------------------
CREATE TABLE conversaciones (
    id                  uuid        NOT NULL DEFAULT gen_random_uuid(),
    anuncio_id          uuid        NOT NULL,
    comprador_id        uuid        NOT NULL,
    vendedor_id         uuid        NOT NULL,
    fecha_creacion      timestamptz NOT NULL DEFAULT now(),
    fecha_actualizacion timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT conversaciones_pkey PRIMARY KEY (id),
    -- Una conversación por par comprador–anuncio (DBDD §3, §5)
    CONSTRAINT conversaciones_anuncio_id_comprador_id_key UNIQUE (anuncio_id, comprador_id),
    -- Sin auto-conversación: comprador y vendedor son usuarios distintos (DBDD §3.2)
    CONSTRAINT chk_conversacion_no_propia CHECK (comprador_id <> vendedor_id),
    CONSTRAINT conversaciones_anuncio_id_fkey FOREIGN KEY (anuncio_id)
        REFERENCES anuncios (id) ON DELETE CASCADE,
    CONSTRAINT conversaciones_comprador_id_fkey FOREIGN KEY (comprador_id)
        REFERENCES usuarios (id) ON DELETE CASCADE,
    CONSTRAINT conversaciones_vendedor_id_fkey FOREIGN KEY (vendedor_id)
        REFERENCES usuarios (id) ON DELETE CASCADE
);

-- ----------------------------------------------------------------------------
-- Tabla: mensajes — historial inmutable del chat (sin fecha_actualizacion).
-- remitente_id debe ser participante de la conversación (invariante de aplicación).
-- ----------------------------------------------------------------------------
CREATE TABLE mensajes (
    id              uuid        NOT NULL DEFAULT gen_random_uuid(),
    conversacion_id uuid        NOT NULL,
    remitente_id    uuid        NOT NULL,
    texto           text        NOT NULL,
    leido           boolean     NOT NULL DEFAULT false,
    fecha_creacion  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT mensajes_pkey PRIMARY KEY (id),
    -- Contenido recortado y no vacío; el mensaje no se edita ni se borra (DBDD §3.2)
    CONSTRAINT chk_mensaje_no_vacio CHECK (btrim(texto) <> ''),
    CONSTRAINT mensajes_conversacion_id_fkey FOREIGN KEY (conversacion_id)
        REFERENCES conversaciones (id) ON DELETE CASCADE,
    CONSTRAINT mensajes_remitente_id_fkey FOREIGN KEY (remitente_id)
        REFERENCES usuarios (id) ON DELETE CASCADE
);

-- ----------------------------------------------------------------------------
-- Índices de negocio (DBDD §3.1): 13 de los 16 del esquema; los 3 parciales de
-- tokens activos viven en V3.
-- No se declaran idx_mensajes_conversacion ni idx_anuncios_estado: quedan
-- cubiertos por el prefijo izquierdo de los índices compuestos.
-- ----------------------------------------------------------------------------

-- "Mis anuncios" del vendedor
CREATE INDEX idx_anuncios_vendedor ON anuncios (vendedor_id);
-- Filtro de catálogo por categoría
CREATE INDEX idx_anuncios_categoria ON anuncios (categoria_id);
-- Moderación: listar pendientes
CREATE INDEX idx_anuncios_estado_revision ON anuncios (estado_revision);
-- Catálogo público con ORDER BY fecha_creacion DESC: el id final desempata
-- timestamps iguales (cursor compuesto)
CREATE INDEX idx_anuncios_publicacion ON anuncios (estado, estado_revision, fecha_creacion DESC, id DESC);
-- Búsqueda ILIKE '%término%' sobre el título
CREATE INDEX idx_anuncios_nombre_trgm ON anuncios USING gin (nombre gin_trgm_ops);
-- Búsqueda ILIKE '%término%' sobre la descripción
CREATE INDEX idx_anuncios_descripcion_trgm ON anuncios USING gin (descripcion gin_trgm_ops);
-- "Mis conversaciones" como comprador, ordenadas por actividad reciente
CREATE INDEX idx_conversaciones_comprador ON conversaciones (comprador_id, fecha_actualizacion DESC);
-- "Mis conversaciones" como vendedor, ordenadas por actividad reciente
CREATE INDEX idx_conversaciones_vendedor ON conversaciones (vendedor_id, fecha_actualizacion DESC);
-- Historial de una conversación ordenado; el id final desempata timestamps
-- iguales (cursor compuesto)
CREATE INDEX idx_mensajes_conversacion_creado ON mensajes (conversacion_id, fecha_creacion, id);
-- Conteo de no leídos
CREATE INDEX idx_mensajes_no_leidos ON mensajes (conversacion_id, leido);
-- Panel admin: usuarios por rol (solo activos)
CREATE INDEX idx_usuarios_rol ON usuarios (rol) WHERE fecha_eliminacion IS NULL;
-- Unicidad case-insensitive entre cuentas activas: A@x.com y a@x.com son la
-- misma cuenta; el email queda libre al darse de baja (soft-delete)
CREATE UNIQUE INDEX ux_usuarios_email_activo ON usuarios (lower(email)) WHERE fecha_eliminacion IS NULL;
-- Reordenamiento de categorías
CREATE INDEX idx_categorias_orden ON categorias (orden);
