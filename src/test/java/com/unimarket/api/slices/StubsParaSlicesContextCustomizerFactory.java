package com.unimarket.api.slices;

import com.unimarket.api.admin.ServicioAdminCategorias;
import com.unimarket.api.catalog.ServicioAnuncios;
import com.unimarket.api.catalog.ServicioCatalogo;
import com.unimarket.api.catalog.ServicioRevision;
import com.unimarket.api.identity.LimitadorTasa;
import com.unimarket.api.identity.ServicioAdministracionUsuarios;
import com.unimarket.api.messaging.ServicioConversaciones;
import com.unimarket.api.identity.ServicioPerfil;
import com.unimarket.api.identity.ServicioRecuperacion;
import com.unimarket.api.identity.ServicioRegistro;
import com.unimarket.api.identity.ServicioSesion;
import com.unimarket.api.media.AlmacenObjetos;
import org.mockito.Mockito;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.test.context.ContextConfigurationAttributes;
import org.springframework.test.context.ContextCustomizer;
import org.springframework.test.context.ContextCustomizerFactory;

import java.util.List;
import java.util.function.Supplier;

/**
 * Stubs globales para slices {@code @WebMvcTest} (solo classpath de test, vía
 * {@code META-INF/spring.factories}).
 *
 * <p>Un {@code @WebMvcTest} sin lista explícita escanea TODOS los controladores (la base
 * es el paquete de la aplicación), pero no sus servicios; sin stubs esos slices genéricos
 * (p. ej. {@code ApiExceptionHandlerTest}) no arrancan. Esta factoría registra dobles
 * (mocks de servicios, {@link LimitadorTasa} real) SOLO cuando el servicio falta: en el
 * contexto completo los beans reales ya existen y no se toca nada; un
 * {@code @MockitoBean} del test gana siempre a estos stubs.
 *
 * <p>CONVENCIÓN: cada módulo que agregue controladores con dependencias de servicio debe
 * registrar aquí sus stubs.
 */
public class StubsParaSlicesContextCustomizerFactory implements ContextCustomizerFactory {

    @Override
    public ContextCustomizer createContextCustomizer(
            Class<?> testClass, List<ContextConfigurationAttributes> configAttributes) {
        return new StubsParaSlices();
    }

    static class StubsParaSlices implements ContextCustomizer {

        /** Marcador que el bootstrapper de slice deja en las propiedades del contexto. */
        private static final String MARCA_WEBMVC_TEST =
                "org.springframework.boot.webmvc.test.autoconfigure.WebMvcTestContextBootstrapper=true";

        @Override
        public void customizeContext(ConfigurableApplicationContext context,
                org.springframework.test.context.MergedContextConfiguration mergedConfig) {
            if (!(context instanceof GenericApplicationContext generico) || !esSliceWeb(mergedConfig)) {
                return;
            }
            registrarSiFalta(generico, ServicioRegistro.class, () -> Mockito.mock(ServicioRegistro.class));
            registrarSiFalta(generico, ServicioSesion.class, () -> Mockito.mock(ServicioSesion.class));
            registrarSiFalta(generico, ServicioRecuperacion.class, () -> Mockito.mock(ServicioRecuperacion.class));
            registrarSiFalta(generico, ServicioPerfil.class, () -> Mockito.mock(ServicioPerfil.class));
            registrarSiFalta(generico, ServicioAnuncios.class, () -> Mockito.mock(ServicioAnuncios.class));
            registrarSiFalta(generico, ServicioCatalogo.class, () -> Mockito.mock(ServicioCatalogo.class));
            registrarSiFalta(generico, ServicioRevision.class, () -> Mockito.mock(ServicioRevision.class));
            registrarSiFalta(generico, ServicioConversaciones.class, () -> Mockito.mock(ServicioConversaciones.class));
            registrarSiFalta(generico, ServicioAdministracionUsuarios.class, () -> Mockito.mock(ServicioAdministracionUsuarios.class));
            registrarSiFalta(generico, ServicioAdminCategorias.class, () -> Mockito.mock(ServicioAdminCategorias.class));
            registrarSiFalta(generico, LimitadorTasa.class, LimitadorTasa::new);
            registrarSiFalta(generico, AlmacenObjetos.class, () -> Mockito.mock(AlmacenObjetos.class));
        }

        /**
         * Solo los slices web necesitan stubs: escanean todos los controladores sin sus
         * servicios. El contexto completo (y los demás slices) se dejan intactos para que
         * los beans reales —registrados durante el refresh, después de este punto— no se
         * dupliquen.
         */
        private static boolean esSliceWeb(org.springframework.test.context.MergedContextConfiguration mergedConfig) {
            for (String propiedad : mergedConfig.getPropertySourceProperties()) {
                if (MARCA_WEBMVC_TEST.equals(propiedad)) {
                    return true;
                }
            }
            return false;
        }

        private static <T> void registrarSiFalta(
                GenericApplicationContext contexto, Class<T> tipo, Supplier<T> fabrica) {
            if (contexto.getBeanFactory().getBeanNamesForType(tipo, true, false).length == 0) {
                contexto.registerBean(tipo, fabrica);
            }
        }

        @Override
        public boolean equals(Object otro) {
            return otro instanceof StubsParaSlices;
        }

        @Override
        public int hashCode() {
            return StubsParaSlices.class.hashCode();
        }
    }
}
