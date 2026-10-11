package com.unimarket.api.architecture;

import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.BufferedReader;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Guardas del contrato OpenAPI (raíz del repo), 100 % Java y dentro de `mvn verify`:
 *
 * <ol>
 *   <li><b>Estructura</b>: parseo con swagger-parser resolviendo {@code $ref}s — una spec
 *       inválida o una referencia rota hace fallar el build.</li>
 *   <li><b>Claves duplicadas</b>: los parsers YAML por defecto sobrescriben duplicados en
 *       silencio ("gana la última"); SnakeYAML estricto los prohíbe (caso real: el
 *       duplicado de {@code CategoriaId} que destapó el lint antes de retirarse).</li>
 * </ol>
 */
class OpenApiContractTest {

    private static final Path SPEC = Path.of("openapi.yaml");

    @Test
    void openapiSpecShouldBeStructurallyValidWithResolvableRefs() {
        assertThat(SPEC)
                .as("El contrato vive en la raíz del repositorio y es fuente de verdad (PRD O6)")
                .exists();

        ParseOptions options = new ParseOptions();
        options.setResolve(true);

        SwaggerParseResult result = new OpenAPIV3Parser().readLocation(SPEC.toString(), null, options);

        assertThat(result.getMessages())
                .as("openapi.yaml debe ser válido y todos sus $ref deben resolverse; mensajes del parser")
                .isEmpty();
        assertThat(result.getOpenAPI())
                .as("El parser debe producir un documento OpenAPI válido")
                .isNotNull();
        assertThat(result.getOpenAPI().getPaths())
                .as("El contrato declara los recursos publicados bajo /v1")
                .isNotEmpty();
    }

    @Test
    void openapiSpecShouldHaveNoDuplicateKeys() {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        Yaml yaml = new Yaml(options);

        assertThatCode(() -> {
            try (BufferedReader reader = Files.newBufferedReader(SPEC)) {
                yaml.load(reader);
            }
        }).as("openapi.yaml no puede contener claves YAML duplicadas").doesNotThrowAnyException();
    }
}
