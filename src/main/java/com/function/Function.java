package com.function;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import com.azure.core.credential.AzureKeyCredential;
import com.azure.core.util.BinaryData;
import com.azure.messaging.eventgrid.EventGridEvent;
import com.azure.messaging.eventgrid.EventGridPublisherClient;
import com.azure.messaging.eventgrid.EventGridPublisherClientBuilder;
import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.functions.HttpMethod;
import com.microsoft.azure.functions.HttpRequestMessage;
import com.microsoft.azure.functions.HttpResponseMessage;
import com.microsoft.azure.functions.HttpStatus;
import com.microsoft.azure.functions.annotation.AuthorizationLevel;
import com.microsoft.azure.functions.annotation.BindingName;
import com.microsoft.azure.functions.annotation.FunctionName;
import com.microsoft.azure.functions.annotation.HttpTrigger;

public class Function {

    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    // ==================================================
    // CREAR CITA
    // POST /api/citas
    // ==================================================

    @FunctionName("Citas")
    public HttpResponseMessage crearCita(
            @HttpTrigger(name = "req", methods = {HttpMethod.POST},
                    authLevel = AuthorizationLevel.ANONYMOUS,
                    route = "citas") HttpRequestMessage<Optional<String>> request,

            final ExecutionContext context) {

        context.getLogger().info("Function Citas ejecutada.");

        try {

            // ==================================================
            // VALIDAR BODY
            // ==================================================

            if (request.getBody().isEmpty() || request.getBody().get().isBlank()) {

                return request.createResponseBuilder(HttpStatus.BAD_REQUEST)
                        .body("El cuerpo de la solicitud es obligatorio.").build();
            }

            String body = request.getBody().get();

            // ==================================================
            // OBTENER DATOS
            // ==================================================

            String fechaCita = obtenerValorString(body, "fechaCita");
            Long idUsuario = obtenerValorLong(body, "idUsuario");
            Long idCliente = obtenerValorLong(body, "idCliente");
            Long idMascota = obtenerValorLong(body, "idMascota");
            String estado = obtenerValorString(body, "estado");

            // ==================================================
            // VALIDACIONES
            // ==================================================

            if (fechaCita == null || fechaCita.isBlank()) {
                return respuestaError(request, "El campo fechaCita es obligatorio.");
            }

            if (idUsuario == null) {
                return respuestaError(request,
                        "El campo idUsuario es obligatorio y debe ser numérico.");
            }

            if (idCliente == null) {
                return respuestaError(request,
                        "El campo idCliente es obligatorio y debe ser numérico.");
            }

            if (idMascota == null) {
                return respuestaError(request,
                        "El campo idMascota es obligatorio y debe ser numérico.");
            }

            if (estado == null || estado.isBlank()) {
                return respuestaError(request, "El campo estado es obligatorio.");
            }

            // ==================================================
            // CONVERTIR FECHA
            // ==================================================

            LocalDateTime fecha;

            try {

                fecha = LocalDateTime.parse(fechaCita, DATE_FORMATTER);

            } catch (DateTimeParseException e) {

                return respuestaError(request,
                        "El formato de fechaCita debe ser dd/MM/yyyy HH:mm.");
            }

            // ==================================================
            // VALIDAR ESTADO
            // ==================================================

            if (!estado.equals("BLOQUEADA") && !estado.equals("CONFIRMADA")
                    && !estado.equals("CANCELADA")) {

                return respuestaError(request,
                        "El estado debe ser BLOQUEADA, CONFIRMADA o CANCELADA.");
            }

            // ==================================================
            // INSERTAR EN ORACLE
            // ==================================================

            String sql = """
                    INSERT INTO FULLSTACK.CITAS
                    (
                        FECHA_CITA,
                        ID_USUARIO,
                        ID_CLIENTE,
                        ID_MASCOTA,
                        ESTADO
                    )
                    VALUES (?, ?, ?, ?, ?)
                    """;

            try (Connection connection = OracleConnection.getConnection();

                    PreparedStatement statement =
                            connection.prepareStatement(sql, new String[] {"ID_CITA"})) {

                statement.setTimestamp(1, Timestamp.valueOf(fecha));

                statement.setLong(2, idUsuario);

                statement.setLong(3, idCliente);

                statement.setLong(4, idMascota);

                statement.setString(5, estado);

                int filasAfectadas = statement.executeUpdate();

                if (filasAfectadas != 1) {

                    return request.createResponseBuilder(HttpStatus.INTERNAL_SERVER_ERROR)
                            .body("No fue posible crear la cita.").build();
                }

                // ==================================================
                // OBTENER ID GENERADO
                // ==================================================

                long nuevoIdCita = 0;

                try (ResultSet generatedKeys = statement.getGeneratedKeys()) {

                    if (generatedKeys.next()) {

                        nuevoIdCita = generatedKeys.getLong(1);
                    }
                }

                context.getLogger()
                        .info("Cita creada correctamente en Oracle. ID_CITA=" + nuevoIdCita);

                // ==================================================
                // PUBLICAR CitaCreada
                // ==================================================

                publicarEventoCitaCreada(nuevoIdCita, fechaCita, idUsuario, idCliente, idMascota,
                        estado, context);

                // ==================================================
                // RESPUESTA
                // ==================================================

                String response = String.format("""
                        {
                          "mensaje": "Cita creada correctamente.",
                          "idCita": %d,
                          "fechaCita": "%s",
                          "idUsuario": %d,
                          "idCliente": %d,
                          "idMascota": %d,
                          "estado": "%s"
                        }
                        """, nuevoIdCita, fechaCita, idUsuario, idCliente, idMascota, estado);

                return request.createResponseBuilder(HttpStatus.CREATED)
                        .header("Content-Type", "application/json").body(response).build();
            }

        } catch (Exception e) {

            context.getLogger().severe("Error procesando cita: " + e.getMessage());

            return request.createResponseBuilder(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error interno al procesar la cita.").build();
        }
    }

    // ==================================================
    // CONFIRMAR / CANCELAR CITA
    // PUT /api/citas/{idcita}/{accion}
    // ==================================================

    @FunctionName("CitasAccion")
    public HttpResponseMessage ejecutarAccionCita(
            @HttpTrigger(name = "req", methods = {HttpMethod.PUT},
                    authLevel = AuthorizationLevel.ANONYMOUS,
                    route = "citas/{idcita}/{accion}") HttpRequestMessage<Optional<String>> request,

            @BindingName("idcita") String idCita,

            @BindingName("accion") String accion,

            final ExecutionContext context) {

        context.getLogger().info("Function CitasAccion ejecutada.");

        try {

            // ==================================================
            // VALIDAR ID
            // ==================================================

            if (idCita == null || idCita.isBlank()) {

                return request.createResponseBuilder(HttpStatus.BAD_REQUEST)
                        .body("El idCita es obligatorio.").build();
            }

            // ==================================================
            // VALIDAR ACCIÓN
            // ==================================================

            if (accion == null || accion.isBlank()) {

                return request.createResponseBuilder(HttpStatus.BAD_REQUEST)
                        .body("La acción es obligatoria.").build();
            }

            // ==================================================
            // CONVERTIR ID
            // ==================================================

            long id;

            try {

                id = Long.parseLong(idCita);

            } catch (NumberFormatException e) {

                return request.createResponseBuilder(HttpStatus.BAD_REQUEST)
                        .body("El idCita debe ser numérico.").build();
            }

            // ==================================================
            // CONFIRMAR
            // ==================================================

            if ("confirmar".equalsIgnoreCase(accion)) {

                return confirmarCita(request, context, id);
            }

            // ==================================================
            // CANCELAR
            // ==================================================

            if ("cancelar".equalsIgnoreCase(accion)) {

                return cancelarCita(request, context, id);
            }

            // ==================================================
            // ACCIÓN NO VÁLIDA
            // ==================================================

            return request.createResponseBuilder(HttpStatus.NOT_FOUND)
                    .body("Acción no válida. Use confirmar o cancelar.").build();

        } catch (Exception e) {

            context.getLogger().severe("Error procesando acción de cita: " + e.getMessage());

            return request.createResponseBuilder(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error interno al procesar la acción de la cita.").build();
        }
    }

    // ==================================================
    // CONFIRMAR CITA
    // ==================================================

    private HttpResponseMessage confirmarCita(HttpRequestMessage<Optional<String>> request,
            ExecutionContext context, long idCita) {

        String selectSql = """
                SELECT FECHA_CITA,
                       ID_USUARIO,
                       ID_CLIENTE,
                       ID_MASCOTA,
                       ESTADO
                FROM FULLSTACK.CITAS
                WHERE ID_CITA = ?
                """;

        try (Connection connection = OracleConnection.getConnection();

                PreparedStatement select = connection.prepareStatement(selectSql)) {

            select.setLong(1, idCita);

            try (ResultSet rs = select.executeQuery()) {

                if (!rs.next()) {

                    return request.createResponseBuilder(HttpStatus.NOT_FOUND)
                            .body("La cita no existe.").build();
                }

                Timestamp fechaTimestamp = rs.getTimestamp("FECHA_CITA");

                long idUsuario = rs.getLong("ID_USUARIO");

                long idCliente = rs.getLong("ID_CLIENTE");

                long idMascota = rs.getLong("ID_MASCOTA");

                String estadoActual = rs.getString("ESTADO");

                // ==================================================
                // VALIDAR TRANSICIÓN
                // ==================================================

                if ("CONFIRMADA".equals(estadoActual)) {

                    return request.createResponseBuilder(HttpStatus.CONFLICT)
                            .body("La cita ya está confirmada.").build();
                }

                if ("CANCELADA".equals(estadoActual)) {

                    return request.createResponseBuilder(HttpStatus.CONFLICT)
                            .body("No se puede confirmar una cita cancelada.").build();
                }

                if (!"BLOQUEADA".equals(estadoActual)) {

                    return request.createResponseBuilder(HttpStatus.CONFLICT)
                            .body("La cita debe estar BLOQUEADA para confirmarse.").build();
                }

                // ==================================================
                // UPDATE
                // ==================================================

                String updateSql = """
                        UPDATE FULLSTACK.CITAS
                        SET ESTADO = 'CONFIRMADA'
                        WHERE ID_CITA = ?
                        """;

                try (PreparedStatement update = connection.prepareStatement(updateSql)) {

                    update.setLong(1, idCita);

                    int filas = update.executeUpdate();

                    if (filas != 1) {

                        return request.createResponseBuilder(HttpStatus.INTERNAL_SERVER_ERROR)
                                .body("No fue posible confirmar la cita.").build();
                    }
                }

                String fechaCita = fechaTimestamp.toLocalDateTime().format(DATE_FORMATTER);

                context.getLogger()
                        .info("Cita confirmada correctamente en Oracle. ID_CITA=" + idCita);

                // ==================================================
                // PUBLICAR CitaConfirmada
                // ==================================================

                publicarEventoCitaConfirmada(idCita, fechaCita, idUsuario, idCliente, idMascota,
                        context);

                // ==================================================
                // RESPUESTA
                // ==================================================

                String response = String.format("""
                        {
                          "mensaje": "Cita confirmada correctamente.",
                          "idCita": %d,
                          "fechaCita": "%s",
                          "idUsuario": %d,
                          "idCliente": %d,
                          "idMascota": %d,
                          "estado": "CONFIRMADA"
                        }
                        """, idCita, fechaCita, idUsuario, idCliente, idMascota);

                return request.createResponseBuilder(HttpStatus.OK)
                        .header("Content-Type", "application/json").body(response).build();
            }

        } catch (Exception e) {

            context.getLogger().severe("Error confirmando cita: " + e.getMessage());

            return request.createResponseBuilder(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error interno al confirmar la cita.").build();
        }
    }

    // ==================================================
    // CANCELAR CITA
    // ==================================================

    private HttpResponseMessage cancelarCita(HttpRequestMessage<Optional<String>> request,
            ExecutionContext context, long idCita) {

        String selectSql = """
                SELECT FECHA_CITA,
                       ID_USUARIO,
                       ID_CLIENTE,
                       ID_MASCOTA,
                       ESTADO
                FROM FULLSTACK.CITAS
                WHERE ID_CITA = ?
                """;

        try (Connection connection = OracleConnection.getConnection();

                PreparedStatement select = connection.prepareStatement(selectSql)) {

            select.setLong(1, idCita);

            try (ResultSet rs = select.executeQuery()) {

                if (!rs.next()) {

                    return request.createResponseBuilder(HttpStatus.NOT_FOUND)
                            .body("La cita no existe.").build();
                }

                Timestamp fechaTimestamp = rs.getTimestamp("FECHA_CITA");

                long idUsuario = rs.getLong("ID_USUARIO");

                long idCliente = rs.getLong("ID_CLIENTE");

                long idMascota = rs.getLong("ID_MASCOTA");

                String estadoActual = rs.getString("ESTADO");

                // ==================================================
                // VALIDAR TRANSICIÓN
                // ==================================================

                if ("CANCELADA".equals(estadoActual)) {

                    return request.createResponseBuilder(HttpStatus.CONFLICT)
                            .body("La cita ya está cancelada.").build();
                }

                if (!"BLOQUEADA".equals(estadoActual) && !"CONFIRMADA".equals(estadoActual)) {

                    return request.createResponseBuilder(HttpStatus.CONFLICT)
                            .body("La cita no puede ser cancelada en su estado actual.").build();
                }

                // ==================================================
                // UPDATE
                // ==================================================

                String updateSql = """
                        UPDATE FULLSTACK.CITAS
                        SET ESTADO = 'CANCELADA'
                        WHERE ID_CITA = ?
                        """;

                try (PreparedStatement update = connection.prepareStatement(updateSql)) {

                    update.setLong(1, idCita);

                    int filas = update.executeUpdate();

                    if (filas != 1) {

                        return request.createResponseBuilder(HttpStatus.INTERNAL_SERVER_ERROR)
                                .body("No fue posible cancelar la cita.").build();
                    }
                }

                String fechaCita = fechaTimestamp.toLocalDateTime().format(DATE_FORMATTER);

                context.getLogger()
                        .info("Cita cancelada correctamente en Oracle. ID_CITA=" + idCita);

                // ==================================================
                // PUBLICAR CitaCancelada
                // ==================================================

                publicarEventoCitaCancelada(idCita, fechaCita, idUsuario, idCliente, idMascota,
                        context);

                // ==================================================
                // RESPUESTA
                // ==================================================

                String response = String.format("""
                        {
                          "mensaje": "Cita cancelada correctamente.",
                          "idCita": %d,
                          "fechaCita": "%s",
                          "idUsuario": %d,
                          "idCliente": %d,
                          "idMascota": %d,
                          "estado": "CANCELADA"
                        }
                        """, idCita, fechaCita, idUsuario, idCliente, idMascota);

                return request.createResponseBuilder(HttpStatus.OK)
                        .header("Content-Type", "application/json").body(response).build();
            }

        } catch (Exception e) {

            context.getLogger().severe("Error cancelando cita: " + e.getMessage());

            return request.createResponseBuilder(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error interno al cancelar la cita.").build();
        }
    }

    // ==================================================
    // EVENTO CitaCreada
    // ==================================================

    private void publicarEventoCitaCreada(long idCita, String fechaCita, Long idUsuario,
            Long idCliente, Long idMascota, String estado, ExecutionContext context) {

        String endpoint = System.getenv("EVENT_GRID_TOPIC_ENDPOINT");

        String accessKey = System.getenv("EVENT_GRID_ACCESS_KEY");

        validarConfiguracionEventGrid(endpoint, accessKey);

        String eventData = String.format("""
                {
                  "idCita": %d,
                  "fechaCita": "%s",
                  "idUsuario": %d,
                  "idCliente": %d,
                  "idMascota": %d,
                  "estado": "%s"
                }
                """, idCita, fechaCita, idUsuario, idCliente, idMascota, estado);

        publicarEvento("CitaCreada", eventData, endpoint, accessKey, context);
    }

    // ==================================================
    // EVENTO CitaConfirmada
    // ==================================================

    private void publicarEventoCitaConfirmada(long idCita, String fechaCita, long idUsuario,
            long idCliente, long idMascota, ExecutionContext context) {

        String endpoint = System.getenv("EVENT_GRID_TOPIC_ENDPOINT");

        String accessKey = System.getenv("EVENT_GRID_ACCESS_KEY");

        validarConfiguracionEventGrid(endpoint, accessKey);

        String eventData = String.format("""
                {
                  "idCita": %d,
                  "fechaCita": "%s",
                  "idUsuario": %d,
                  "idCliente": %d,
                  "idMascota": %d,
                  "estado": "CONFIRMADA"
                }
                """, idCita, fechaCita, idUsuario, idCliente, idMascota);

        publicarEvento("CitaConfirmada", eventData, endpoint, accessKey, context);
    }

    // ==================================================
    // EVENTO CitaCancelada
    // ==================================================

    private void publicarEventoCitaCancelada(long idCita, String fechaCita, long idUsuario,
            long idCliente, long idMascota, ExecutionContext context) {

        String endpoint = System.getenv("EVENT_GRID_TOPIC_ENDPOINT");

        String accessKey = System.getenv("EVENT_GRID_ACCESS_KEY");

        validarConfiguracionEventGrid(endpoint, accessKey);

        String eventData = String.format("""
                {
                  "idCita": %d,
                  "fechaCita": "%s",
                  "idUsuario": %d,
                  "idCliente": %d,
                  "idMascota": %d,
                  "estado": "CANCELADA"
                }
                """, idCita, fechaCita, idUsuario, idCliente, idMascota);

        publicarEvento("CitaCancelada", eventData, endpoint, accessKey, context);
    }

    // ==================================================
    // VALIDAR CONFIGURACIÓN EVENT GRID
    // ==================================================

    private void validarConfiguracionEventGrid(String endpoint, String accessKey) {

        if (endpoint == null || endpoint.isBlank()) {

            throw new IllegalStateException("EVENT_GRID_TOPIC_ENDPOINT no está configurado.");
        }

        if (accessKey == null || accessKey.isBlank()) {

            throw new IllegalStateException("EVENT_GRID_ACCESS_KEY no está configurado.");
        }
    }

    // ==================================================
    // PUBLICAR EVENT GRID
    // ==================================================

    private void publicarEvento(String eventType, String eventData, String endpoint,
            String accessKey, ExecutionContext context) {

        EventGridEvent evento = new EventGridEvent("/veterinaria/citas", eventType,
                BinaryData.fromString(eventData), "1.0");

        EventGridPublisherClient<EventGridEvent> client = new EventGridPublisherClientBuilder()
                .endpoint(endpoint).credential(new AzureKeyCredential(accessKey))
                .buildEventGridEventPublisherClient();

        client.sendEvent(evento);

        context.getLogger().info("Evento " + eventType + " publicado correctamente en Event Grid.");
    }

    // ==================================================
    // OBTENER STRING DESDE JSON
    // ==================================================

    private String obtenerValorString(String json, String campo) {

        String patron = "\"" + campo + "\"\\s*:\\s*\"([^\"]*)\"";

        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(patron);

        java.util.regex.Matcher matcher = pattern.matcher(json);

        if (matcher.find()) {

            return matcher.group(1);
        }

        return null;
    }

    // ==================================================
    // OBTENER LONG DESDE JSON
    // ==================================================

    private Long obtenerValorLong(String json, String campo) {

        String patron = "\"" + campo + "\"\\s*:\\s*(\\d+)";

        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(patron);

        java.util.regex.Matcher matcher = pattern.matcher(json);

        if (matcher.find()) {

            try {

                return Long.parseLong(matcher.group(1));

            } catch (NumberFormatException e) {

                return null;
            }
        }

        return null;
    }

    // ==================================================
    // RESPUESTA ERROR 400
    // ==================================================

    private HttpResponseMessage respuestaError(HttpRequestMessage<Optional<String>> request,
            String mensaje) {

        return request.createResponseBuilder(HttpStatus.BAD_REQUEST).body(mensaje).build();
    }
}
