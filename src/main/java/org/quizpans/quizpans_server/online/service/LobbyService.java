package org.quizpans.quizpans_server.online.service;

import org.quizpans.quizpans_server.online.model.Lobby;
import org.quizpans.quizpans_server.online.model.LobbyStatus;
import org.quizpans.quizpans_server.online.model.GameSettings;
import org.quizpans.quizpans_server.online.model.PlayerInfo;
import org.quizpans.quizpans_server.online.model.ParticipantRole;
import org.quizpans.quizpans_server.game.GameService;
import org.quizpans.quizpans_server.game.GameService.AnswerProcessingResult;
import org.quizpans.quizpans_server.online.websocket.LobbyWebSocketHandler;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

@Service
public class LobbyService {

    private final Map<String, Lobby> lobbies = new ConcurrentHashMap<>();
    private static final List<String> LOBBY_IDS = List.of("Red", "Blue", "Green", "Yellow", "Black");
    private final Map<String, GameService> activeGameServices = new ConcurrentHashMap<>();
    private final ScheduledExecutorService timerScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r);
        t.setDaemon(true);
        t.setName("LobbyTimerScheduler");
        return t;
    });

    private final LobbyWebSocketHandler webSocketHandler;

    @Autowired
    public LobbyService(@Lazy LobbyWebSocketHandler webSocketHandler) {
        this.webSocketHandler = webSocketHandler;
    }

    @PostConstruct
    private void initializeLobbies() {
        for (String id : LOBBY_IDS) {
            Lobby newLobby = new Lobby(id, id);
            final LobbyWebSocketHandler handlerRef = this.webSocketHandler;
            newLobby.setTimerCallback(lobbyToBroadcast -> {
                if (handlerRef != null) {
                    handlerRef.broadcastLobbyUpdate(lobbyToBroadcast);
                }
            });
            lobbies.put(id, newLobby);
        }
    }

    @PreDestroy
    public void shutdownScheduler() {
        if (timerScheduler != null && !timerScheduler.isShutdown()) {
            timerScheduler.shutdownNow();
        }
    }

    public Collection<Lobby> getAllLobbies() {
        return lobbies.values();
    }

    public Optional<Lobby> getLobby(String id) {
        return Optional.ofNullable(lobbies.get(id));
    }

    public synchronized Optional<Lobby> hostTakesLobby(String lobbyId, String hostSessionId) {
        Optional<Lobby> lobbyOpt = getLobby(lobbyId);
        if (lobbyOpt.isPresent()) {
            Lobby lobby = lobbyOpt.get();
            if (lobby.getStatus() == LobbyStatus.AVAILABLE) {
                lobby.setHostSessionId(hostSessionId);
                lobby.setStatus(LobbyStatus.BUSY);
                return Optional.of(lobby);
            } else if (lobby.getStatus() != LobbyStatus.AVAILABLE && lobby.getHostSessionId() != null && lobby.getHostSessionId().equals(hostSessionId)) {
                return Optional.of(lobby);
            } else if (lobby.getStatus() != LobbyStatus.AVAILABLE && lobby.getHostSessionId() == null) {
                lobby.setHostSessionId(hostSessionId);
                lobby.setStatus(LobbyStatus.BUSY);
                return Optional.of(lobby);
            }
        }
        return Optional.empty();
    }

    public synchronized Optional<Lobby> finalizeLobbyConfiguration(String lobbyId, String hostSessionId, GameSettings settings, String password) {
        Optional<Lobby> lobbyOpt = getLobby(lobbyId);
        if (lobbyOpt.isPresent()) {
            Lobby lobby = lobbyOpt.get();
            if (lobby.getHostSessionId() != null && lobby.getHostSessionId().equals(hostSessionId)) {
                lobby.setGameSettings(settings);
                lobby.setPassword(password);
                return Optional.of(lobby);
            }
        }
        return Optional.empty();
    }

    public synchronized Optional<Lobby> registerHostPanel(String lobbyId, String panelSessionId) {
        Optional<Lobby> lobbyOpt = getLobby(lobbyId);
        lobbyOpt.ifPresent(lobby -> lobby.setHostPanelSessionId(panelSessionId));
        return lobbyOpt;
    }

    public synchronized Optional<Lobby> addWaitingPlayerWithPasswordCheck(String lobbyId, PlayerInfo player, String providedPassword) {
        Optional<Lobby> lobbyOpt = getLobby(lobbyId);
        if (lobbyOpt.isPresent()) {
            Lobby lobby = lobbyOpt.get();
            if (lobby.getHostSessionId() != null) {
                String lobbyPassword = lobby.getPassword();
                boolean passwordRequired = lobbyPassword != null && !lobbyPassword.isEmpty();
                if (passwordRequired && (providedPassword == null || !lobbyPassword.equals(providedPassword))) {
                    return Optional.empty();
                }
                if (lobby.addPlayer(player)) {
                    return Optional.of(lobby);
                }
            }
        }
        return Optional.empty();
    }

    public synchronized Optional<Lobby> assignParticipantRole(String lobbyId, String hostSessionId, String participantSessionId, String role, String targetTeamName) {
        Optional<Lobby> lobbyOpt = getLobby(lobbyId);
        if (lobbyOpt.isEmpty() || lobbyOpt.get().getHostSessionId() == null || !lobbyOpt.get().getHostSessionId().equals(hostSessionId)) {
            return Optional.empty();
        }
        Lobby lobby = lobbyOpt.get();
        PlayerInfo participant = lobby.findParticipantBySessionId(participantSessionId);
        if (participant == null) return Optional.empty();

        if ("QUIZ_MASTER".equalsIgnoreCase(role)) {
            lobby.setQuizMaster(participant);
        } else if ("PLAYER".equalsIgnoreCase(role)) {
            if (targetTeamName == null || targetTeamName.trim().isEmpty()) return Optional.empty();
            lobby.assignPlayerToTeam(participant.sessionId(), targetTeamName);
        }
        return Optional.of(lobby);
    }

    public synchronized Optional<Lobby> unassignParticipant(String lobbyId, String hostSessionId, String participantToUnassignSessionId) {
        Optional<Lobby> lobbyOpt = getLobby(lobbyId);
        if (lobbyOpt.isEmpty() || lobbyOpt.get().getHostSessionId() == null || !lobbyOpt.get().getHostSessionId().equals(hostSessionId)) {
            return Optional.empty();
        }
        return lobbyOpt.get().unassignPlayer(participantToUnassignSessionId) ? lobbyOpt : Optional.empty();
    }

    public synchronized Optional<Lobby> resetLobbyDueToHostDisconnect(String lobbyId) {
        Optional<Lobby> lobbyOpt = getLobby(lobbyId);
        if (lobbyOpt.isPresent()) {
            Lobby lobby = lobbyOpt.get();
            lobby.stopAnswerTimer();
            lobby.resetToAvailable();
            activeGameServices.remove(lobbyId);
            return Optional.of(lobby);
        }
        return Optional.empty();
    }

    public synchronized Optional<Lobby> removePlayerFromLobby(String lobbyId, String playerSessionIdToRemove) {
        Optional<Lobby> lobbyOpt = getLobby(lobbyId);
        if (lobbyOpt.isPresent()) {
            Lobby lobby = lobbyOpt.get();
            if (lobby.removePlayer(playerSessionIdToRemove)) {
                return Optional.of(lobby);
            }
        }
        return Optional.empty();
    }

    public synchronized Optional<Lobby> hostRemovesParticipant(String lobbyId, String requestingHostSessionId, String participantToRemoveSessionId) {
        Optional<Lobby> lobbyOpt = getLobby(lobbyId);
        if (lobbyOpt.isEmpty() || lobbyOpt.get().getHostSessionId() == null ||
                !lobbyOpt.get().getHostSessionId().equals(requestingHostSessionId) ||
                participantToRemoveSessionId.equals(requestingHostSessionId)) {
            return Optional.empty();
        }
        return lobbyOpt.get().removePlayer(participantToRemoveSessionId) ? lobbyOpt : Optional.empty();
    }

    public synchronized Optional<Lobby> setLobbyGameStatus(String lobbyId, String hostSessionId, boolean gameInProgress, Set<Integer> usedQuestionIds) {
        Optional<Lobby> lobbyOpt = getLobby(lobbyId);
        if (lobbyOpt.isPresent()) {
            Lobby lobby = lobbyOpt.get();
            if (lobby.getHostSessionId() != null && lobby.getHostSessionId().equals(hostSessionId)) {
                if (gameInProgress) {
                    GameSettings settings = lobby.getGameSettings();
                    if (settings == null) return Optional.empty();

                    Map<String, List<PlayerInfo>> teams = lobby.getTeams();
                    String teamBlueName = lobby.getTeam1Name();
                    String teamRedName = lobby.getTeam2Name();

                    if (!teams.containsKey(teamBlueName) || teams.get(teamBlueName).isEmpty() ||
                            !teams.containsKey(teamRedName) || teams.get(teamRedName).isEmpty()) {
                        return Optional.empty();
                    }

                    String categoryName = settings.category();
                    final String finalCategoryForService;
                    if ("MIX (Wszystkie Kategorie)".equalsIgnoreCase(categoryName)) {
                        finalCategoryForService = null;
                    } else {
                        finalCategoryForService = categoryName;
                    }

                    GameService gameServiceInstance = activeGameServices.computeIfAbsent(lobbyId, k -> new GameService(finalCategoryForService));

                    boolean questionLoaded = gameServiceInstance.loadQuestion(usedQuestionIds);

                    if (!questionLoaded) {
                        webSocketHandler.sendError(hostSessionId, "NO_QUESTIONS_AVAILABLE");
                        return Optional.empty();
                    }

                    lobby.setCurrentQuestionId(gameServiceInstance.getCurrentQuestionId());
                    loadNewQuestionIntoLobby(lobby, gameServiceInstance);

                    lobby.setCurrentRoundNumber(1);
                    lobby.setTotalRounds(settings.numberOfRounds());
                    lobby.setTeam1Score(0); lobby.setTeam2Score(0);
                    lobby.setTeam1Errors(0); lobby.setTeam2Errors(0);
                    lobby.setCurrentRoundPoints(0);
                    lobby.setRevealedAnswersCountInRound(0);
                    lobby.setTeam1Turn(true);
                    lobby.setCurrentPlayerSessionId(teams.get(teamBlueName).get(0).sessionId());
                    lobby.setStatus(LobbyStatus.BUSY);
                    lobby.startAnswerTimer(timerScheduler);
                } else {
                    lobby.stopAnswerTimer();
                    lobby.resetToAvailable();
                    activeGameServices.remove(lobbyId);
                }
                return Optional.of(lobby);
            }
        }
        return Optional.empty();
    }

    public synchronized void loadNewQuestionForNextRound(String lobbyId, String hostSessionId, Set<Integer> usedQuestionIds) {
        Optional<Lobby> lobbyOpt = getLobby(lobbyId);
        if (lobbyOpt.isEmpty() || !lobbyOpt.get().getHostSessionId().equals(hostSessionId)) {
            return;
        }

        Lobby lobby = lobbyOpt.get();
        GameService gameServiceInstance = activeGameServices.get(lobbyId);
        if (gameServiceInstance == null) {
            webSocketHandler.sendError(hostSessionId, "GAME_SERVICE_NOT_FOUND");
            return;
        }

        boolean questionLoaded = gameServiceInstance.loadQuestion(usedQuestionIds);

        if (questionLoaded) {
            lobby.setCurrentQuestionId(gameServiceInstance.getCurrentQuestionId());
            loadNewQuestionIntoLobby(lobby, gameServiceInstance);
            webSocketHandler.broadcastLobbyUpdate(lobby);
        } else {
            webSocketHandler.sendError(hostSessionId, "NO_QUESTIONS_AVAILABLE");
        }
    }

    private void loadNewQuestionIntoLobby(Lobby lobby, GameService gameServiceInstance) {
        lobby.setCurrentQuestionText(gameServiceInstance.getCurrentQuestion());
        lobby.setCurrentQuestionId(gameServiceInstance.getCurrentQuestionId());
        List<Map<String, Object>> initialAnswersData = new ArrayList<>();
        List<GameService.AnswerData> allAnswers = gameServiceInstance.getAllAnswersForCurrentQuestion();
        if (allAnswers != null) {
            for (int i = 0; i < allAnswers.size(); i++) {
                GameService.AnswerData ad = allAnswers.get(i);
                Map<String, Object> answerMap = new HashMap<>();
                answerMap.put("text", ad.originalText());
                answerMap.put("points", ad.points());
                answerMap.put("isRevealed", false);
                answerMap.put("position", i);
                initialAnswersData.add(answerMap);
            }
        }
        lobby.setRevealedAnswersData(initialAnswersData);
        lobby.setRevealedAnswersCountInRound(0);
        lobby.setCurrentRoundPoints(0);
        lobby.setTeam1Errors(0);
        lobby.setTeam2Errors(0);
    }

    public synchronized Optional<Lobby> processPlayerAnswer(String lobbyId, String answeringPlayerSessionId, String answerText) {
        Optional<Lobby> lobbyOpt = getLobby(lobbyId);
        if (lobbyOpt.isEmpty()) {
            return Optional.empty();
        }
        Lobby lobby = lobbyOpt.get();

        if (lobby.getHostPanelSessionId() != null) {
            lobby.stopAnswerTimer();
            lobby.setStatus(LobbyStatus.VALIDATING);
        } else {
            GameService gameServiceInstance = activeGameServices.get(lobbyId);
            if (gameServiceInstance == null) {
                return Optional.empty();
            }

            PlayerInfo answeringPlayer = lobby.findParticipantBySessionId(answeringPlayerSessionId);
            if (answeringPlayer == null || !answeringPlayer.sessionId().equals(lobby.getCurrentPlayerSessionId())) {
                return Optional.empty();
            }

            AnswerProcessingResult result = gameServiceInstance.processPlayerAnswer(answerText);
            lobby.processAnswer(answeringPlayer, result, gameServiceInstance, false);

            handleRoundOrGameEnd(lobby, gameServiceInstance);
        }

        return Optional.of(lobby);
    }

    public synchronized Optional<Lobby> validateAnswerByQuizMaster(String lobbyId, String answeringPlayerSessionId, boolean isCorrect, String matchedAnswerText) {
        Optional<Lobby> lobbyOpt = getLobby(lobbyId);
        GameService gameServiceInstance = activeGameServices.get(lobbyId);
        if (lobbyOpt.isEmpty() || gameServiceInstance == null) return Optional.empty();
        Lobby lobby = lobbyOpt.get();

        if (lobby.getStatus() != LobbyStatus.VALIDATING) return Optional.empty();

        PlayerInfo answeringPlayer = lobby.findParticipantBySessionId(answeringPlayerSessionId);
        if (answeringPlayer == null) return Optional.empty();

        AnswerProcessingResult result;
        if (isCorrect) {
            Optional<GameService.AnswerData> matchedData = gameServiceInstance.getAllAnswersForCurrentQuestion().stream()
                    .filter(ad -> ad.originalText().equalsIgnoreCase(matchedAnswerText))
                    .findFirst();

            if (matchedData.isPresent()) {
                result = new AnswerProcessingResult(true, matchedData.get().points(), matchedData.get().originalText(), matchedData.get().displayOrderIndex(), matchedData.get().baseForm());
            } else {
                result = new AnswerProcessingResult(false, 0, null, -1, null);
            }
        } else {
            result = new AnswerProcessingResult(false, 0, null, -1, null);
        }

        lobby.processValidatedAnswer(answeringPlayer, result, gameServiceInstance, false);
        handleRoundOrGameEnd(lobby, gameServiceInstance);
        return Optional.of(lobby);
    }

    private void handleRoundOrGameEnd(Lobby lobby, GameService gameServiceInstance) {
        if (lobby.getStatus() == LobbyStatus.BUSY && lobby.getCurrentQuestionText() == null && lobby.getCurrentRoundNumber() <= lobby.getTotalRounds()) {
            webSocketHandler.sendError(lobby.getHostSessionId(), "REQUEST_NEW_QUESTION_DATA");
        } else if (lobby.getStatus() == LobbyStatus.BUSY && lobby.getCurrentPlayerSessionId() != null && lobby.getCurrentQuestionText() != null && !lobby.getCurrentQuestionText().startsWith("Koniec gry!")) {
            lobby.startAnswerTimer(timerScheduler);
        } else if (lobby.getCurrentQuestionText() != null && lobby.getCurrentQuestionText().startsWith("Koniec gry!")) {
            lobby.stopAnswerTimer();
        }
    }
}