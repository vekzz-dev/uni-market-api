# Feature: hardening-post-catalog

| Campo | Valor |
|---|---|
| Estado | Completada (2026-10-12; 130 tests, cobertura 88.7%) |
| Creado | 2026-10-12 |
| Alcance | Cierre de N5/N6 + mejoras registradas: endpoint de reemplazo de imágenes (N6), reenvío de confirmación con cooldown (N5), no-objetivo de búsqueda con acentos, dirección de `orden=precio` explícita, límite de `q`, test del adaptador Resend |
| Documentos | `openapi.yaml`, `docs/prd.md`, `docs/system-design.md`, `odd/tasks/catalog.md`, `odd/tasks/identity.md` |

## Decisiones tomadas (2026-10-12, propietario)

- **N6**: nuevo endpoint **`PUT /v1/anuncios/{anuncioId}/imagenes`** (multipart `imagenes` 1..5): reemplazo completo con borrado de las viejas **post-commit** y reset de revisión (R6). El PATCH de contenido queda JSON puro.
- **N5**: **`POST /v1/auth/reenviar-confirmacion`** → `202` idéntico exista o no la cuenta (no-enumeración), invalida el token previo, **cooldown configurable** (`app.notificaciones.cooldown-reenvio`, default 2 min) y rate limit (`429`).
- **Búsqueda con acentos**: se declina como no-objetivo explícito (documentado en PRD y SDD); el índice trigram sigue siendo efectivo.
- **`orden=precio`**: ascendente, ahora explícito en la descripción del contrato.
- **`q`**: máximo 200 caracteres validado (400 estructural si se excede), espejo del contrato.
- **Adaptador Resend**: base URL configurable + test con stub HTTP local (`com.sun.net.httpserver`), incluida la ruta de error.

## Tareas

- [x] **H1 — N6 imágenes en edición**: `PUT /anuncios/{id}/imagenes` (multipart 1..5): subida nueva → commit → borrado viejo con hook `afterCommit` (`TransactionSynchronization`); compensación de nuevas ante fallo; reset R6. Tests: unit 3 (feliz/compensación/403) + integración (reemplazo real, archivo viejo borrado, revisión reseteada, 403 ajeno).
- [x] **H2 — N5 reenvío**: `PropiedadesNotificaciones.cooldownReenvio` (default 2m), `ServicioRegistro.reenviarConfirmacion` (202 genérico, cooldown silencioso, invalida previo), endpoint + rate limit. Tests integración: cooldown/invalidación, respuesta genérica triple, 429.
- [x] **H3 — Arreglos**: `q` ≤ 200 validado (test en integración → 400); `orden=precio` ASC explícito en el contrato; base URL de Resend configurable + `CorreoTransaccionalResendTest` (3 tests: envío, escape JSON, error 500) contra stub `com.sun.net.httpserver`.
- [x] **H4 — Docs**: PRD §7 no-objetivo de acentos + fila Identidad; SDD N5/N6 resueltos + N7; contrato con las 2 rutas nuevas.
- [x] **H5 — Verificación y commit**: `mvn clean verify` EXIT=0 con gate; commits por unidad (notifications/identity, catalog, docs).

## Evidencia (2026-10-12)

- Lote dirigido verde (32 tests entre contrato, adaptador, reenvío, servicio e integración de catalog).
- Un assert mal planteado mío en el unit de compensación (estado en memoria vs. rollback real) fue corregido hacia lo que importa: compensación de archivos (nueva borrada, vieja intacta).
- **`mvn clean verify` FINAL: EXIT=0 — 130 tests, 0 fallos, cobertura de líneas 88.7 %** (con el gate de 80 % activo; el avance vino de cubrir también el adaptador Resend).
- **Remediación de entorno (Podman)**: el suite falló de forma reproducible con `Broken pipe` contra el socket de Podman. Causa raíz: `podman.service` (socket-activated) se apagaba por inactividad y systemd lo re-arrancaba en cada ráfaga de requests del test — la carrera durante el ciclo de apagado/arranque rompía la conexión a mitad de `POST /containers/create`. Fix aplicado fuera del repo (documentado aquí): override `~/.config/systemd/user/podman.service.d/99-no-idle-timeout.conf` con `ExecStart=... system service --timeout 0` + `daemon-reload` + reinicio del socket. Verificado: servicio activo tras 30 s de inactividad y suite completa en verde (0 broken pipes). Es el fix documentado por Testcontainers para Podman rootless.

## Criterios de aceptación

- Reemplazo de imágenes: arrays pareados actualizados, objetos viejos borrados solo tras commit, revisión reseteada, 403/404 correctos.
- Reenvío: respuesta idéntica en todos los casos, cooldown respetado, token previo invalidado, 429 con rate limit.
- Test del adaptador Resend verde (éxito + error) y el adaptador sin cambios de comportamiento en prod.
- `mvn clean verify` EXIT=0 con cobertura ≥ 80 %.
