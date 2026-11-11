# Feature: pin-confirmacion-reset

| Campo | Valor |
|---|---|
| Estado | Completada (2026-11-11; `mvn verify` EXIT=0, 259 tests, cobertura con gate) |
| Creado | 2026-11-11 |
| Alcance | Issue #8: confirmación de email y restablecimiento de contraseña con PIN de 6 dígitos, sin enlaces; correos HTML |
| No incluye | Cambios en el flujo de login/refresh; notificaciones distintas de confirmación y reset |
| Estrategia de entrega | single-pr |
| Documentos | `openapi.yaml` (`/auth/confirmar-email`, `RestablecerConfirmacionRequest`), `docs/prd.md` (R11, §5, criterio 21), `docs/database-design.md` (V7, CHECKs), plantillas HTML |

## Specs

- **S1** — El registro y la solicitud de reset envían un **PIN de 6 dígitos**, generado con `SecureRandom` uniforme, y **ningún enlace**: ni URL, ni `token=` en el cuerpo.
- **S2** — `POST /v1/auth/confirmar-email` con `{email, pin}`; `POST /v1/auth/restablecer-contrasena/confirmar` con `{email, pin, nuevaContrasena}`. Se elimina el `GET` con token.
- **S3** — Vigencia **15 minutos** y **máximo 5 intentos** por PIN; al agotarse el código se invalida y hay que pedir otro (migración `V7`: columna `intentos` en las dos tablas).
- **S4** — Solo se persiste el hash SHA-256; la comparación usa `MessageDigest.isEqual` (tiempo constante) y el PIN nunca aparece en rutas ni en la base de datos.
- **S5** — Errores genéricos: cuenta inexistente, PIN incorrecto, expirado, usado o agotado responden el mismo `422` (R13). Rate limit en confirmar y confirmar-reset.
- **S6** — Correos en **HTML** (plantillas de confirmación y restablecimiento) con el PIN destacado, más versión de texto plano. El nombre se escapa al interpolar.
- **S7** — PRD, contrato, DBDD y documentación local actualizados; la suite completa en verde.

## Tareas

- [x] **T1 — Esquema**: `V7` con `intentos` y CHECK no negativo en ambas tablas; `DbSchemaVerificationTest` al día (17 CHECKs, V1–V7).
- [x] **T2 — Dominio**: `TokensSeguridad.generarPin`/`pinCoincide`; `activo()` considera intentos; `registrarIntentoFallido()`.
- [x] **T3 — Servicios**: confirmación y reset por `(email, pin)`, TTL 15 min, invalidación al agotar intentos, PIN hasheado.
- [x] **T4 — Contrato y HTTP**: POST con cuerpos validados (`@Pattern` 6 dígitos), rate limit en ambos endpoints, sin `GET` con token.
- [x] **T5 — Correos**: `MensajeCorreo` con `html`; Resend envía `text`+`html`; plantillas en `resources/plantillas`; `PlantillasCorreo` escapa valores.
- [x] **T6 — Verificación**: `mvn verify` 257 tests; prueba manual en contenedor: registro → PIN en correo real → confirmar 200 → reuso 422 → PIN incorrecto 422.

## Log

- **L1** — `L1` (2026-11-11, verbatim): «Que todo sea por pin, nada de link ya. [...] ajustalas para usarlas con pin. Crea el issue y lo resuelves.»
- **L2** — Issue #8 creada con alcance, decisiones (15 min, 5 intentos, POST) y criterios de aceptación.
- **L3** — Decisión: TTL de 15 min en ambos flujos (antes 24 h y 60 min). Un PIN de 10⁶ combinaciones con 24 h y sin contador es fuerza bruta trivial.
- **L4** — Decisión: `VIGENCIA_PIN_MINUTOS` vive en `notifications` y `identity` la consume. Al revés rompería la frontera de módulos (ArchUnit: `identity → notifications`).
- **L5** — Evidencia (2026-11-11): RED inicial por firmas cambiadas (servicios, DTOs, `MensajeCorreo`, `OyenteCorreos`). GREEN con 257 tests.
- **L6** — Evidencia manual en contenedor: registro con `pin.prueba.…@chuwel.com` → correo redirigido a la bandeja de pruebas con `html=sí` y PIN de 6 dígitos; `confirmar` 200; reuso 422; PIN incorrecto 422.
- **L9** — Verificación independiente (2026-11-11, `FAIL` → corregido): el contador de intentos **no se persistía**. La excepción de negocio se lanzaba dentro de la transacción y la revertía, así que `intentos` volvía a 0 y el PIN seguía válido tras cinco fallos. Mi test unitario solo probaba la entidad en memoria: no probaba nada del flujo. Corregido con `@Transactional(noRollbackFor = ReglaDeNegocioException.class)` en `confirmarEmail` y `confirmar` (mismo patrón que ya usa `ServicioSesion` para el reuso de refresh), y probado con `PinIntentosIntegrationTest`: cinco fallos → `intentos` 1..5 persistidos y el quinto consume el PIN.
- **L10** — Verificación independiente (2026-11-11, cierre): documentación derivada puesta al día (DBDD: `intentos` y TTL 15 min; SDD: flujo POST con PIN; README y guía local: ya no hablan de token). Advisory aceptado y no corregido: un `GET` sobre la ruta eliminada devuelve `500` en vez de `405`; es el fallback genérico preexistente y ningún cliente generado lo llamará.
- **L7** — Nota: el rate limit (5/min) y el máximo de intentos (5) se solapan; un usuario que falla cinco veces seguidas verá `429` en el sexto intento. Aceptado: el código queda igualmente invalidado.
- **L8** — Rompe el cliente: se elimina el `GET` con token y cambian dos cuerpos. Requiere `contract:sync` + `api:generate` y pantalla de código en el frontend.
