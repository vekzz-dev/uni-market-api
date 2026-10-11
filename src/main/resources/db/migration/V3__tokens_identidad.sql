-- ============================================================================
-- V3 — Tablas de tokens de identidad (DBDD §3 columnas, §3.1 índices, §2 FKs).
-- Tres tablas de un solo propósito; el valor crudo del token nunca se persiste:
-- se guarda el hash SHA-256 y la búsqueda es por hash del valor presentado.
-- Forward-only: una vez aplicada, esta migración no se edita (DBDD §6).
-- ============================================================================

-- ----------------------------------------------------------------------------
-- Tabla: tokens_verificacion_email — confirmación de email (TTL 24 h).
-- ----------------------------------------------------------------------------
CREATE TABLE tokens_verificacion_email (
    id               uuid        NOT NULL DEFAULT gen_random_uuid(),
    usuario_id       uuid        NOT NULL,
    hash_token       text        NOT NULL,
    fecha_expiracion timestamptz NOT NULL,
    fecha_uso        timestamptz,
    fecha_creacion   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT tokens_verificacion_email_pkey PRIMARY KEY (id),
    CONSTRAINT tokens_verificacion_email_hash_token_key UNIQUE (hash_token),
    -- NO ACTION explícito: el flujo soportado de baja es soft-delete (UPDATE),
    -- que no dispara ON DELETE (DBDD §2)
    CONSTRAINT tokens_verificacion_email_usuario_id_fkey FOREIGN KEY (usuario_id)
        REFERENCES usuarios (id) ON DELETE NO ACTION
);

-- ----------------------------------------------------------------------------
-- Tabla: tokens_restablecimiento_contrasena — reset de contraseña (TTL 60 min).
-- ----------------------------------------------------------------------------
CREATE TABLE tokens_restablecimiento_contrasena (
    id               uuid        NOT NULL DEFAULT gen_random_uuid(),
    usuario_id       uuid        NOT NULL,
    hash_token       text        NOT NULL,
    fecha_expiracion timestamptz NOT NULL,
    fecha_uso        timestamptz,
    fecha_creacion   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT tokens_restablecimiento_contrasena_pkey PRIMARY KEY (id),
    CONSTRAINT tokens_restablecimiento_contrasena_hash_token_key UNIQUE (hash_token),
    CONSTRAINT tokens_restablecimiento_contrasena_usuario_id_fkey FOREIGN KEY (usuario_id)
        REFERENCES usuarios (id) ON DELETE NO ACTION
);

-- ----------------------------------------------------------------------------
-- Tabla: tokens_refresh — rotación de sesión (TTL 30 días). Columnas propias:
-- familia_id (agrupa la familia de rotación; el reuso de un token rotado revoca
-- la familia entera) y fecha_revocacion (NULL = sin revocar).
-- ----------------------------------------------------------------------------
CREATE TABLE tokens_refresh (
    id               uuid        NOT NULL DEFAULT gen_random_uuid(),
    usuario_id       uuid        NOT NULL,
    hash_token       text        NOT NULL,
    fecha_expiracion timestamptz NOT NULL,
    fecha_uso        timestamptz,
    fecha_creacion   timestamptz NOT NULL DEFAULT now(),
    familia_id       uuid        NOT NULL,
    fecha_revocacion timestamptz,
    CONSTRAINT tokens_refresh_pkey PRIMARY KEY (id),
    CONSTRAINT tokens_refresh_hash_token_key UNIQUE (hash_token),
    CONSTRAINT tokens_refresh_usuario_id_fkey FOREIGN KEY (usuario_id)
        REFERENCES usuarios (id) ON DELETE NO ACTION
);

-- ----------------------------------------------------------------------------
-- Índices parciales de tokens activos (DBDD §3.1): a lo sumo un token activo
-- (fecha_uso IS NULL) por usuario y propósito. Respaldan la invalidación de
-- tokens previos al reenviar un email (R11) y la rotación/revocación de refresh (R12).
-- ----------------------------------------------------------------------------
CREATE UNIQUE INDEX ux_tokens_email_activos ON tokens_verificacion_email (usuario_id) WHERE fecha_uso IS NULL;
CREATE UNIQUE INDEX ux_tokens_reset_activos ON tokens_restablecimiento_contrasena (usuario_id) WHERE fecha_uso IS NULL;
CREATE UNIQUE INDEX ux_tokens_refresh_activos ON tokens_refresh (usuario_id) WHERE fecha_uso IS NULL;
