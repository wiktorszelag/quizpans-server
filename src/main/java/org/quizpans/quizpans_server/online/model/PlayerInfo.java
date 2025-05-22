package org.quizpans.quizpans_server.online.model;

public record PlayerInfo(
        String sessionId,
        String nickname,
        String teamName
) {}