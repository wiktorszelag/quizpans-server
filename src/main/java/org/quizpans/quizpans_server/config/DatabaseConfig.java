package org.quizpans.quizpans_server.config;

public class DatabaseConfig {
    private static final String URL = "jdbc:mysql://207.180.228.112:3306/mieszane?useUnicode=true&characterEncoding=UTF-8&useSSL=false&allowPublicKeyRetrieval=true"; // Zaktualizowany URL
    private static final String USER = "gierka_user";
    private static final String PASSWORD = "DSA42$312316%4£sa33";

    public static String getUrl() {
        return URL;
    }

    public static String getUser() {
        return USER;
    }

    public static String getPassword() {
        return PASSWORD;
    }
}