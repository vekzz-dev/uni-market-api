# Feature: api-bootstrap

| Campo | Valor |
|---|---|
| Estado | Completada (2026-10-10; follow-up: validación Java del contrato) |
| Creado | 2026-10-10 |
| Alcance | Scaffold del proyecto Maven (Boot 4.1 / Java 21) — sin lógica de negocio |
| Presupuesto (heurística ~400 líneas) | ~350 líneas escritas; cabe en un PR único |
| Estrategia de entrega | single-pr (por debajo del presupuesto) |
| Documentos | `docs/system-design.md` (§3, §4, §9, D11), `docs/prd.md` (§5 Repositorio, §6 Pruebas), `openapi.yaml` |

## Objetivo

Dejar el repositorio listo para implementar los módulos del SDD: build Maven verde, estructura de paquetes con fronteras, CI mínimo y reglas de dependencia verificadas por test.

## Tareas

- [x] **T1 — Build**: `pom.xml` (parent Boot 4.1.1, Java 21 target, starters Boot 4: `webmvc`, `data-jpa`, `security`, `security-oauth2-resource-server`, `validation`, `flyway`; driver PostgreSQL; MapStruct; test: `starter-test`, `webmvc-test`, ArchUnit, JaCoCo sin gate aún). Ruta: delegado (writer). Verificación: `mvn -q -B verify` verde. **Evidencia (2026-10-10)**: writer `mvn -q -B verify` EXIT=0; padre re-ejecutó `mvn -q -B verify` EXIT=0 (spot check). Boot 4.1.1 resolvió directo; MapStruct 1.6.3, ArchUnit 1.5.1, JaCoCo 0.8.15 fijados explícitamente (el BOM de Boot 4 no los gestiona).
- [x] **T2 — Esqueleto**: clase principal `UniMarketApiApplication`, `application.yml` (perfil dev con placeholders de entorno; Flyway activo; `ddl-auto=validate`), `package-info.java` de los 8 módulos. Ruta: delegado (mismo writer). Verificación: build verde + `.gitignore` cubre `target/`. **Evidencia**: `git status` ignora `target/`; jar `target/api-0.0.1-SNAPSHOT.jar` generado.
- [x] **T3 — Fronteras**: test ArchUnit en `src/test/java/.../architecture/ModuleBoundariesTest.java` (shared no depende de módulos; sin ciclos entre módulos) + CI `.github/workflows/ci.yml` (Maven verify + spectral lint del OpenAPI). Ruta: delegado. Verificación: `mvn test` ejecuta el test y pasa. **Evidencia**: `Tests run: 2, Failures: 0`; CI añadido con `.spectral.yaml` (2 líneas, requerido para que `spectral lint openapi.yaml` funcione sin `--ruleset`).
- [x] **T4 — Commit de cierre**: revisión del padre (spot check `mvn verify`) y commit `build:` del scaffold. Ruta: padre. **Evidencia**: `1796bda build: bootstrap Spring Boot 4.1 Maven project with module skeleton and CI`; fix del contrato en `43dc239 docs: fix OpenAPI contract issues found by spectral lint` (duplicado `CategoriaId`, `parameters` faltante en DELETE, `default` inválido en parámetro `EstadoRevision` — todos detectados por spectral y corregidos; lint final 0 errores / 0 warnings).

## Criterios de aceptación

- `mvn -q -B verify` verde en limpio.
- `git status` limpio tras el commit; `target/` ignorado.
- Ninguna dependencia inventada: todo resuelve desde Maven Central (Boot 4.1.x).
- CI definido es ejecutable tal cual (no referencia secretos).

## Hallazgos relevantes

- Entorno local: JDK 25 (GraalVM LTS) + Maven 3.9.16; el target 21 se logra con `maven.compiler.release=21` (no requiere toolchain).
- Nombres Boot 4 confirmados (guía de migración oficial): `spring-boot-starter-webmvc` (antes `web`), `spring-boot-starter-flyway` (nuevo), `spring-boot-starter-security-oauth2-resource-server`, starters de test por tecnología (`-webmvc-test`).
- JaCoCo entra sin `check` activo: sin código de dominio, el gate de 80 % no es evaluable todavía (se activa con el módulo `identity`).

## Follow-up (2026-10-10): validación del contrato sin Node

Sustituido el lint con spectral (job de CI con Node) por **guardas Java dentro de `mvn verify`** (`OpenApiContractTest`): validez estructural + resolución de `$ref`s con `swagger-parser` 2.1.27, y prohibición de claves YAML duplicadas con SnakeYAML estricto (`allowDuplicateKeys=false`). El job `contract` de CI y `.spectral.yaml` se eliminaron: el repo queda **sin ninguna herramienta Node/JS** (decisión del propietario). Verificado con pruebas negativas: clave duplicada inyectada → falla; `$ref` roto inyectado → falla; restaurado → verde.
Nota: el plugin `openapi-generator-maven-plugin` 7.14.0 NO expone un goal `validate` (solo `generate` y `help`) — descartado por eso.
