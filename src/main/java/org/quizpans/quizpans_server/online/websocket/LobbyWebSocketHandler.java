package org.quizpans.quizpans_server.online.websocket;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import org.quizpans.quizpans_server.online.model.Lobby;
import org.quizpans.quizpans_server.online.model.LobbyStatus;
import org.quizpans.quizpans_server.online.model.GameSettings;
import org.quizpans.quizpans_server.online.model.PlayerInfo;
import org.quizpans.quizpans_server.online.model.ParticipantRole;
import org.quizpans.quizpans_server.online.service.LobbyService;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Component
public class LobbyWebSocketHandler extends TextWebSocketHandler {

    private final LobbyService lobbyService;
    private final Gson gson = new GsonBuilder().serializeNulls().create();
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

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
                sendError(session.getId(), "Brakująca akcja w wiadomości.");
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
                        updatedLobbyOpt.ifPresent(this::broadcastLobbyUpdate);
                    } else { sendError(session.getId(), "Brak lobbyId w żądaniu requestHostLobby."); }
                    break;
                case "configureLobby":
                    lobbyId = (String) messageData.get("lobbyId");
                    Map<String, Object> settingsMap = (Map<String, Object>) messageData.get("gameSettings");
                    GameSettings settings = (settingsMap != null) ? gson.fromJson(gson.toJson(settingsMap), GameSettings.class) : null;
                    String password = (String) messageData.get("password");
                    if (lobbyId != null && settings != null) {
                        updatedLobbyOpt = lobbyService.finalizeLobbyConfiguration(lobbyId, session.getId(), settings, password);
                        updatedLobbyOpt.ifPresent(this::broadcastLobbyUpdate);
                    } else { sendError(session.getId(), "Brak lobbyId lub gameSettings w żądaniu configureLobby."); }
                    break;
                case "playerWebJoinRequest":
                    lobbyId = (String) messageData.get("lobbyId");
                    String nickname = (String) messageData.get("nickname");
                    String providedPassword = (String) messageData.get("password");
                    if (lobbyId != null && nickname != null) {
                        PlayerInfo webPlayer = new PlayerInfo(session.getId(), nickname, null, ParticipantRole.PLAYER);
                        updatedLobbyOpt = lobbyService.addWaitingPlayerWithPasswordCheck(lobbyId, webPlayer, providedPassword);
                        final String finalLobbyId = lobbyId;
                        updatedLobbyOpt.ifPresentOrElse(lobby -> {
                            Map<String, Object> successMsg = Map.of("type", "joinSuccess", "lobbyId", lobby.getId());
                            sendMessageToSession(session, successMsg);
                            this.broadcastLobbyUpdate(lobby);
                        }, () -> {
                            Optional<Lobby> targetLobbyOpt = lobbyService.getLobby(finalLobbyId);
                            if (targetLobbyOpt.isPresent()) {
                                Lobby targetLobby = targetLobbyOpt.get();
                                boolean isPasswordProtected = targetLobby.getPassword() != null && !targetLobby.getPassword().isEmpty();
                                if (isPasswordProtected && providedPassword != null && !providedPassword.isEmpty()) {
                                    sendError(session.getId(), "Błędne hasło do lobby '" + targetLobby.getName() + "'. Spróbuj ponownie.");
                                } else if (isPasswordProtected && (providedPassword == null || providedPassword.isEmpty())) {
                                    sendError(session.getId(), "Lobby '" + targetLobby.getName() + "' jest zabezpieczone hasłem. Musisz je podać.");
                                } else {
                                    sendError(session.getId(), "Nie udało się dołączyć do lobby '" + targetLobby.getName() + "'. Może być pełne lub niedostępne.");
                                }
                            } else {
                                sendError(session.getId(), "Nie znaleziono lobby o ID: " + finalLobbyId);
                            }
                        });
                    } else { sendError(session.getId(), "Brak lobbyId lub nickname w żądaniu playerWebJoinRequest."); }
                    break;
                case "announceWebPlayerInRoom":
                    lobbyId = (String) messageData.get("lobbyId");
                    String playerNickname = (String) messageData.get("nickname");
                    String newPlayerSessionId = session.getId();

                    if (lobbyId != null && playerNickname != null) {
                        PlayerInfo announcedPlayer = new PlayerInfo(newPlayerSessionId, playerNickname, null, ParticipantRole.PLAYER);
                        Optional<Lobby> lobbyOpt = lobbyService.getLobby(lobbyId);
                        if (lobbyOpt.isPresent()) {
                            Lobby currentLobby = lobbyOpt.get();
                            if (currentLobby.findParticipantBySessionId(newPlayerSessionId) == null) {
                                if (currentLobby.addPlayer(announcedPlayer)) {
                                    this.broadcastLobbyUpdate(currentLobby);
                                }
                            } else {
                                this.broadcastLobbyUpdate(currentLobby);
                            }
                        }
                    } else { sendError(session.getId(), "Brakujące dane w żądaniu announceWebPlayerInRoom."); }
                    break;
                case "registerHostPanel":
                    lobbyId = (String) messageData.get("lobbyId");
                    if (lobbyId != null) {
                        lobbyService.registerHostPanel(lobbyId, session.getId()).ifPresent(this::broadcastLobbyUpdate);
                    }
                    break;
                case "assignRole":
                    lobbyId = (String) messageData.get("lobbyId");
                    String participantSessionId = (String) messageData.get("participantSessionId");
                    String role = (String) messageData.get("role");
                    String targetTeamName = (String) messageData.get("targetTeamName");
                    if (lobbyId != null && participantSessionId != null && role != null) {
                        updatedLobbyOpt = lobbyService.assignParticipantRole(lobbyId, session.getId(), participantSessionId, role, targetTeamName);
                        updatedLobbyOpt.ifPresent(this::broadcastLobbyUpdate);
                    } else { sendError(session.getId(), "Brakujące dane w żądaniu assignRole."); }
                    break;
                case "unassignParticipant":
                    lobbyId = (String) messageData.get("lobbyId");
                    String participantToUnassignSessionId = (String) messageData.get("participantSessionId");
                    if (lobbyId != null && participantToUnassignSessionId != null) {
                        updatedLobbyOpt = lobbyService.unassignParticipant(lobbyId, session.getId(), participantToUnassignSessionId);
                        updatedLobbyOpt.ifPresent(this::broadcastLobbyUpdate);
                    } else { sendError(session.getId(), "Brakujące dane w żądaniu unassignParticipant."); }
                    break;
                case "leaveLobby":
                    lobbyId = (String) messageData.get("lobbyId");
                    if (lobbyId != null) {
                        lobbyService.removePlayerFromLobby(lobbyId, session.getId()).ifPresent(this::broadcastLobbyUpdate);
                    }
                    break;
                case "removeParticipantFromLobby":
                    lobbyId = (String) messageData.get("lobbyId");
                    String participantToRemoveSessionId = (String) messageData.get("participantSessionId");
                    if (lobbyId != null && participantToRemoveSessionId != null) {
                        updatedLobbyOpt = lobbyService.hostRemovesParticipant(lobbyId, session.getId(), participantToRemoveSessionId);
                        updatedLobbyOpt.ifPresent(this::broadcastLobbyUpdate);
                    } else { sendError(session.getId(), "Brakujące dane w żądaniu removeParticipantFromLobby."); }
                    break;
                case "startGame":
                    lobbyId = (String) messageData.get("lobbyId");
                    Set<Integer> usedQuestionIds = new HashSet<>();
                    if (messageData.containsKey("usedQuestionIds")) {
                        List<Double> idsAsDouble = (List<Double>) messageData.get("usedQuestionIds");
                        for (Double d : idsAsDouble) {
                            usedQuestionIds.add(d.intValue());
                        }
                    }
                    if (lobbyId != null) {
                        updatedLobbyOpt = lobbyService.setLobbyGameStatus(lobbyId, session.getId(), true, usedQuestionIds);
                        updatedLobbyOpt.ifPresent(this::broadcastLobbyUpdate);
                    } else { sendError(session.getId(), "Brak lobbyId w żądaniu startGame."); }
                    break;
                case "requestNewQuestion":
                    lobbyId = (String) messageData.get("lobbyId");
                    Set<Integer> existingIds = new HashSet<>();
                    if (messageData.containsKey("usedQuestionIds")) {
                        List<Double> idsAsDouble = (List<Double>) messageData.get("usedQuestionIds");
                        for (Double d : idsAsDouble) {
                            existingIds.add(d.intValue());
                        }
                    }
                    if (lobbyId != null) {
                        lobbyService.loadNewQuestionForNextRound(lobbyId, session.getId(), existingIds);
                    }
                    break;
                case "submitAnswer":
                    lobbyId = (String) messageData.get("lobbyId");
                    String answer = (String) messageData.get("answer");
                    String answeringPlayerSessionId = (String) messageData.get("playerSessionId");
                    if (lobbyId != null && answer != null && answeringPlayerSessionId != null && answeringPlayerSessionId.equals(session.getId())) {
                        updatedLobbyOpt = lobbyService.processPlayerAnswer(lobbyId, answeringPlayerSessionId, answer);
                        updatedLobbyOpt.ifPresent(lobby -> {
                            if (lobby.getStatus() == LobbyStatus.VALIDATING) {
                                sendAnswerToHostPanelForValidation(lobby, answeringPlayerSessionId, answer);
                            }
                            broadcastLobbyUpdate(lobby);
                        });
                    } else { sendError(session.getId(), "Brakujące dane lub niezgodność sesji w submitAnswer."); }
                    break;
                case "validateAnswer":
                    lobbyId = (String) messageData.get("lobbyId");
                    String pSessionId = (String) messageData.get("playerSessionId");
                    boolean isCorrect = (Boolean) messageData.get("isCorrect");
                    String matchedAnswer = (String) messageData.get("matchedAnswer");

                    if (lobbyId != null && pSessionId != null) {
                        Lobby currentLobby = lobbyService.getLobby(lobbyId).orElse(null);
                        if (currentLobby != null && session.getId().equals(currentLobby.getHostPanelSessionId())) {
                            updatedLobbyOpt = lobbyService.validateAnswerByQuizMaster(lobbyId, pSessionId, isCorrect, matchedAnswer);
                            updatedLobbyOpt.ifPresent(this::broadcastLobbyUpdate);
                        } else {
                            sendError(session.getId(), "Nie jesteś uprawniony do walidacji odpowiedzi w tym lobby.");
                        }
                    }
                    break;
                default: sendError(session.getId(), "Nieznana akcja: " + action);
            }
        } catch (JsonSyntaxException e) { sendError(session.getId(), "Błąd formatu JSON."); }
        catch (Exception e) { sendError(session.getId(), "Błąd serwera."); e.printStackTrace(); }
    }

    private void sendAnswerToHostPanelForValidation(Lobby lobby, String answeringPlayerSessionId, String answer) {
        String hostPanelSessionId = lobby.getHostPanelSessionId();
        PlayerInfo answeringPlayer = lobby.findParticipantBySessionId(answeringPlayerSessionId);

        if (hostPanelSessionId == null || answeringPlayer == null) return;

        WebSocketSession hostPanelSession = sessions.get(hostPanelSessionId);
        if (hostPanelSession != null && hostPanelSession.isOpen()) {
            Map<String, Object> validationPayload = new HashMap<>();
            validationPayload.put("type", "answerForValidation");
            validationPayload.put("playerSessionId", answeringPlayer.sessionId());
            validationPayload.put("playerNickname", answeringPlayer.nickname());
            validationPayload.put("submittedAnswer", answer);

            List<Map<String, Object>> correctAnswers = lobby.getRevealedAnswersData().stream()
                    .filter(ansMap -> !(boolean)ansMap.get("isRevealed"))
                    .map(ansMap -> Map.of(
                            "answer", ansMap.get("text"),
                            "points", ansMap.get("points")
                    )).collect(Collectors.toList());

            validationPayload.put("correctAnswers", correctAnswers);
            sendMessageToSession(hostPanelSession, validationPayload);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        String sessionId = session.getId();
        sessions.remove(sessionId);

        lobbyService.getAllLobbies().forEach(lobby -> {
            boolean wasParticipant = lobby.findParticipantBySessionId(sessionId) != null || sessionId.equals(lobby.getHostSessionId()) || sessionId.equals(lobby.getHostPanelSessionId());
            if (wasParticipant) {
                lobbyService.removePlayerFromLobby(lobby.getId(), sessionId).ifPresent(this::broadcastLobbyUpdate);
            }
        });
    }

    public void sendError(String sessionId, String errorMessage) {
        WebSocketSession session = sessions.get(sessionId);
        if (session != null && session.isOpen()) {
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
        lobbyDataForClient.put("currentQuestionId", lobby.getCurrentQuestionId());
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
        lobbyDataForClient.put("currentAnswerTimeRemaining", lobby.getCurrentAnswerTimeRemaining());
        lobbyDataForClient.put("hostPanelSessionId", lobby.getHostPanelSessionId());
        return lobbyDataForClient;
    }

    private void sendMessageToSession(WebSocketSession session, Map<String, Object> payload) {
        if (session.isOpen()) {
            try {
                session.sendMessage(new TextMessage(gson.toJson(payload)));
            } catch (IOException e) {
            }
        }
    }
}