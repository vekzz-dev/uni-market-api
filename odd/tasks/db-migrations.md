# Feature: db-migrations

| Campo | Valor |
|---|---|
| Estado | En progreso |
| Creado | 2026-10-10 |
| Alcance | Migraciones Flyway V1–V3 del DBDD contra PostgreSQL 18 real (Testcontainers) — sin repositorios ni entidades JPA |
| Presupuesto (heurística ~400 líneas) | ~300 líneas (SQL + test); cabe en single-pr |
| Estrategia de entrega | single-pr |
| Documentos | `docs/database-design.md` (§2 FKs, §3 columnas, §3.1 índices, §3.2 CHECKs, §6 migraciones/verificación), `docs/system-design.md` (§7, D14 irrelevante aquí) |

## Objetivo

Materializar el esquema del DBDD como migraciones versionadas forward-only, aplicadas automáticamente por Flyway en el arranque, y verificadas por un test de integración contra **PostgreSQL 18 real** (Testcontainers; el PRD §6 prohíbe sustitutos en memoria).

## Contenido de las migraciones (transcribir del DBDD, no reinventar)

- `V1__esquema_base.sql`: extensión `pg_trgm`; 5 tablas de negocio (`usuarios`, `categorias`, `anuncios`, `conversaciones`, `mensajes`) con columnas/tipos/defaults exactos del §3; los **13 CHECKs** del §3.2 (incluidos `chk_revision_trail_consistente`, `chk_motivo_rechazo_obligatorio`, `chk_icono_longitud`); UNIQUEs (email activo parcial con `lower(email)`, nombre de categoría, par conversación); **8 FKs** con su `ON DELETE` (§2: CASCADE/SET NULL/NO ACTION); **13 índices de negocio** del §3.1 (incluye `id` de desempate en `idx_anuncios_publicacion` e `idx_mensajes_conversacion_creado`; compuestos de participantes con `fecha_actualizacion DESC`).
- `V2__datos_categorias.sql`: seed idempotente (`INSERT ... ON CONFLICT (nombre) DO NOTHING`) de las 6 categorías con sus emojis y `orden` 1–6 del §4.
- `V3__tokens_identidad.sql`: 3 tablas de tokens (§3) + `hash_token` UNIQUE + **3 índices parciales** `ux_tokens_*_activos` (`usuario_id WHERE fecha_uso IS NULL`) + FKs `NO ACTION` explícitas. `familia_id`/`fecha_revocacion` solo en `tokens_refresh`.

## Tareas

- [x] **T1 — V1**: esquema base. Verificación: aplica en limpio sin errores. **Evidencia (2026-10-10)**: `V1__esquema_base.sql` (5 tablas, 13 CHECKs, 8 FKs, 13 índices de §3.1, `pg_trgm`) aplicada por Flyway en PostgreSQL 18.6 real; sin ediciones posteriores al primer apply (forward-only).
- [x] **T2 — V2**: seed. Verificación: idempotente (re-ejecutar el INSERT no duplica). **Evidencia**: `DbSchemaVerificationTest.SemillaDeCategorias` — 6 categorías con `orden` 1–6 y nombres/emojis de §4; re-ejecución del INSERT de V2 vía `JdbcTemplate` → sigue en 6.
- [x] **T3 — V3**: tokens. Verificación: aplica en orden V1→V2→V3. **Evidencia**: `flyway_schema_history` con V1, V2 y V3 en orden y `success=true`; 3 tablas de tokens, `hash_token` UNIQUE en cada una, `familia_id`/`fecha_revocacion` solo en `tokens_refresh` y 3 índices parciales `ux_tokens_*_activos`.
- [x] **T4 — Test de verificación** (`DbSchemaVerificationTest`, integración con Testcontainers + Flyway del arranque de Spring):
  - 8 tablas en `information_schema` (schema `public`).
  - Los **16 índices nombrados** existen en `pg_indexes` (13 de V1 + 3 de V3) — se verifican por nombre, no por conteo total (pg_indexes incluye los índices de PK/UNIQUE de constraints, que no forman parte de los 16).
  - Los **13 CHECKs nombrados** existen en `pg_constraint` (`contype='c'`).
  - `pg_policies` vacío (sin RLS).
  - Seed: 6 categorías, `orden` 1–6, y re-ejecución del INSERT → sigue en 6.
  - Comportamientos FK: `confdeltype` de `anuncios_vendedor_id_fkey`='c' (CASCADE), `anuncios_categoria_id_fkey`='n' (SET NULL), `anuncios_revisado_por_fkey`='a' (NO ACTION), FK de tokens='a'.
  **Evidencia (2026-10-10)**: `mvn -q -B verify` EXIT=0; `DbSchemaVerificationTest` 20 tests / 0 failures / 0 errors (3 de migraciones y motor, 1 de índices, 2 de CHECKs, 11 parametrizados de FKs, 1 de RLS, 2 de semilla). Extras sobre el checklist mínimo: se verifican las 5 FKs de negocio restantes y las 3 FKs de tokens con su `confdeltype`, y `chk_nombre_no_vacio` se comprueba explícitamente en `usuarios` **y** en `anuncios`.
- [ ] **T5 — Verificación y commit**: padre `mvn verify` + commit `feat(db):`. Pendiente: el writer no ejecuta operaciones de git.

## Entorno (verificado)

- Runtime de contenedores local: **Podman 5.8.7** con shim `docker` y `DOCKER_HOST=unix:///run/user/1000/podman/podman.sock` ya exportado; `podman.socket` activo. Si Ryuk falla en rootless, documentar el ajuste aplicado (`TESTCONTAINERS_RYUK_DISABLED` o `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE`) y dejarlo anotado aquí.
  - **Resultado (2026-10-10)**: no hizo falta ningún ajuste. Con Testcontainers 2.0.5 + Ryuk 0.14.0 sobre Podman rootless, Ryuk arrancó a la primera: `Ryuk started - will monitor and terminate Testcontainers containers on JVM exit`. **Ni `TESTCONTAINERS_RYUK_DISABLED` ni `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE` fueron necesarios**; los contenedores (`testcontainers/ryuk:0.14.0`, `postgres:18`) quedan limpios al terminar el JVM. CI (GitHub runners con Docker real) no requiere nada especial.
- Imagen: `postgres:18` (el contenedor resolvió a PostgreSQL **18.6**).
- Módulo de motor de Flyway: `flyway-core` 12.4.0 (el que trae el starter de Boot 4.1.1) **no incluye** el soporte de PostgreSQL — solo H2/SQLite/base. Sin `org.flywaydb:flyway-database-postgresql` el arranque falla con `FlywayException: Unsupported Database: PostgreSQL 18.x`. Se añadió al `pom.xml` (versión gestionada por el BOM de Boot 4.1.1).

## Hallazgos

- **Testcontainers 2.x renombra artefactos**: `org.testcontainers:junit-jupiter` → `testcontainers-junit-jupiter` y `org.testcontainers:postgresql` → `testcontainers-postgresql` (Boot 4.1.1 gestiona TC 2.0.5, así que no se fijan versiones). Además, en TC 2.x la clase canónica es `org.testcontainers.postgresql.PostgreSQLContainer` (no genérica); `org.testcontainers.containers.PostgreSQLContainer` (`<SELF>`) sigue existiendo como compatibilidad.
- **`@ServiceConnection` funciona en Boot 4.1.1** con el paquete habitual `org.springframework.boot.testcontainers.service.connection.ServiceConnection`; la fábrica genérica `org.springframework.boot.jdbc.testcontainers.JdbcContainerConnectionDetailsFactory` (módulo `spring-boot-jdbc`) casa con cualquier `JdbcDatabaseContainer`, así que **no** hizo falta el fallback a `@DynamicPropertySource`.
- El esquema se aplica desde la configuración real de la app (`spring.flyway.enabled=true`), por eso el test también valida el cableado de Flyway en el arranque.
- En `pg_indexes` se filtran por `schemaname='public'` y se verifica por nombre: los índices de PK/UNIQUE-constraint (p. ej. `usuarios_pkey`, `categorias_nombre_key`) no forman parte de los 16 y por eso no se assertion-a un conteo total.

## Criterios de aceptación

- `mvn -q -B verify` verde con el test de integración corriendo contra PostgreSQL 18 real.
- Reproducibilidad: V1+V2+V3 en orden sobre base vacía = esquema completo (DBDD §6).
- Ninguna migración editada tras aplicarse (forward-only): los archivos son inmutables desde su primer commit.

## Verificación (evidencia 2026-10-10)

`mvn -q -B verify` → EXIT=0 (49 tests en total, 0 failures / 0 errors). `DbSchemaVerificationTest`: 20/0/0 contra PostgreSQL real.

| Comprobación | Resultado |
|---|---|
| Motor del contenedor (`SELECT version()`) | `PostgreSQL 18.6 (Debian 18.6-1.pgdg13+2) on x86_64-pc-linux-gnu` |
| Tablas en `information_schema` (public, sin `flyway_schema_history`) | 8: `usuarios`, `categorias`, `anuncios`, `conversaciones`, `mensajes`, `tokens_verificacion_email`, `tokens_restablecimiento_contrasena`, `tokens_refresh` |
| Los 16 índices nombrados en `pg_indexes` | `idx_anuncios_vendedor`, `idx_anuncios_categoria`, `idx_anuncios_estado_revision`, `idx_anuncios_publicacion`, `idx_anuncios_nombre_trgm`, `idx_anuncios_descripcion_trgm`, `idx_conversaciones_comprador`, `idx_conversaciones_vendedor`, `idx_mensajes_conversacion_creado`, `idx_mensajes_no_leidos`, `idx_usuarios_rol`, `ux_usuarios_email_activo`, `idx_categorias_orden`, `ux_tokens_email_activos`, `ux_tokens_reset_activos`, `ux_tokens_refresh_activos` |
| Los 13 CHECKs nombrados en `pg_constraint` (`contype='c'`) | 14 filas / 13 nombres: `usuarios.chk_nombre_no_vacio`, `usuarios.usuarios_rol_check`, `anuncios.chk_nombre_no_vacio`, `anuncios.chk_precio_rango`, `anuncios.chk_max_imagenes`, `anuncios.chk_imagenes_pareadas`, `anuncios.anuncios_estado_check`, `anuncios.anuncios_estado_revision_check`, `anuncios.chk_revision_trail_consistente`, `anuncios.chk_motivo_rechazo_obligatorio`, `conversaciones.chk_conversacion_no_propia`, `mensajes.chk_mensaje_no_vacio`, `categorias.chk_icono_longitud` |
| `pg_policies` | 0 (sin RLS) |
| Semilla + idempotencia | 6 categorías, `orden` 1–6; re-ejecución del INSERT de V2 → 6 |
| `confdeltype` de las FKs | anuncios→usuarios `c`, anuncios→categorias `n`, anuncios.revisado_por `a`, las 3 de conversaciones `c`, las 2 de mensajes `c`, las 3 de tokens `a` |

Ajustes de entorno aplicados: **ninguno** (ver «Entorno»).

## Nota del padre (2026-10-10)

El writer implementó los índices `ux_tokens_*_activos` como **UNIQUE parciales**, más fuerte que la letra del DBDD (que los listaba como parciales simples y dejaba "a lo sumo un token activo por usuario y propósito" como regla de aplicación). Decisión aceptada: elimina la carrera de dos reenvíos/rotaciones concurrentes y encaja con O7 ("invariantes críticas impuestas por el esquema"). El DBDD se sincronizó en 3 sitios (§3.1 definiciones, §5 reglas de BD, §5 regla de aplicación reformulada) para mantener la única fuente de verdad. Spot check del padre: `mvn -q -B verify` → EXIT=0; 49 tests (20 del esquema).
