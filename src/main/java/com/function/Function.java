package com.function;

import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.functions.HttpMethod;
import com.microsoft.azure.functions.HttpRequestMessage;
import com.microsoft.azure.functions.HttpResponseMessage;
import com.microsoft.azure.functions.HttpStatus;
import com.microsoft.azure.functions.annotation.AuthorizationLevel;
import com.microsoft.azure.functions.annotation.FunctionName;
import com.microsoft.azure.functions.annotation.HttpTrigger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;

public class Function {

    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    @FunctionName("Citas")
    public HttpResponseMessage run(
            @HttpTrigger(name = "req", methods = {HttpMethod.POST},
                    authLevel = AuthorizationLevel.ANONYMOUS,
                    route = "citas") HttpRequestMessage<Optional<String>> request,
            final ExecutionContext context) {

        context.getLogger().info("Function Citas ejecutada.");

        try {
            // ==========================================
            // 1. Validar que exista body
            // ==========================================

            if (request.getBody().isEmpty() || request.getBody().get().isBlank()) {

                return request.createResponseBuilder(HttpStatus.BAD_REQUEST)
                        .body("El cuerpo de la solicitud es obligatorio.").build();
            }

            String body = request.getBody().get();

            // ==========================================
            // 2. Obtener valores del JSON
            // ==========================================

            String fechaCita = obtenerValorString(body, "fechaCita");
            Long idUsuario = obtenerValorLong(body, "idUsuario");
            Long idCliente = obtenerValorLong(body, "idCliente");
            Long idMascota = obtenerValorLong(body, "idMascota");
            String estado = obtenerValorString(body, "estado");

            // ==========================================
            // 3. Validaciones
            // ==========================================

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

            // ==========================================
            // 4. Convertir fecha
            // ==========================================

            LocalDateTime fecha;

            try {

                fecha = LocalDateTime.parse(fechaCita, DATE_FORMATTER);

            } catch (DateTimeParseException e) {

                return respuestaError(request,
                        "El formato de fechaCita debe ser dd/MM/yyyy HH:mm.");
            }

            // ==========================================
            // 5. Validar estado
            // ==========================================

            if (!estado.equals("BLOQUEADA") && !estado.equals("CONFIRMADA")
                    && !estado.equals("CANCELADA")) {

                return respuestaError(request,
                        "El estado debe ser BLOQUEADA, CONFIRMADA o CANCELADA.");
            }

            // ==========================================
            // 6. Insertar en Oracle
            // ==========================================

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
                    PreparedStatement statement = connection.prepareStatement(sql)) {

                statement.setTimestamp(1, Timestamp.valueOf(fecha));
                statement.setLong(2, idUsuario);
                statement.setLong(3, idCliente);
                statement.setLong(4, idMascota);
                statement.setString(5, estado);

                int filasAfectadas = statement.executeUpdate();

                if (filasAfectadas != 1) {

                    context.getLogger().severe("No fue posible insertar la cita.");

                    return request.createResponseBuilder(HttpStatus.INTERNAL_SERVER_ERROR)
                            .body("No fue posible crear la cita.").build();
                }

                context.getLogger().info("Cita creada correctamente.");

                // ==========================================
                // 7. Respuesta exitosa
                // ==========================================

                String response = String.format("""
                        {
                          "mensaje": "Cita creada correctamente.",
                          "fechaCita": "%s",
                          "idUsuario": %d,
                          "idCliente": %d,
                          "idMascota": %d,
                          "estado": "%s"
                        }
                        """, fechaCita, idUsuario, idCliente, idMascota, estado);

                return request.createResponseBuilder(HttpStatus.CREATED)
                        .header("Content-Type", "application/json").body(response).build();
            }

        } catch (Exception e) {

            context.getLogger().severe("Error creando cita: " + e.getMessage());

            return request.createResponseBuilder(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error interno al crear la cita.").build();
        }
    }

    // ==================================================
    // Obtener String desde JSON
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
    // Obtener Long desde JSON
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
    // Respuesta de error 400
    // ==================================================

    private HttpResponseMessage respuestaError(HttpRequestMessage<Optional<String>> request,
            String mensaje) {

        return request.createResponseBuilder(HttpStatus.BAD_REQUEST).body(mensaje).build();
    }
}
