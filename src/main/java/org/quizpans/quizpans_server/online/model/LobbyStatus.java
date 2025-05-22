package org.quizpans.quizpans_server.online.model;

public enum LobbyStatus {
    AVAILABLE, // Lobby jest wolne i czeka na hosta do konfiguracji lub na graczy, jeśli już skonfigurowane, a niepełne
    BUSY       // Lobby jest konfigurowane przez hosta, pełne, lub gra jest w toku
}