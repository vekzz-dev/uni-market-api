# Aceptación final — checklist PRD §8

| Campo | Valor |
|---|---|
| Estado | **Verificado (2026-10-12)** |
| HEAD | `mvn clean verify` EXIT=0 · **190 tests, 0 fallos** · cobertura de líneas **91.5 %** (gate ≥ 80 % activo) |
| Alcance | Los 17 criterios de aceptación del PRD §8, cada uno mapeado a su evidencia real en la suite |
| Actualizaciones posteriores | 2026-10-13/14: contenerización completa (`Dockerfile` + `compose.yaml` con BD y API), perfiles `dev/prod` con guarda fail-fast de credenciales, y CORS configurable por entorno (+3 tests). Ninguna regla del PRD cambió: los 17 criterios siguen verificados |

## Resultado por criterio

| # | Criterio (PRD §8) | Estado | Evidencia (suite real) |
|---|---|---|---|
| 1 | Registro crea `miembro` sin confirmar; login rechazado hasta confirmar | ✅ | `IdentityFlujoIntegrationTest` (201 → 401 → confirmar → 200) |
| 2 | Mismo email en distinto caso → 409 sin duplicados | ✅ | `IdentityFlujoIntegrationTest` (409 distinto caso) |
| 3 | Email de baja queda libre; la baja NO destruye anuncios, conversaciones ni mensajes | ✅ | `IdentityFlujoIntegrationTest` (email libre) + `AceptacionFinalIntegrationTest.bajaNoDestruyeDatos` (filas intactas + historial legible) |
| 4 | Todo anuncio —incluido el de un admin— nace `pendiente` y no es visible hasta aprobar | ✅ | `AceptacionFinalIntegrationTest.anuncioDeAdminNacePendiente` + `CatalogoFlujoIntegrationTest.flujoDeVisibilidad` |
| 5 | Catálogo solo `disponible+aprobado`; detalle no visible → 404 indistinguible | ✅ | `CatalogoFlujoIntegrationTest.flujoDeVisibilidad` + `AnuncioRepositoryTest.soloVisible` |
| 6 | Nadie decide su propio anuncio ni cambia su propio rol (caso permitido y denegado) | ✅ | `ModeracionFlujoIntegrationTest.antiAutoYAdmin` (403 propio / 200 admin ajeno) + `AdminFlujoIntegrationTest.guardsDeRol` + unit `ServicioRevisionTest`/`ServicioAdministracionUsuariosTest` |
| 7 | Rechazo/retracción dejan `revisadoPor`+`fechaRevision`+`motivoRechazo`; sin motivo → 422 | ✅ | `ModeracionFlujoIntegrationTest.validaciones` + `ServicioRevisionTest` (trail completo, motivo vacío 422) |
| 8 | Editar `aprobado`/`rechazado` → `pendiente` + limpia trail | ✅ | `CatalogoFlujoIntegrationTest.editarAjenoYReset` + `ServicioAnunciosTest.edicionReseteaRevision` |
| 9 | Una conversación por par; sin auto-conversación; mensaje vacío nunca persistido | ✅ | `MensajeriaFlujoIntegrationTest` (201→200 mismo id, auto 422, blank 422) + `ConversacionRepositoryTest.parUnico/sinAutoConversacion` |
| 10 | Cero plaintext en tokens; consumido/expirado no se reutiliza; reuso de refresh revoca familia | ✅ | `AceptacionFinalIntegrationTest.tokensSiempreHasheados` (regex SHA-256) + `IdentityFlujoIntegrationTest` (reuso → familia revocada) |
| 11 | Reset responde idéntico exista o no + rate limit | ✅ | `IdentityFlujoIntegrationTest` (202 idéntico, 429) |
| 12 | Vendedor no modifica ajenos (403) ni ve ajenos en su panel | ✅ | `CatalogoFlujoIntegrationTest.editarAjenoYReset` + `AnuncioRepositoryTest.soloDelVendedor` |
| 13 | Límites de imagen con status correcto y sin objetos subidos (5 pareadas/5 MB/avatar 2 MB/tipos) | ✅ | `CatalogoFlujoIntegrationTest.rechazosSinHuerfanos` (422 sin huérfanos) + `ServicioAnunciosTest` (firma/tamaño/cantidad) + `IdentityFlujoIntegrationTest` (avatar) + `DbSchemaVerificationTest` (CHECKs pareados) |
| 14 | Suite verde con gate de cobertura y contrato validado | ✅ | `mvn clean verify`: 183 tests, gate ≥ 80 % activo (90.7 %), `OpenApiContractTest` (estructura + sin claves duplicadas). **Nota honesta**: el CI está definido y el build es idéntico en local; su primera ejecución real será al publicar el repo en GitHub |
| 15 | Web y móvil consumen EXCLUSIVAMENTE el cliente generado | ➖ N/A en este repo | Los clientes viven en repos externos (decisión de alcance, PRD §7); este repo garantiza la fuente de verdad y su validación en el build |
| 16 | Sin políticas RLS ni triggers en la BD | ✅ | `AceptacionFinalIntegrationTest.sinRlsNiTriggers` (`pg_policies`=0, `pg_trigger` no internos=0) + `DbSchemaVerificationTest` |
| 17 | Apertura solo sobre anuncio visible con vendedor activo; envío con contraparte activa; historial previo legible | ✅ | `MensajeriaFlujoIntegrationTest.apertura/participacionYEnvio/flujoCompleto` |

## Conclusión

**16 de 17 criterios verificados con evidencia automatizada; 1 fuera de alcance de este repositorio por diseño (criterio 15)**. La entrega queda cerrada con:

- `mvn clean verify` reproducible: 183 tests · 0 fallos · 90.7 % de líneas con el gate activo.
- Contrato OpenAPI validado en el build (estructura, `$ref`s y claves duplicadas).
- Pendientes del SDD sin deudas abiertas: N3 y N8 resueltos; las notas restantes son decisiones conscientes (memoria del limiter en despliegue único; verificación real de R2 al desplegar).
