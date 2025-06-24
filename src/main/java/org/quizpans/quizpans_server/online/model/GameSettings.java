package org.quizpans.quizpans_server.online.model;
//ustawienia gry
public record GameSettings(
        String category,
        int answerTime,
        int numberOfRounds,
        int maxPlayersPerTeam,
        String teamBlueName,
        String teamRedName
) {
    public GameSettings() {
        this(null, 30, 5, 3, "Niebiescy", "Czerwoni");
    }

    public GameSettings(String category, int answerTime, int numberOfRounds, int maxPlayersPerTeam) {
        this(category, answerTime, numberOfRounds, maxPlayersPerTeam, "Niebiescy", "Czerwoni");
    }
}