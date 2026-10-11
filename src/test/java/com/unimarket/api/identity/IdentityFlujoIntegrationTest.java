package com.unimarket.api.identity;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.unimarket.api.notifications.CorreoTransaccional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

/**
 * Flujos de identidad end-to-end (T6): registro → confirmación → login → refresh con
 * detección de reuso → reset no-enumerable → perfil/avatar/baja, contra PostgreSQL real.
 *
 * <p>Verifica los criterios del PRD §8 tocados por identity: 409 case-insensitive, login
 * bloqueado sin confirmar, 401 genérico indistinguible (R13), reuso que revoca la familia
 * (R12), reset idéntico exista o no (R13), baja que corta la sesión (R15) y avatar 422.
 */
@Tag("integration")
@DisplayName("Identidad: flujos end-to-end")
class IdentityFlujoIntegrationTest extends SoportePostgresIT {

    private static final Pattern TOKEN_EN_CUERPO = Pattern.compile("token=([A-Za-z0-9_\\-]+)");

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private ObjectMapper json;

    @MockitoBean
    private CorreoTransaccional correos;

    // ------------------------------------------------------------------ helpers ---

    private static String emailUnico(String prefijo) {
        return prefijo + "+" + UUID.randomUUID() + "@correo.com";
    }

    private ResponseEntity<JsonNode> post(String ruta, Object cuerpo) {
        ResponseEntity<String> respuesta = http.postForEntity(ruta, cuerpo, String.class);
        return conCuerpo(respuesta);
    }

    private ResponseEntity<JsonNode> get(String ruta) {
        ResponseEntity<String> respuesta = http.getForEntity(ruta, String.class);
        return conCuerpo(respuesta);
    }

    private ResponseEntity<JsonNode> postAutenticado(String ruta, Object cuerpo, String acceso) {
        HttpHeaders cabeceras = new HttpHeaders();
        cabeceras.setContentType(MediaType.APPLICATION_JSON);
        cabeceras.setBearerAuth(acceso);
        ResponseEntity<String> respuesta =
                http.exchange(ruta, HttpMethod.POST, new HttpEntity<>(cuerpo, cabeceras), String.class);
        return conCuerpo(respuesta);
    }

    private ResponseEntity<JsonNode> getAutenticado(String ruta, String acceso) {
        HttpHeaders cabeceras = new HttpHeaders();
        cabeceras.setBearerAuth(acceso);
        ResponseEntity<String> respuesta =
                http.exchange(ruta, HttpMethod.GET, new HttpEntity<>(null, cabeceras), String.class);
        return conCuerpo(respuesta);
    }

    private ResponseEntity<JsonNode> intercambioAutenticado(
            String ruta, HttpMethod metodo, Object cuerpo, String acceso) {
        HttpHeaders cabeceras = new HttpHeaders();
        if (cuerpo != null) {
            cabeceras.setContentType(MediaType.APPLICATION_JSON);
        }
        cabeceras.setBearerAuth(acceso);
        ResponseEntity<String> respuesta =
                http.exchange(ruta, metodo, new HttpEntity<>(cuerpo, cabeceras), String.class);
        return conCuerpo(respuesta);
    }

    private ResponseEntity<JsonNode> conCuerpo(ResponseEntity<String> respuesta) {
        JsonNode cuerpo = respuesta.getBody() == null ? null : json.readTree(respuesta.getBody());
        return new ResponseEntity<>(cuerpo, respuesta.getHeaders(), respuesta.getStatusCode());
    }

    private ResponseEntity<JsonNode> registrar(String email) {
        return post("/v1/usuarios", Map.of(
                "email", email, "nombre", "Ana Estudiante", "contrasena", "secreta123"));
    }

    private ResponseEntity<JsonNode> login(String email, String contrasena) {
        return post("/v1/auth/login", Map.of("email", email, "contrasena", contrasena));
    }

    private String tokenCrudoDeConfirmacion(String email) {
        return ultimoTokenPara(email, "Confirma tu cuenta");
    }

    private String tokenCrudoDeReset(String email) {
        return ultimoTokenPara(email, "Restablece tu contraseña");
    }

    /**
     * Extrae el token crudo del último correo enviado a ese email con ese asunto: el
     * oyente async ya llamó al puerto (verificado con Awaitility) y el cuerpo porta el
     * token de un solo uso.
     */
    private String ultimoTokenPara(String email, String asunto) {
        org.mockito.ArgumentCaptor<CorreoTransaccional.MensajeCorreo> captor =
                org.mockito.ArgumentCaptor.forClass(CorreoTransaccional.MensajeCorreo.class);
        await().untilAsserted(() -> verify(correos, atLeastOnce()).enviar(captor.capture()));
        return captor.getAllValues().stream()
                .filter(m -> m.destino().equals(email) && m.asunto().startsWith(asunto))
                .map(m -> {
                    Matcher matcher = TOKEN_EN_CUERPO.matcher(m.texto());
                    return matcher.find() ? matcher.group(1) : null;
                })
                .filter(token -> token != null)
                .reduce((primero, ultimo) -> ultimo)
                .orElseThrow(() -> new AssertionError("sin correo '" + asunto + "' para " + email));
    }

    // ------------------------------------------------------------------- flujos ---

    @Nested
    @DisplayName("Registro → confirmación → login")
    class RegistroConfirmacionLogin {

        @Test
        @DisplayName("201 sin sesión → 401 sin confirmar → 200 confirmar → 200 login con par JWT+refresh")
        void flujo_shouldBloquearLoginHastaConfirmar() {
            // Arrange
            String email = emailUnico("flujo");

            // Act: registro
            var registro = registrar(email);

            // Assert: 201 sin tokens de sesión
            assertThat(registro.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(registro.getBody().get("email").asText()).isEqualTo(email.toLowerCase());
            assertThat(registro.getBody().get("rol").asText()).isEqualTo("miembro");
            assertThat(registro.getBody().has("hashContrasena")).isFalse();

            // Act + Assert: login bloqueado antes de confirmar (401 genérico, R13)
            var previo = login(email, "secreta123");
            assertThat(previo.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

            // Act: confirmación con el token crudo del evento post-commit
            String crudo = tokenCrudoDeConfirmacion(email.toLowerCase());
            var confirmacion = get("/v1/auth/confirmar-email?token=" + crudo);

            // Assert
            assertThat(confirmacion.getStatusCode()).isEqualTo(HttpStatus.OK);

            // Act + Assert: login emite el par de sesión
            var sesion = login(email, "secreta123");
            assertThat(sesion.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(sesion.getBody().get("accessToken").asText()).isNotBlank();
            assertThat(sesion.getBody().get("refreshToken").asText()).isNotBlank();
            assertThat(sesion.getBody().get("expiraEnSegundos").asInt()).isPositive();

            // Act + Assert: el token de confirmación es de un solo uso
            var reuso = get("/v1/auth/confirmar-email?token=" + crudo);
            assertThat(reuso.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        }

        @Test
        @DisplayName("Email normalizado: mayúsculas se guardan en minúsculas")
        void registro_shouldNormalizarEmail_aMinusculasSinEspacios() {
            // Arrange: @Email estructural rechaza espacios (400); el servicio recorta+minimiza
            String email = "MAYUS" + UUID.randomUUID() + "@CORREO.COM";

            // Act
            var respuesta = post("/v1/usuarios", Map.of(
                    "email", email, "nombre", "Ana", "contrasena", "secreta123"));

            // Assert
            assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(respuesta.getBody().get("email").asText())
                    .isEqualTo(email.toLowerCase());
        }

        @Test
        @DisplayName("Duplicado en distinto caso → 409 sin crear filas extra")
        void registro_shouldResponder409_cuandoEmailActivoEnDistintoCaso() {
            // Arrange
            String email = emailUnico("duplicado");
            assertThat(registrar(email).getStatusCode()).isEqualTo(HttpStatus.CREATED);

            // Act
            var duplicado = post("/v1/usuarios", Map.of(
                    "email", email.toUpperCase(), "nombre", "Otra", "contrasena", "secreta123"));

            // Assert
            assertThat(duplicado.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(duplicado.getHeaders().getContentType()
                    .isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)).isTrue();
        }

        @Test
        @DisplayName("Token de confirmación inválido → 422")
        void confirmar_shouldResponder422_cuandoTokenDesconocido() {
            // Act
            var respuesta = get("/v1/auth/confirmar-email?token=no-existe");

            // Assert
            assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        }
    }

    @Nested
    @DisplayName("Login indistinguible (R13/R15)")
    class LoginIndistinguible {

        @Test
        @DisplayName("Credenciales malas, sin confirmar e inexistente responden el mismo 401 genérico")
        void login_shouldResponderMismo401_sinRevelarLaCausa() {
            // Arrange
            String sinConfirmar = emailUnico("noconfirm");
            registrar(sinConfirmar);

            // Act
            var mala = login(sinConfirmar.toLowerCase(), "otra-clave");
            var sinConfirmarResp = login(sinConfirmar.toLowerCase(), "secreta123");
            var inexistente = login(emailUnico("nadie"), "secreta123");

            // Assert: mismo estado y mismo cuerpo genérico
            assertThat(mala.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(sinConfirmarResp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(inexistente.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(mala.getBody().toString()).isEqualTo(sinConfirmarResp.getBody().toString());
            assertThat(mala.getBody().toString()).isEqualTo(inexistente.getBody().toString());
        }
    }

    @Nested
    @DisplayName("Refresh rotativo con detección de reuso (R12)")
    class RefreshRotativo {

        @Test
        @DisplayName("Rotar emite par nuevo; reusar el anterior revoca TODA la familia")
        void refresh_shouldRevocarFamilia_cuandoSeReusaUnToken() {
            // Arrange: cuenta confirmada con sesión
            String email = emailUnico("rotacion");
            registrar(email);
            String emailNormalizado = email.toLowerCase();
            get("/v1/auth/confirmar-email?token="
                    + tokenCrudoDeConfirmacion(emailNormalizado));
            String refresh1 = login(emailNormalizado, "secreta123").getBody().get("refreshToken").asText();

            // Act: rotación legítima
            var rotado = post("/v1/auth/refresh", Map.of("refreshToken", refresh1));

            // Assert: nuevo par emitido
            assertThat(rotado.getStatusCode()).isEqualTo(HttpStatus.OK);
            String refresh2 = rotado.getBody().get("refreshToken").asText();
            assertThat(refresh2).isNotEqualTo(refresh1);

            // Act: reuso del token ya rotado (posible robo)
            var reuso = post("/v1/auth/refresh", Map.of("refreshToken", refresh1));

            // Assert: 401 y la familia entera queda revocada (el nuevo también cae)
            assertThat(reuso.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            var conNuevo = post("/v1/auth/refresh", Map.of("refreshToken", refresh2));
            assertThat(conNuevo.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        @DisplayName("Refresh desconocido → 401")
        void refresh_shouldResponder401_cuandoTokenDesconocido() {
            // Act
            var respuesta = post("/v1/auth/refresh", Map.of("refreshToken", "desconocido"));

            // Assert
            assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        @DisplayName("Logout revoca server-side de forma idempotente (siempre 204)")
        void logout_shouldResponder204Siempre_eInvalidarElRefresh() {
            // Arrange
            String email = emailUnico("logout");
            registrar(email);
            String normalizado = email.toLowerCase();
            get("/v1/auth/confirmar-email?token="
                    + tokenCrudoDeConfirmacion(normalizado));
            String refresh = login(normalizado, "secreta123").getBody().get("refreshToken").asText();

            // Act + Assert: primera vez revoca, segunda es idempotente
            assertThat(post("/v1/auth/logout", Map.of("refreshToken", refresh)).getStatusCode())
                    .isEqualTo(HttpStatus.NO_CONTENT);
            assertThat(post("/v1/auth/logout", Map.of("refreshToken", refresh)).getStatusCode())
                    .isEqualTo(HttpStatus.NO_CONTENT);
            assertThat(post("/v1/auth/logout", Map.of("refreshToken", "inexistente")).getStatusCode())
                    .isEqualTo(HttpStatus.NO_CONTENT);

            // Assert: el refresh revocado ya no rota
            assertThat(post("/v1/auth/refresh", Map.of("refreshToken", refresh)).getStatusCode())
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }

    @Nested
    @DisplayName("Restablecimiento no-enumerable (R13)")
    class Restablecimiento {

        @Test
        @DisplayName("202 idéntico exista o no la cuenta; el token confirma una sola vez")
        void reset_shouldResponder202Identico_yUnSoloUso() {
            // Arrange
            String existente = emailUnico("reset");
            registrar(existente);
            String normalizado = existente.toLowerCase();
            get("/v1/auth/confirmar-email?token="
                    + tokenCrudoDeConfirmacion(normalizado));
            String fantasma = emailUnico("fantasma");

            // Act
            var conCuenta = post("/v1/auth/restablecer-contrasena", Map.of("email", normalizado));
            var sinCuenta = post("/v1/auth/restablecer-contrasena", Map.of("email", fantasma));

            // Assert: respuestas idénticas
            assertThat(conCuenta.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            assertThat(sinCuenta.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            assertThat(conCuenta.getBody().toString()).isEqualTo(sinCuenta.getBody().toString());

            // Act: consumo del token de 60 minutos
            String crudo = tokenCrudoDeReset(normalizado);
            var confirmado = post("/v1/auth/restablecer-contrasena/confirmar",
                    Map.of("token", crudo, "nuevaContrasena", "nueva-clave-1"));

            // Assert: 204, segundo uso 422 y login con la nueva clave
            assertThat(confirmado.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
            var reuso = post("/v1/auth/restablecer-contrasena/confirmar",
                    Map.of("token", crudo, "nuevaContrasena", "otra-clave-22"));
            assertThat(reuso.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(login(normalizado, "nueva-clave-1").getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(login(normalizado, "secreta123").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        @DisplayName("Token de reset inválido → 422")
        void resetConfirmar_shouldResponder422_cuandoTokenDesconocido() {
            // Act
            var respuesta = post("/v1/auth/restablecer-contrasena/confirmar",
                    Map.of("token", "desconocido", "nuevaContrasena", "nueva-clave-1"));

            // Assert
            assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        }
    }

    @Nested
    @DisplayName("Cambio de contraseña autenticado")
    class CambioContrasena {

        @Test
        @DisplayName("Verifica la actual (422 si falla) y exige política 8+")
        void cambiar_shouldValidarActualYPolitica() {
            // Arrange
            String email = emailUnico("clave");
            registrar(email);
            String normalizado = email.toLowerCase();
            get("/v1/auth/confirmar-email?token="
                    + tokenCrudoDeConfirmacion(normalizado));
            String acceso = login(normalizado, "secreta123").getBody().get("accessToken").asText();

            // Act + Assert: actual incorrecta → 422
            var mala = postAutenticado("/v1/usuarios/me/contrasena",
                    Map.of("contrasenaActual", "no-es", "nuevaContrasena", "nueva-clave-1"), acceso);
            assertThat(mala.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);

            // Act + Assert: cambio válido → 204 y la nueva clave abre sesión
            var cambio = postAutenticado("/v1/usuarios/me/contrasena",
                    Map.of("contrasenaActual", "secreta123", "nuevaContrasena", "nueva-clave-1"), acceso);
            assertThat(cambio.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
            assertThat(login(normalizado, "nueva-clave-1").getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }

    @Nested
    @DisplayName("Perfil, avatar y baja")
    class PerfilAvatarBaja {

        private String accesoVigente(String email) {
            String normalizado = email.toLowerCase();
            get("/v1/auth/confirmar-email?token="
                    + tokenCrudoDeConfirmacion(normalizado));
            return login(normalizado, "secreta123").getBody().get("accessToken").asText();
        }

        @Test
        @DisplayName("GET/PATCH /me: lee el perfil y solo el nombre es editable")
        void perfil_shouldLeerYActualizarSoloNombre() {
            // Arrange
            String email = emailUnico("perfil");
            registrar(email);
            String acceso = accesoVigente(email);

            // Act: lectura
            var perfil = getAutenticado("/v1/usuarios/me", acceso);

            // Assert
            assertThat(perfil.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(perfil.getBody().get("email").asText()).isEqualTo(email.toLowerCase());
            assertThat(perfil.getBody().has("hashContrasena")).isFalse();

            // Act: actualización (el contrato no admite email: es de solo lectura, R2)
            var actualizado = intercambioAutenticado("/v1/usuarios/me", HttpMethod.PATCH,
                    Map.of("nombre", "Ana Nueva"), acceso);

            // Assert
            assertThat(actualizado.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(actualizado.getBody().get("nombre").asText()).isEqualTo("Ana Nueva");
            assertThat(actualizado.getBody().get("email").asText()).isEqualTo(email.toLowerCase());
        }

        @Test
        @DisplayName("Avatar: tipo no permitido y exceso de tamaño → 422; PNG válido persiste y se sirve")
        void avatar_shouldValidarTipoYTamano() {
            // Arrange
            String email = emailUnico("avatar");
            registrar(email);
            String acceso = accesoVigente(email);

            // Act + Assert: texto plano → 422 sin subir objetos
            assertThat(putAvatar(acceso, "image/png", "hola.txt", "no-es-imagen".getBytes()))
                    .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT.value());

            // Act + Assert: 3 MBPNG ficticio → 422 por tamaño
            assertThat(putAvatar(acceso, "image/png", "grande.png", new byte[3 * 1024 * 1024]))
                    .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT.value());

            // Act: PNG mínimo válido (firma de 8 bytes + contenido)
            byte[] png = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x01, 0x02};
            var subida = putAvatarEntidad(acceso, "image/png", "avatar.png", png);

            // Assert: 200 con URL pública y el objeto se sirve
            assertThat(subida.getStatusCode()).isEqualTo(HttpStatus.OK);
            String url = subida.getBody().get("urlAvatar").asText();
            assertThat(url).startsWith("/media/");
            var objeto = http.getForEntity(url, byte[].class);
            assertThat(objeto.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(objeto.getHeaders().getContentType()
                    .isCompatibleWith(MediaType.IMAGE_PNG)).isTrue();

            // Act + Assert: borrado limpia la URL (204) y el perfil queda sin avatar
            HttpHeaders cabeceras = new HttpHeaders();
            cabeceras.setBearerAuth(acceso);
            assertThat(intercambioAutenticado("/v1/usuarios/me/avatar", HttpMethod.DELETE, null, acceso)
                    .getStatusCode())
                    .isEqualTo(HttpStatus.NO_CONTENT);
            assertThat(getAutenticado("/v1/usuarios/me", acceso).getBody().get("urlAvatar").isNull())
                    .isTrue();
        }

        @Test
        @DisplayName("Baja: soft-delete que corta la sesión (access 401 + refresh 401, R15)")
        void baja_shouldCortarSesion_sinBorrarLaFila() {
            // Arrange
            String email = emailUnico("baja");
            registrar(email);
            String normalizado = email.toLowerCase();
            get("/v1/auth/confirmar-email?token="
                    + tokenCrudoDeConfirmacion(normalizado));
            var sesion = login(normalizado, "secreta123");
            String acceso = sesion.getBody().get("accessToken").asText();
            String refresh = sesion.getBody().get("refreshToken").asText();

            // Act
            var baja = intercambioAutenticado("/v1/usuarios/me", HttpMethod.DELETE, null, acceso);

            // Assert: 204 y la sesión queda cortada por el filtro de autoridad (D3)
            assertThat(baja.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
            assertThat(getAutenticado("/v1/usuarios/me", acceso).getStatusCode())
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(post("/v1/auth/refresh", Map.of("refreshToken", refresh)).getStatusCode())
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(login(normalizado, "secreta123").getStatusCode())
                    .isEqualTo(HttpStatus.UNAUTHORIZED);

            // Assert: el email queda libre para reutilización (R1/R2)
            assertThat(registrar(email.toUpperCase()).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        }

        private HttpHeaders bearerJson(String acceso) {
            HttpHeaders cabeceras = new HttpHeaders();
            cabeceras.setContentType(MediaType.APPLICATION_JSON);
            cabeceras.setBearerAuth(acceso);
            return cabeceras;
        }

        private int putAvatar(String acceso, String contentType, String nombre, byte[] bytes) {
            return putAvatarEntidad(acceso, contentType, nombre, bytes).getStatusCode().value();
        }

        private ResponseEntity<JsonNode> putAvatarEntidad(
                String acceso, String contentType, String nombre, byte[] bytes) {
            ByteArrayResource recurso = new ByteArrayResource(bytes) {
                @Override
                public String getFilename() {
                    return nombre;
                }
            };
            MultiValueMap<String, Object> partes = new LinkedMultiValueMap<>();
            HttpHeaders parte = new HttpHeaders();
            parte.setContentType(MediaType.parseMediaType(contentType));
            partes.add("archivo", new HttpEntity<>(recurso, parte));
            HttpHeaders cabeceras = new HttpHeaders();
            cabeceras.setContentType(MediaType.MULTIPART_FORM_DATA);
            cabeceras.setBearerAuth(acceso);
            ResponseEntity<String> respuesta = http.exchange("/v1/usuarios/me/avatar", HttpMethod.PUT,
                    new HttpEntity<>(partes, cabeceras), String.class);
            return conCuerpo(respuesta);
        }
    }

    @Nested
    @DisplayName("Límite de tasa (Q1/R13)")
    class LimiteTasa {

        @Test
        @DisplayName("Sexto registro con el mismo email en un minuto → 429 problem+json")
        void registro_shouldResponder429_cuandoExcedeCincoPorMinuto() {
            // Arrange
            String email = emailUnico("tasaregistro");
            int sexto = -1;

            // Act: seis intentos rápidos con el mismo email (el primero crea, resto 409)
            for (int i = 0; i < 6; i++) {
                var respuesta = post("/v1/usuarios", Map.of(
                        "email", email, "nombre", "Ana " + i, "contrasena", "secreta123"));
                if (i == 5) {
                    sexto = respuesta.getStatusCode().value();
                    // Assert dentro del último intento para conservar el cuerpo
                    assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    assertThat(respuesta.getHeaders().getContentType()
                            .isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)).isTrue();
                }
            }

            // Assert
            assertThat(sexto).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
        }

        @Test
        @DisplayName("Sexta solicitud de reset con el mismo email en un minuto → 429 idéntico")
        void reset_shouldResponder429_cuandoExcedeCincoPorMinuto() {
            // Arrange
            String email = emailUnico("tasareset");

            // Act: seis solicitudes rápidas (todas 202 idénticas hasta el límite)
            int sexto = -1;
            for (int i = 0; i < 6; i++) {
                var respuesta = post("/v1/auth/restablecer-contrasena", Map.of("email", email));
                if (i < 5) {
                    assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
                } else {
                    sexto = respuesta.getStatusCode().value();
                }
            }

            // Assert
            assertThat(sexto).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
        }
    }

    @Nested
    @DisplayName("Seguridad de rutas")
    class SeguridadRutas {

        @Test
        @DisplayName("Sin token: /me responde 401 problem+json indistinguible")
        void perfil_shouldResponder401_cuandoSinToken() {
            // Act
            var respuesta = get("/v1/usuarios/me");

            // Assert
            assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(respuesta.getHeaders().getContentType()
                    .isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)).isTrue();
        }

        @Test
        @DisplayName("El cuerpo del 401 de login no revela si la cuenta existe (R13)")
        void login_shouldOcultarLaCausa_enElCuerpo() {
            // Act
            var respuesta = login(emailUnico("oculta"), "secreta123");

            // Assert
            Matcher m = TOKEN_EN_CUERPO.matcher("");
            assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(respuesta.getBody().toString()).doesNotContain("existe", "habilitad", "confirma");
            assertThat(m.reset(respuesta.getBody().toString()).find()).isFalse();
        }
    }
}
