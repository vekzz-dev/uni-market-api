package com.unimarket.api.shared.error;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.net.URI;
import java.util.stream.Collectors;

/**
 * Handler global de errores de la API: traduce las excepciones a
 * {@code application/problem+json} (RFC 9457) sin stack traces, con el split único
 * {@code 400} estructural vs {@code 422} de regla de negocio (SDD §6). {@code instance}
 * es la URI del request; el fallback {@code 500} responde siempre un detalle genérico.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /** Detalle del fallback: nunca deriva del mensaje ni del tipo de la excepción. */
    static final String DETALLE_500 = "Ocurrió un error interno; intenta de nuevo más tarde.";

    private static final String TITULO_400 = "Solicitud inválida";
    private static final String TITULO_401 = "No autenticado";
    private static final String TITULO_403 = "Acceso denegado";
    private static final String TITULO_500 = "Error interno";

    /** Errores de dominio: estado y título los fija la excepción, el detalle es su mensaje. */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ProblemDetail> manejarApiException(ApiException excepcion, HttpServletRequest solicitud) {
        return ResponseEntity.status(excepcion.estado())
                .body(problema(excepcion.estado(), excepcion.titulo(), excepcion.getMessage(), solicitud));
    }

    /** Bean Validation sobre el DTO: 400 con los campos inválidos unidos en el detalle. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> manejarValidacion(MethodArgumentNotValidException excepcion,
                                                           HttpServletRequest solicitud) {
        String detalle = excepcion.getBindingResult().getFieldErrors().stream()
                .map(ApiExceptionHandler::describirCampo)
                .collect(Collectors.joining("; "));
        if (detalle.isBlank()) {
            detalle = "Revisa los datos enviados.";
        }
        return ResponseEntity.badRequest().body(problema(HttpStatus.BAD_REQUEST, TITULO_400, detalle, solicitud));
    }

    /** JSON malformado o ilegible → 400 estructural (SDD §6). */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> manejarCuerpoIlegible(HttpMessageNotReadableException excepcion,
                                                               HttpServletRequest solicitud) {
        return ResponseEntity.badRequest().body(
                problema(HttpStatus.BAD_REQUEST, TITULO_400, "El cuerpo de la solicitud está malformado.", solicitud));
    }

    /** Parámetro con valor fuera del dominio o del tipo esperado → 400 estructural. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ProblemDetail> manejarParametroInvalido(MethodArgumentTypeMismatchException excepcion,
                                                                  HttpServletRequest solicitud) {
        String detalle = "El parámetro '" + excepcion.getName() + "' tiene un valor inválido.";
        return ResponseEntity.badRequest().body(problema(HttpStatus.BAD_REQUEST, TITULO_400, detalle, solicitud));
    }

    /** Autorización a nivel de método ({@code @PreAuthorize}): autenticado sin permisos → 403. */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> manejarAccesoDenegado(AccessDeniedException excepcion,
                                                               HttpServletRequest solicitud) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                problema(HttpStatus.FORBIDDEN, TITULO_403, "No tienes permisos para realizar esta acción.", solicitud));
    }

    /** Sesión ausente o inválida en la capa MVC → 401 genérico e indistinguible (R13). */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ProblemDetail> manejarNoAutenticado(AuthenticationException excepcion,
                                                              HttpServletRequest solicitud) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
                problema(HttpStatus.UNAUTHORIZED, TITULO_401, "Credenciales inválidas o sesión no vigente.", solicitud));
    }

    /** Ruta no mapeada (sin handler): 404, jamás un 500 del fallback. */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ProblemDetail> manejarRutaNoEncontrada(NoResourceFoundException excepcion,
                                                                  HttpServletRequest solicitud) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                problema(HttpStatus.NOT_FOUND, "Recurso no encontrado", "El recurso solicitado no existe.", solicitud));
    }

    /** Parámetro o parte de multipart obligatoria ausente → 400 estructural (openapi). */
    @ExceptionHandler(ServletRequestBindingException.class)
    public ResponseEntity<ProblemDetail> manejarEnlaceInvalido(ServletRequestBindingException excepcion,
                                                               HttpServletRequest solicitud) {
        return ResponseEntity.badRequest().body(
                problema(HttpStatus.BAD_REQUEST, TITULO_400, "Falta un dato obligatorio de la solicitud.", solicitud));
    }

    /** Solicitud multipart ilegible (p. ej. el contenedor rechaza el tamaño) → 400 estructural. */
    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<ProblemDetail> manejarMultipartInvalido(MultipartException excepcion,
                                                                  HttpServletRequest solicitud) {
        return ResponseEntity.badRequest().body(
                problema(HttpStatus.BAD_REQUEST, TITULO_400, "La solicitud multipart no es válida.", solicitud));
    }

    /**
     * Fallback: cualquier excepción no controlada → 500 con detalle genérico. La excepción
     * se registra en el servidor, jamás viaja al cliente (ni mensaje ni stack trace).
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> manejarNoControlado(Exception excepcion, HttpServletRequest solicitud) {
        log.error("Error no controlado en {} {}", solicitud.getMethod(), solicitud.getRequestURI(), excepcion);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                problema(HttpStatus.INTERNAL_SERVER_ERROR, TITULO_500, DETALLE_500, solicitud));
    }

    private static ProblemDetail problema(HttpStatus estado, String titulo, String detalle, HttpServletRequest solicitud) {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(estado, detalle);
        problema.setTitle(titulo);
        problema.setInstance(URI.create(solicitud.getRequestURI()));
        return problema;
    }

    private static String describirCampo(FieldError error) {
        return error.getField() + ": " + error.getDefaultMessage();
    }
}
