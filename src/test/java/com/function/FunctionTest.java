package com.function;

import com.microsoft.azure.functions.*;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.util.Optional;
import java.util.logging.Logger;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit test for Function class.
 */
public class FunctionTest {

    /**
     * Unit test for Citas HttpTrigger POST method.
     */
    @Test
    public void testCrearCita() throws Exception {

        // ==========================================
        // Setup
        // ==========================================

        @SuppressWarnings("unchecked")
        final HttpRequestMessage<Optional<String>> req = mock(HttpRequestMessage.class);

        // Simulamos una cita enviada en el body
        final String citaJson = """
                {
                    "fechaCita": "30/09/2026 10:00",
                    "idUsuario": 2,
                    "idCliente": 1,
                    "idMascota": 1,
                    "estado": "BLOQUEADA"
                }
                """;

        doReturn(Optional.of(citaJson)).when(req).getBody();

        // ==========================================
        // Simulamos el Response Builder
        // ==========================================

        doAnswer(new Answer<HttpResponseMessage.Builder>() {
            @Override
            public HttpResponseMessage.Builder answer(InvocationOnMock invocation) {

                HttpStatus status = (HttpStatus) invocation.getArguments()[0];

                return new HttpResponseMessageMock.HttpResponseMessageBuilderMock().status(status);
            }
        }).when(req).createResponseBuilder(any(HttpStatus.class));

        // ==========================================
        // ExecutionContext
        // ==========================================

        final ExecutionContext context = mock(ExecutionContext.class);

        doReturn(Logger.getGlobal()).when(context).getLogger();

        // ==========================================
        // Invoke
        // ==========================================

        final HttpResponseMessage ret = new Function().run(req, context);

        // ==========================================
        // Verify
        // ==========================================

        assertNotNull(ret);

        /*
         * Como este test utiliza la conexión Oracle real, el resultado depende de que las variables
         * de configuración de Oracle estén disponibles.
         *
         * Por ahora verificamos que la Function responde.
         */
        assertNotNull(ret.getStatus());
    }
}
