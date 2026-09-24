package com.function;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public class OracleConnection {

    private OracleConnection() {}

    public static Connection getConnection() throws SQLException {

        String tnsAlias = System.getenv("ORACLE_TNS_ALIAS");
        String username = System.getenv("ORACLE_USER");
        String password = System.getenv("ORACLE_PASSWORD");
        String tnsAdmin = System.getenv("TNS_ADMIN");

        System.out.println("===== CONFIGURACION ORACLE =====");
        System.out.println("ORACLE_TNS_ALIAS = " + tnsAlias);
        System.out.println("ORACLE_USER      = " + username);
        System.out.println("TNS_ADMIN        = " + tnsAdmin);
        System.out.println(
                "ORACLE_PASSWORD  = " + (password != null ? "CONFIGURADA" : "NO CONFIGURADA"));
        System.out.println("================================");

        if (tnsAlias == null || username == null || password == null || tnsAdmin == null) {

            throw new SQLException("Faltan variables de configuración de Oracle.");
        }

        System.setProperty("oracle.net.tns_admin", tnsAdmin);

        String url = "jdbc:oracle:thin:@" + tnsAlias;

        System.out.println("JDBC URL = " + url);
        System.out.println(
                "TNS_ADMIN System Property = " + System.getProperty("oracle.net.tns_admin"));

        return DriverManager.getConnection(url, username, password);
    }
}
