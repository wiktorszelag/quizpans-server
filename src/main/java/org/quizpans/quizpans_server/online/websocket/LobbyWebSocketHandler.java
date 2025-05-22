package org.quizpans.quizpans_server.online.websocket;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import org.quizpans.quizpans_server.online.model.Lobby;
import org.quizpans.quizpans_server.online.model.GameSettings;
import org.quizpans.quizpans_server.online.model.PlayerInfo;
import org.quizpans.quizpans_server.online.service.LobbyService;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Component
public class LobbyWebSocketHandler extends TextWebSocketHandler {

    private final LobbyService lobbyService;
    private final Gson gson = new GsonBuilder().serializeNulls().create();
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, Long> recentlyJoinedWebPlayers = new ConcurrentHashMap<>();

    public LobbyWebSocketHandler(LobbyService lobbyService) {
        this.lobbyService = lobbyService;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        sessions.put(session.getId(), session);
        Map<String, Object> sessionIdMessage = new HashMap<>();
        sessionIdMessage.put("type", "yourSessionId");
        sessionIdMessage.put("sessionId", session.getId());
        sendMessageToSession(session, sessionIdMessage);
        sendAllLobbiesToOneUser(session);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String payload = message.getPayload();
        try {
            Type type = new TypeToken<Map<String, Object>>(){}.getType();
            Map<String, Object> messageData = gson.fromJson(payload, type);
            String action = (String) messageData.get("action");

            if (action == null) {
                sendError(session, "Brakująca akcja w wiadomości.");
                return;
            }

            String lobbyId;
            Optional<Lobby> updatedLobbyOpt;

            switch (action) {
                case "getAllLobbies":
                    sendAllLobbiesToOneUser(session);
                    break;
                case "requestHostLobby":
                    lobbyId = (String) messageData.get("lobbyId");
                    if (lobbyId != null) {
                        updatedLobbyOpt = lobbyService.hostTakesLobby(lobbyId, session.getId());
                        updatedLobbyOpt.ifPresentOrElse(
                                this::broadcastLobbyUpdate,
                                () -> sendError(session, "Nie udało się przejąć lobby: " + lobbyId)
                        );
                    } else {
                        sendError(session, "Brak lobbyId w żądaniu requestHostLobby.");
                    }
                    break;
                case "configureLobby":
                    lobbyId = (String) messageData.get("lobbyId");
                    Map<String, Object> settingsMap = (Map<String, Object>) messageData.get("gameSettings");
                    GameSettings settings = null;
                    if (settingsMap != null) {
                        settings = gson.fromJson(gson.toJson(settingsMap), GameSettings.class);
                    }
                    String password = (String) messageData.get("password");

                    if (lobbyId != null && settings != null) {
                        updatedLobbyOpt = lobbyService.finalizeLobbyConfiguration(lobbyId, session.getId(), settings, password);
                        updatedLobbyOpt.ifPresentOrElse(
                                this::broadcastLobbyUpdate,
                                () -> sendError(session, "Nie udało się skonfigurować lobby: " + lobbyId)
                        );
                    } else {
                        sendError(session, "Brak lobbyId lub gameSettings w żądaniu configureLobby.");
                    }
                    break;

                case "playerWebJoinRequest":
                    lobbyId = (String) messageData.get("lobbyId");
                    String nickname = (String) messageData.get("nickname");
                    if (lobbyId != null && nickname != null) {
                        PlayerInfo webPlayer = new PlayerInfo(session.getId(), nickname, null);
                        updatedLobbyOpt = lobbyService.addWaitingPlayer(lobbyId, webPlayer);
                        updatedLobbyOpt.ifPresentOrElse(
                                lobby -> {
                                    recentlyJoinedWebPlayers.put(session.getId(), System.currentTimeMillis());
                                    this.broadcastLobbyUpdate(lobby);
                                },
                                () -> sendError(session, "Nie udało się dołączyć do poczekalni lobby " + lobbyId)
                        );
                    } else {
                        sendError(session, "Brak lobbyId lub nickname w żądaniu playerWebJoinRequest.");
                    }
                    break;

                case "assignRole":
                    lobbyId = (String) messageData.get("lobbyId");
                    String participantSessionId = (String) messageData.get("participantSessionId");
                    String role = (String) messageData.get("role");
                    String targetTeamName = (String) messageData.get("targetTeamName");

                    if (lobbyId != null && participantSessionId != null && role != null) {
                        updatedLobbyOpt = lobbyService.assignParticipantRole(lobbyId, session.getId(), participantSessionId, role, targetTeamName);
                        updatedLobbyOpt.ifPresentOrElse(
                                this::broadcastLobbyUpdate,
                                () -> sendError(session, "Nie udało się przypisać roli uczestnikowi " + participantSessionId + " w lobby " + lobbyId)
                        );
                    } else {
                        sendError(session, "Brakujące dane w żądaniu assignRole (lobbyId, participantSessionId, role).");
                    }
                    break;

                case "unassignParticipant":
                    lobbyId = (String) messageData.get("lobbyId");
                    String participantToUnassignSessionId = (String) messageData.get("participantSessionId");
                    if (lobbyId != null && participantToUnassignSessionId != null) {
                        updatedLobbyOpt = lobbyService.unassignParticipant(lobbyId, session.getId(), participantToUnassignSessionId);
                        updatedLobbyOpt.ifPresentOrElse(
                                this::broadcastLobbyUpdate,
                                () -> sendError(session, "Nie udało się przenieść uczestnika " + participantToUnassignSessionId + " do poczekalni w lobby " + lobbyId + ".")
                        );
                    } else {
                        sendError(session, "Brakujące lobbyId lub participantSessionId w żądaniu unassignParticipant.");
                    }
                    break;

                case "leaveLobby":
                    lobbyId = (String) messageData.get("lobbyId");
                    if (lobbyId != null) {
                        Optional<Lobby> lobbyOpt = lobbyService.removePlayerFromLobby(lobbyId, session.getId());
                        lobbyOpt.ifPresentOrElse(
                                this::broadcastLobbyUpdate,
                                () -> System.err.println("WebSocket: Próba opuszczenia nieistniejącego lub nieudanego opuszczenia lobby " + lobbyId + " przez " + session.getId())
                        );
                    } else {
                        sendError(session, "Brak lobbyId w żądaniu leaveLobby.");
                    }
                    break;

                case "removeParticipantFromLobby":
                    lobbyId = (String) messageData.get("lobbyId");
                    String participantToRemoveSessionId = (String) messageData.get("participantSessionId");
                    if (lobbyId != null && participantToRemoveSessionId != null) {
                        updatedLobbyOpt = lobbyService.hostRemovesParticipant(lobbyId, session.getId(), participantToRemoveSessionId);
                        updatedLobbyOpt.ifPresentOrElse(
                                this::broadcastLobbyUpdate,
                                () -> sendError(session, "Nie udało się usunąć uczestnika " + participantToRemoveSessionId + " z lobby " + lobbyId + " (być może nie jesteś hostem lub uczestnik nie istnieje).")
                        );
                    } else {
                        sendError(session, "Brakujące lobbyId lub participantSessionId w żądaniu removeParticipantFromLobby.");
                    }
                    break;

                case "startGame":
                    lobbyId = (String) messageData.get("lobbyId");
                    if(lobbyId != null) {
                        updatedLobbyOpt = lobbyService.setLobbyGameStatus(lobbyId, session.getId(), true);
                        updatedLobbyOpt.ifPresentOrElse(
                                this::broadcastLobbyUpdate,
                                () -> sendError(session, "Nie udało się wystartować gry w lobby " + lobbyId)
                        );
                    } else {
                        sendError(session, "Brak lobbyId w żądaniu startGame.");
                    }
                    break;
                default:
                    sendError(session, "Nieznana akcja: " + action);
            }

        } catch (JsonSyntaxException e) {
            sendError(session, "Błąd formatu wiadomości JSON.");
        } catch (Exception e) {
            sendError(session, "Wystąpił błąd serwera podczas przetwarzania wiadomości.");
            e.printStackTrace();
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        String sessionId = session.getId();
        sessions.remove(sessionId);
        System.out.println("WebSocket: Zamknięto połączenie: " + sessionId + ", status: " + status);

        Long joinTimestamp = recentlyJoinedWebPlayers.remove(sessionId);
        boolean wasRecentlyJoinedWebPlayer = joinTimestamp != null && (System.currentTimeMillis() - joinTimestamp < 5000);

        if (wasRecentlyJoinedWebPlayer) {
            System.out.println("WebSocket: Sesja " + sessionId + " była niedawno dołączonym graczem webowym. Opóźnianie usunięcia z lobby.");
            return;
        }

        boolean broadcastAllNeeded = false;
        List<Lobby> processedLobbies = new ArrayList<>();


        for (Lobby lobby : lobbyService.getAllLobbies()) {
            boolean wasHost = lobby.getHostSessionId() != null && lobby.getHostSessionId().equals(sessionId);

            if (wasHost) {
                System.out.println("WebSocket: Sesja " + sessionId + " była HOSTem w lobby " + lobby.getId() + ". Resetowanie lobby i informowanie graczy.");
                processedLobbies.add(lobby);

                List<String> playerSessionIdsInLobby = new ArrayList<>();
                lobby.getWaitingPlayers().forEach(p -> playerSessionIdsInLobby.add(p.sessionId()));
                lobby.getPlayersInTeams().forEach(p -> playerSessionIdsInLobby.add(p.sessionId()));
                if (lobby.getQuizMaster() != null && !lobby.getQuizMaster().sessionId().equals(sessionId)) {
                    playerSessionIdsInLobby.add(lobby.getQuizMaster().sessionId());
                }

                for (String playerSessionIdInLobby : playerSessionIdsInLobby) {
                    WebSocketSession playerWsSession = sessions.get(playerSessionIdInLobby);
                    if (playerWsSession != null && playerWsSession.isOpen()) {
                        sendError(playerWsSession, "Host opuścił lobby '" + lobby.getName() + "'. Lobby zostało zamknięte i zostaniesz z niego usunięty.");
                    }
                }

                Optional<Lobby> resetLobbyOpt = lobbyService.resetLobbyDueToHostDisconnect(lobby.getId());
                if (resetLobbyOpt.isPresent()) {
                    broadcastLobbyUpdate(resetLobbyOpt.get());
                } else {
                    broadcastAllNeeded = true;
                }
            }
        }

        for (Lobby lobby : lobbyService.getAllLobbies()) {
            if (processedLobbies.stream().anyMatch(l -> l.getId().equals(lobby.getId()))) {
                continue;
            }

            boolean wasParticipantNonHost = (lobby.getPlayersInTeams().stream().anyMatch(p -> p.sessionId().equals(sessionId)) ||
                    lobby.getWaitingPlayers().stream().anyMatch(p -> p.sessionId().equals(sessionId)) ||
                    (lobby.getQuizMaster() != null && lobby.getQuizMaster().sessionId().equals(sessionId)))
                    && (lobby.getHostSessionId() == null || !lobby.getHostSessionId().equals(sessionId));

            if (wasParticipantNonHost) {
                System.out.println("WebSocket: Sesja " + sessionId + " była uczestnikiem (nie hostem) w lobby " + lobby.getId() + ". Usuwanie.");
                Optional<Lobby> updatedLobby = lobbyService.removePlayerFromLobby(lobby.getId(), sessionId);
                if (updatedLobby.isPresent()) {
                    broadcastLobbyUpdate(updatedLobby.get());
                } else {
                    broadcastAllNeeded = true;
                }
            }
        }


        if (broadcastAllNeeded) {
            System.out.println("WebSocket: Rozgłaszanie aktualizacji wszystkich lobby z powodu rozłączenia lub błędu resetu.");
            broadcastAllLobbies();
        }
    }


    private void sendError(WebSocketSession session, String errorMessage) {
        Map<String, Object> errorPayload = Map.of("type", "error", "message", errorMessage);
        sendMessageToSession(session, errorPayload);
    }

    public void sendAllLobbiesToOneUser(WebSocketSession session) {
        Collection<Lobby> allLobbies = lobbyService.getAllLobbies();

        List<Map<String, Object>> clientLobbiesView = new ArrayList<>();
        for (Lobby lobby : allLobbies) {
            Map<String, Object> lobbyDataForClient = new HashMap<>();
            lobbyDataForClient.put("id", lobby.getId());
            lobbyDataForClient.put("name", lobby.getName());
            lobbyDataForClient.put("status", lobby.getStatus().toString());
            lobbyDataForClient.put("gameSettings", lobby.getGameSettings());
            lobbyDataForClient.put("password", lobby.getPassword());
            lobbyDataForClient.put("hostSessionId", lobby.getHostSessionId());
            lobbyDataForClient.put("quizMaster", lobby.getQuizMaster());
            lobbyDataForClient.put("waitingPlayers", lobby.getWaitingPlayers());
            lobbyDataForClient.put("teams", lobby.getTeams());
            lobbyDataForClient.put("totalParticipantCount", lobby.getTotalParticipantCount());
            lobbyDataForClient.put("maxParticipants", lobby.getMaxParticipants());
            clientLobbiesView.add(lobbyDataForClient);
        }
        Map<String, Object> messagePayload = Map.of("type", "allLobbies", "lobbies", clientLobbiesView);
        sendMessageToSession(session, messagePayload);
    }

    public void broadcastAllLobbies() {
        Collection<Lobby> allLobbies = lobbyService.getAllLobbies();
        List<Map<String, Object>> clientLobbiesView = new ArrayList<>();
        for (Lobby lobby : allLobbies) {
            Map<String, Object> lobbyDataForClient = new HashMap<>();
            lobbyDataForClient.put("id", lobby.getId());
            lobbyDataForClient.put("name", lobby.getName());
            lobbyDataForClient.put("status", lobby.getStatus().toString());
            lobbyDataForClient.put("gameSettings", lobby.getGameSettings());
            lobbyDataForClient.put("password", lobby.getPassword());
            lobbyDataForClient.put("hostSessionId", lobby.getHostSessionId());
            lobbyDataForClient.put("quizMaster", lobby.getQuizMaster());
            lobbyDataForClient.put("waitingPlayers", lobby.getWaitingPlayers());
            lobbyDataForClient.put("teams", lobby.getTeams());
            lobbyDataForClient.put("totalParticipantCount", lobby.getTotalParticipantCount());
            lobbyDataForClient.put("maxParticipants", lobby.getMaxParticipants());
            clientLobbiesView.add(lobbyDataForClient);
        }
        Map<String, Object> messagePayload = Map.of("type", "allLobbiesUpdate", "lobbies", clientLobbiesView);
        sessions.values().forEach(s -> sendMessageToSession(s, messagePayload));
    }

    public void broadcastLobbyUpdate(Lobby lobby) {
        if (lobby == null) return;

        Map<String, Object> lobbyDataForClient = new HashMap<>();
        lobbyDataForClient.put("id", lobby.getId());
        lobbyDataForClient.put("name", lobby.getName());
        lobbyDataForClient.put("status", lobby.getStatus().toString());
        lobbyDataForClient.put("gameSettings", lobby.getGameSettings());
        lobbyDataForClient.put("password", lobby.getPassword());
        lobbyDataForClient.put("hostSessionId", lobby.getHostSessionId());
        lobbyDataForClient.put("quizMaster", lobby.getQuizMaster());
        lobbyDataForClient.put("waitingPlayers", lobby.getWaitingPlayers());
        lobbyDataForClient.put("teams", lobby.getTeams());
        lobbyDataForClient.put("totalParticipantCount", lobby.getTotalParticipantCount());
        lobbyDataForClient.put("maxParticipants", lobby.getMaxParticipants());

        System.out.println("DEBUG: Wysyłanie lobbyUpdate, dane dla klienta (JSON): " + gson.toJson(lobbyDataForClient));

        Map<String, Object> messagePayload = Map.of("type", "lobbyUpdate", "lobby", lobbyDataForClient);

        System.out.println("WebSocket: Rozgłaszanie aktualizacji lobby '" + lobby.getId() + "' (Status: " + lobby.getStatus() +
                ", Uczestników: " + lobby.getTotalParticipantCount() + "/" + lobby.getMaxParticipants() +
                ", Nazwa: " + lobby.getName() +
                ", Prowadzący: " + (lobby.getQuizMaster() != null ? lobby.getQuizMaster().nickname() : "brak") +
                ", Oczekujący: " + lobby.getWaitingPlayers().stream().map(PlayerInfo::nickname).collect(Collectors.joining(", ")) +
                ", Host: " + lobby.getHostSessionId() +
                ") do " + sessions.size() + " sesji.");
        sessions.values().forEach(s -> sendMessageToSession(s, messagePayload));
    }

    private void sendMessageToSession(WebSocketSession session, Map<String, Object> payload) {
        if (session.isOpen()) {
            try {
                session.sendMessage(new TextMessage(gson.toJson(payload)));
            } catch (IOException e) {
                System.err.println("WebSocket: Błąd wysyłania do sesji " + session.getId() + ": " + e.getMessage());
            }
        }
    }
}