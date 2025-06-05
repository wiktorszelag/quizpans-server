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
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.lang.reflect.Type;
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

    @Autowired
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
                        updatedLobbyOpt.ifPresentOrElse(this::broadcastLobbyUpdate, () -> sendError(session, "Nie udało się przejąć lobby: " + lobbyId));
                    } else { sendError(session, "Brak lobbyId w żądaniu requestHostLobby."); }
                    break;
                case "configureLobby":
                    lobbyId = (String) messageData.get("lobbyId");
                    Map<String, Object> settingsMap = (Map<String, Object>) messageData.get("gameSettings");
                    GameSettings settings = (settingsMap != null) ? gson.fromJson(gson.toJson(settingsMap), GameSettings.class) : null;
                    String password = (String) messageData.get("password");
                    if (lobbyId != null && settings != null) {
                        updatedLobbyOpt = lobbyService.finalizeLobbyConfiguration(lobbyId, session.getId(), settings, password);
                        updatedLobbyOpt.ifPresentOrElse(this::broadcastLobbyUpdate, () -> sendError(session, "Nie udało się skonfigurować lobby: " + lobbyId));
                    } else { sendError(session, "Brak lobbyId lub gameSettings w żądaniu configureLobby."); }
                    break;
                case "playerWebJoinRequest":
                    lobbyId = (String) messageData.get("lobbyId");
                    String nickname = (String) messageData.get("nickname");
                    String providedPassword = (String) messageData.get("password");
                    if (lobbyId != null && nickname != null) {
                        PlayerInfo webPlayer = new PlayerInfo(session.getId(), nickname, null);
                        updatedLobbyOpt = lobbyService.addWaitingPlayerWithPasswordCheck(lobbyId, webPlayer, providedPassword);
                        final String finalLobbyId = lobbyId;
                        updatedLobbyOpt.ifPresentOrElse(lobby -> {
                            recentlyJoinedWebPlayers.put(session.getId(), System.currentTimeMillis());
                            Map<String, Object> successMsg = Map.of("type", "joinSuccess", "lobbyId", lobby.getId());
                            sendMessageToSession(session, successMsg);
                            this.broadcastLobbyUpdate(lobby);
                        }, () -> {
                            Optional<Lobby> targetLobbyOpt = lobbyService.getLobby(finalLobbyId);
                            if (targetLobbyOpt.isPresent()) {
                                Lobby targetLobby = targetLobbyOpt.get();
                                boolean isPasswordProtected = targetLobby.getPassword() != null && !targetLobby.getPassword().isEmpty();
                                if (isPasswordProtected && providedPassword != null && !providedPassword.isEmpty()) {
                                    sendError(session, "Błędne hasło do lobby '" + targetLobby.getName() + "'. Spróbuj ponownie.");
                                } else if (isPasswordProtected && (providedPassword == null || providedPassword.isEmpty())) {
                                    sendError(session, "Lobby '" + targetLobby.getName() + "' jest zabezpieczone hasłem. Musisz je podać.");
                                } else {
                                    sendError(session, "Nie udało się dołączyć do lobby '" + targetLobby.getName() + "'. Może być pełne lub niedostępne.");
                                }
                            } else {
                                sendError(session, "Nie znaleziono lobby o ID: " + finalLobbyId);
                            }
                        });
                    } else { sendError(session, "Brak lobbyId lub nickname w żądaniu playerWebJoinRequest."); }
                    break;
                case "announceWebPlayerInRoom":
                    lobbyId = (String) messageData.get("lobbyId");
                    String playerNickname = (String) messageData.get("nickname");
                    String newPlayerSessionId = (String) messageData.get("newSessionId");

                    if (lobbyId != null && playerNickname != null && newPlayerSessionId != null && newPlayerSessionId.equals(session.getId())) {
                        PlayerInfo announcedPlayer = new PlayerInfo(session.getId(), playerNickname, null);
                        Optional<Lobby> lobbyOpt = lobbyService.getLobby(lobbyId);
                        if (lobbyOpt.isPresent()) {
                            Lobby currentLobby = lobbyOpt.get();
                            if (currentLobby.findParticipantBySessionId(session.getId()) == null) {
                                if (currentLobby.addWaitingPlayer(announcedPlayer)) {
                                    this.broadcastLobbyUpdate(currentLobby);
                                } else {
                                    sendError(session, "Nie udało się dodać gracza " + playerNickname + " do poczekalni lobby " + lobbyId + " po przejściu.");
                                }
                            } else {
                                this.broadcastLobbyUpdate(currentLobby);
                            }
                        } else {
                            sendError(session, "Nie znaleziono lobby " + lobbyId + " przy próbie ogłoszenia gracza.");
                        }
                    } else { sendError(session, "Brakujące lub nieprawidłowe dane w żądaniu announceWebPlayerInRoom."); }
                    break;
                case "assignRole":
                    lobbyId = (String) messageData.get("lobbyId");
                    String participantSessionId = (String) messageData.get("participantSessionId");
                    String role = (String) messageData.get("role");
                    String targetTeamName = (String) messageData.get("targetTeamName");
                    if (lobbyId != null && participantSessionId != null && role != null) {
                        updatedLobbyOpt = lobbyService.assignParticipantRole(lobbyId, session.getId(), participantSessionId, role, targetTeamName);
                        updatedLobbyOpt.ifPresentOrElse(this::broadcastLobbyUpdate, () -> sendError(session, "Nie udało się przypisać roli."));
                    } else { sendError(session, "Brakujące dane w żądaniu assignRole."); }
                    break;
                case "unassignParticipant":
                    lobbyId = (String) messageData.get("lobbyId");
                    String participantToUnassignSessionId = (String) messageData.get("participantSessionId");
                    if (lobbyId != null && participantToUnassignSessionId != null) {
                        updatedLobbyOpt = lobbyService.unassignParticipant(lobbyId, session.getId(), participantToUnassignSessionId);
                        updatedLobbyOpt.ifPresentOrElse(this::broadcastLobbyUpdate, () -> sendError(session, "Nie udało się cofnąć uczestnika."));
                    } else { sendError(session, "Brakujące dane w żądaniu unassignParticipant."); }
                    break;
                case "leaveLobby":
                    lobbyId = (String) messageData.get("lobbyId");
                    if (lobbyId != null) {
                        lobbyService.removePlayerFromLobby(lobbyId, session.getId()).ifPresent(this::broadcastLobbyUpdate);
                    } else { sendError(session, "Brak lobbyId w żądaniu leaveLobby."); }
                    break;
                case "removeParticipantFromLobby":
                    lobbyId = (String) messageData.get("lobbyId");
                    String participantToRemoveSessionId = (String) messageData.get("participantSessionId");
                    if (lobbyId != null && participantToRemoveSessionId != null) {
                        updatedLobbyOpt = lobbyService.hostRemovesParticipant(lobbyId, session.getId(), participantToRemoveSessionId);
                        updatedLobbyOpt.ifPresentOrElse(this::broadcastLobbyUpdate, () -> sendError(session, "Nie udało się usunąć uczestnika."));
                    } else { sendError(session, "Brakujące dane w żądaniu removeParticipantFromLobby."); }
                    break;
                case "startGame":
                    lobbyId = (String) messageData.get("lobbyId");
                    if (lobbyId != null) {
                        updatedLobbyOpt = lobbyService.setLobbyGameStatus(lobbyId, session.getId(), true);
                        updatedLobbyOpt.ifPresentOrElse(this::broadcastLobbyUpdate, () -> sendError(session, "Nie udało się wystartować gry."));
                    } else { sendError(session, "Brak lobbyId w żądaniu startGame."); }
                    break;
                case "submitAnswer":
                    lobbyId = (String) messageData.get("lobbyId");
                    String answer = (String) messageData.get("answer");
                    String answeringPlayerSessionId = (String) messageData.get("playerSessionId");
                    if (lobbyId != null && answer != null && answeringPlayerSessionId != null && answeringPlayerSessionId.equals(session.getId())) {
                        updatedLobbyOpt = lobbyService.processPlayerAnswer(lobbyId, answeringPlayerSessionId, answer);
                        updatedLobbyOpt.ifPresentOrElse(this::broadcastLobbyUpdate, () -> sendError(session, "Nie udało się przetworzyć odpowiedzi."));
                    } else { sendError(session, "Brakujące dane lub niezgodność sesji w submitAnswer."); }
                    break;
                default: sendError(session, "Nieznana akcja: " + action);
            }
        } catch (JsonSyntaxException e) { sendError(session, "Błąd formatu JSON."); }
        catch (Exception e) { sendError(session, "Błąd serwera."); e.printStackTrace(); }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        String sessionId = session.getId();
        sessions.remove(sessionId);
        recentlyJoinedWebPlayers.remove(sessionId);

        lobbyService.getAllLobbies().forEach(lobby -> {
            if (sessionId.equals(lobby.getHostSessionId())) {
                Optional<Lobby> resetOpt = lobbyService.resetLobbyDueToHostDisconnect(lobby.getId());
                if(resetOpt.isPresent()){
                    broadcastLobbyUpdate(resetOpt.get());
                }
            } else {
                Optional<Lobby> removedOpt = lobbyService.removePlayerFromLobby(lobby.getId(), sessionId);
                if (removedOpt.isPresent()) {
                    broadcastLobbyUpdate(removedOpt.get());
                }
            }
        });
    }

    private void sendError(WebSocketSession session, String errorMessage) {
        if (session.isOpen()) {
            Map<String, Object> errorPayload = Map.of("type", "error", "message", errorMessage);
            sendMessageToSession(session, errorPayload);
        }
    }

    public void sendAllLobbiesToOneUser(WebSocketSession session) {
        Collection<Lobby> allLobbies = lobbyService.getAllLobbies();
        List<Map<String, Object>> clientLobbiesView = allLobbies.stream()
                .map(this::mapLobbyToClientData)
                .collect(Collectors.toList());
        Map<String, Object> messagePayload = Map.of("type", "allLobbies", "lobbies", clientLobbiesView);
        sendMessageToSession(session, messagePayload);
    }

    public void broadcastLobbyUpdate(Lobby lobby) {
        if (lobby == null) return;
        Map<String, Object> lobbyDataForClient = mapLobbyToClientData(lobby);
        Map<String, Object> messagePayload = Map.of("type", "lobbyUpdate", "lobby", lobbyDataForClient);
        sessions.values().stream().filter(WebSocketSession::isOpen).forEach(s -> sendMessageToSession(s, messagePayload));
    }

    private Map<String, Object> mapLobbyToClientData(Lobby lobby) {
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
        lobbyDataForClient.put("currentQuestionText", lobby.getCurrentQuestionText());
        lobbyDataForClient.put("currentRoundNumber", lobby.getCurrentRoundNumber());
        lobbyDataForClient.put("totalRounds", lobby.getTotalRounds());
        lobbyDataForClient.put("currentPlayerSessionId", lobby.getCurrentPlayerSessionId());
        lobbyDataForClient.put("isTeam1Turn", lobby.isTeam1Turn());
        lobbyDataForClient.put("team1Score", lobby.getTeam1Score());
        lobbyDataForClient.put("team2Score", lobby.getTeam2Score());
        lobbyDataForClient.put("team1Errors", lobby.getTeam1Errors());
        lobbyDataForClient.put("team2Errors", lobby.getTeam2Errors());
        lobbyDataForClient.put("revealedAnswersData", lobby.getRevealedAnswersData());
        lobbyDataForClient.put("currentRoundPoints", lobby.getCurrentRoundPoints());
        lobbyDataForClient.put("revealedAnswersCountInRound", lobby.getRevealedAnswersCountInRound());
        lobbyDataForClient.put("currentAnswerTimeRemaining", lobby.getCurrentAnswerTimeRemaining());
        return lobbyDataForClient;
    }

    private void sendMessageToSession(WebSocketSession session, Map<String, Object> payload) {
        if (session.isOpen()) {
            try {
                session.sendMessage(new TextMessage(gson.toJson(payload)));
            } catch (IOException e) {
                System.err.println("Błąd wysyłania wiadomości do sesji " + session.getId() + ": " + e.getMessage());
            }
        }
    }
}