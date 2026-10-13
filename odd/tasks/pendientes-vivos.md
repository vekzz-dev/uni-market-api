# Feature: pendientes-vivos

| Campo | Valor |
|---|---|
| Estado | Completada (2026-10-12; 179 tests, cobertura 90.7%) |
| Creado | 2026-10-12 |
| Alcance | Cierre de los pendientes vivos tras la auditoría de docs: N3 (rate limit configurable), N8 (adaptador R2 de producción) y README de entrega |
| Presupuesto | ~600 líneas con tests; single-pr |
| Documentos | `docs/system-design.md` (N3/N8, D14, §10), `docs/prd.md` (Q1), README nuevo |

## Decisiones de diseño

- **N3**: los límites del rate limiter se externalizan (`app.seguridad.rate-limit.intentos-por-minuto`, default 5) manteniendo el almacenamiento en memoria — es correcto para el despliegue único documentado; un limiter distribuido exigiría un backing compartido (nota para multi-instancia). N3 se resuelve como "por diseño + configurable".
- **N8**: adaptador `AlmacenR2` (S3-compatible, SDK v2 de AWS) tras `app.media.proveedor=r2` con endpoint/bucket/credenciales/URL pública por entorno; dev sigue con filesystem. **El puerto evoluciona**: `rutaDesdeUrl` pasa de estático a método de instancia (filesystem extrae tras `/media/`; R2 tras la URL pública base) porque el avatar almacena solo URL. Verificación real contra R2 queda para el despliegue (documentado); unit tests con cliente S3 mockeado cubren mapeo y llamadas.
- **README**: guía de arranque (Postgres, variables, `mvn spring-boot:run`, test, mapa de endpoints y cuenta demo implícita).

## Tareas

- [x] **P1 — N3**: `PropiedadesRateLimit` (`app.seguridad.rate-limit.intentos-por-minuto`, default 5) + wiring en `LimitadorTasa` + ymls + stubs; `LimitadorTasaTest` (3 tests: límite 1, default/cero inválido, separación por propósito e IP). SDD N3 → resuelto como configurable + memoria por diseño.
- [x] **P2 — N8**: `s3` + `apache-client` (2.46.7, versión explícita), `AlmacenR2` + `ConfiguracionAlmacenR2` condicionales a `app.media.proveedor=r2`; **puerto evolucionado**: `rutaDesdeUrl` de instancia (filesystem extrae tras `/media/`, R2 tras su URL pública) con ajuste en `ServicioPerfil`; `PropiedadesMedia` (+`proveedor`+`r2`); ymls con placeholders (main) y `filesystem` explícito (test); `AlmacenR2Test` (5 tests con `S3Client` mockeado: subida con bucket/clave/tipo, lectura+ausente, borrado, URL inversa, config incompleta). Verificación real contra R2 queda documentada para despliegue.
- [x] **P3 — README**: arranque local (Postgres, `JWT_SECRETO`, `spring-boot:run`), flujo de humo, suite de pruebas, tabla de variables de entorno, mapa de la API y estructura del repo.
- [x] **P4 — Verificación y commit**: `mvn clean verify` EXIT=0 (179 tests, 90.7 %, gate activo); commits `feat(rate-limit)`, `feat(media)`, `docs(readme)`, `docs(sdd)`.

## Evidencia (2026-10-12)

- Con N3 y N8 cerrados, la tabla de pendientes del SDD queda **sin deudas abiertas** (las filas restantes son notas de diseño conscientes: memoria del rate limiter en despliegue único y verificación de R2 al desplegar).

## Criterios de aceptación

- Límite cambiado por configuración surte efecto (test con propiedad de límite 1 → segundo intento 429).
- `AlmacenR2` unit-tested (clave y URL correctas, delete/read mapeados) sin red; filesystem intacto (suite previa verde).
- README reproducible: quien lo siga levanta la API y completa registro→confirmación→login.
- `mvn clean verify` EXIT=0 con cobertura ≥ 80 %.
