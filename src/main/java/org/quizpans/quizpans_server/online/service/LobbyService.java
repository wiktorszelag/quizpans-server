package org.quizpans.quizpans_server.online.service;

import org.quizpans.quizpans_server.online.model.Lobby;
import org.quizpans.quizpans_server.online.model.LobbyStatus;
import org.quizpans.quizpans_server.online.model.GameSettings;
import org.quizpans.quizpans_server.online.model.PlayerInfo;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.stream.Collectors;

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
                return Optional.of(lobby);
            } else if (lobby.getStatus() == LobbyStatus.BUSY && lobby.getHostSessionId() != null && lobby.getHostSessionId().equals(hostSessionId)) {
                return Optional.of(lobby);
            } else if (lobby.getStatus() == LobbyStatus.BUSY && lobby.getHostSessionId() == null) {
                lobby.setHostSessionId(hostSessionId);
                return Optional.of(lobby);
            }
        }
        return Optional.empty();
    }

    public synchronized Optional<Lobby> finalizeLobbyConfiguration(String lobbyId, String hostSessionId, GameSettings settings, String password) {
        Optional<Lobby> lobbyOpt = getLobby(lobbyId);
        if (lobbyOpt.isPresent()) {
            Lobby lobby = lobbyOpt.get();
            if (lobby.getStatus() == LobbyStatus.BUSY && lobby.getHostSessionId() != null && lobby.getHostSessionId().equals(hostSessionId)) {

                System.out.println("SERVER (LobbyService - finalizeLobbyConfiguration): PRZED setGameSettings. WaitingPlayers: " + new ArrayList<>(lobby.getWaitingPlayers()));
                System.out.println("SERVER (LobbyService - finalizeLobbyConfiguration): PRZED setGameSettings. Teams: " + new HashMap<>(lobby.getTeams()));
                System.out.println("SERVER (LobbyService - finalizeLobbyConfiguration): PRZED setGameSettings. PlayersInTeams: " + new ArrayList<>(lobby.getPlayersInTeams()));
                System.out.println("SERVER (LobbyService - finalizeLobbyConfiguration): PRZED setGameSettings. QM: " + lobby.getQuizMaster());
                System.out.println("SERVER (LobbyService - finalizeLobbyConfiguration): Otrzymane nowe GameSettings: " + settings);

                lobby.setGameSettings(settings);
                lobby.setPassword(password);

                System.out.println("SERVER (LobbyService - finalizeLobbyConfiguration): PO setGameSettings. WaitingPlayers: " + new ArrayList<>(lobby.getWaitingPlayers()));
                System.out.println("SERVER (LobbyService - finalizeLobbyConfiguration): PO setGameSettings. Teams: " + new HashMap<>(lobby.getTeams()));
                System.out.println("SERVER (LobbyService - finalizeLobbyConfiguration): PO setGameSettings. PlayersInTeams: " + new ArrayList<>(lobby.getPlayersInTeams()));
                System.out.println("SERVER (LobbyService - finalizeLobbyConfiguration): PO setGameSettings. QM: " + lobby.getQuizMaster());

                return Optional.of(lobby);
            }
        }
        return Optional.empty();
    }

    public synchronized Optional<Lobby> addWaitingPlayerWithPasswordCheck(String lobbyId, PlayerInfo player, String providedPassword) {
        Optional<Lobby> lobbyOpt = getLobby(lobbyId);
        if (lobbyOpt.isPresent()) {
            Lobby lobby = lobbyOpt.get();
            if (lobby.getStatus() == LobbyStatus.BUSY && lobby.getHostSessionId() != null) {
                String lobbyPassword = lobby.getPassword();
                boolean passwordRequired = lobbyPassword != null && !lobbyPassword.isEmpty();
                if (passwordRequired && (providedPassword == null || !lobbyPassword.equals(providedPassword))) {
                    return Optional.empty();
                }
                if (lobby.addWaitingPlayer(player)) {
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
        if (participant == null) {
            participant = lobby.getWaitingPlayers().stream().filter(p->p.sessionId().equals(participantSessionId)).findFirst().orElse(null);
            if(participant == null) return Optional.empty();
        }

        boolean success = false;
        if ("QUIZ_MASTER".equalsIgnoreCase(role)) {
            lobby.setQuizMaster(participant);
            success = true;
        } else if ("PLAYER".equalsIgnoreCase(role)) {
            if (targetTeamName == null || targetTeamName.trim().isEmpty()) return Optional.empty();
            lobby.unassignAndMoveToWaiting(participantSessionId);
            success = lobby.assignWaitingPlayerToTeam(participantSessionId, targetTeamName);
        }
        return success ? Optional.of(lobby) : Optional.empty();
    }

    public synchronized Optional<Lobby> unassignParticipant(String lobbyId, String hostSessionId, String participantToUnassignSessionId) {
        Optional<Lobby> lobbyOpt = getLobby(lobbyId);
        if (lobbyOpt.isEmpty() || lobbyOpt.get().getHostSessionId() == null || !lobbyOpt.get().getHostSessionId().equals(hostSessionId)) {
            return Optional.empty();
        }
        return lobbyOpt.get().unassignAndMoveToWaiting(participantToUnassignSessionId) ? lobbyOpt : Optional.empty();
    }

    public synchronized Optional<Lobby> resetLobbyDueToHostDisconnect(String lobbyId) {
        Optional<Lobby> lobbyOpt = getLobby(lobbyId);
        if (lobbyOpt.isPresent()) {
            Lobby lobby = lobbyOpt.get();
            lobby.stopAnswerTimer();
            lobby.setHostSessionId(null);
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
            boolean wasHost = playerSessionIdToRemove.equals(lobby.getHostSessionId());
            if (lobby.removePlayer(playerSessionIdToRemove)) {
                if (wasHost && lobby.getHostSessionId() == null &&
                        lobby.getPlayersInTeams().isEmpty() && lobby.getWaitingPlayers().isEmpty() && lobby.getQuizMaster() == null) {
                    lobby.stopAnswerTimer();
                    lobby.resetToAvailable();
                    activeGameServices.remove(lobbyId);
                }
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

    public synchronized Optional<Lobby> setLobbyGameStatus(String lobbyId, String hostSessionId, boolean gameInProgress) {
        Optional<Lobby> lobbyOpt = getLobby(lobbyId);
        if (lobbyOpt.isPresent()) {
            Lobby lobby = lobbyOpt.get();
            if (lobby.getHostSessionId() != null && lobby.getHostSessionId().equals(hostSessionId)) {
                if (gameInProgress) {
                    GameSettings settings = lobby.getGameSettings();
                    if (settings == null) return Optional.empty();

                    String teamBlueName = (settings.teamBlueName() != null && !settings.teamBlueName().isEmpty()) ? settings.teamBlueName() : "Niebiescy";
                    String teamRedName = (settings.teamRedName() != null && !settings.teamRedName().isEmpty()) ? settings.teamRedName() : "Czerwoni";
                    List<PlayerInfo> teamBluePlayers = lobby.getTeams().getOrDefault(teamBlueName, new ArrayList<>());
                    List<PlayerInfo> teamRedPlayers = lobby.getTeams().getOrDefault(teamRedName, new ArrayList<>());

                    if (teamBluePlayers.isEmpty() || teamRedPlayers.isEmpty()) {
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
                    if (gameServiceInstance.getCurrentQuestion() == null || gameServiceInstance.getAllAnswersForCurrentQuestion().isEmpty()) {
                        gameServiceInstance.loadQuestion();
                        if (gameServiceInstance.getCurrentQuestion() == null || gameServiceInstance.getAllAnswersForCurrentQuestion().isEmpty()) {
                            return Optional.empty();
                        }
                    }

                    loadNewQuestionIntoLobby(lobby, gameServiceInstance);

                    lobby.setCurrentRoundNumber(1);
                    lobby.setTotalRounds(settings.numberOfRounds());
                    lobby.setTeam1Score(0); lobby.setTeam2Score(0);
                    lobby.setTeam1Errors(0); lobby.setTeam2Errors(0);
                    lobby.setCurrentRoundPoints(0); lobby.setRevealedAnswersCountInRound(0);
                    lobby.setTeam1Turn(true);
                    lobby.setCurrentPlayerSessionId(teamBluePlayers.get(0).sessionId());
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

    private void loadNewQuestionIntoLobby(Lobby lobby, GameService gameServiceInstance) {
        lobby.setCurrentQuestionText(gameServiceInstance.getCurrentQuestion());
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
        GameService gameServiceInstance = activeGameServices.get(lobbyId);

        if (lobbyOpt.isEmpty() || gameServiceInstance == null) return Optional.empty();

        Lobby lobby = lobbyOpt.get();
        PlayerInfo answeringPlayer = lobby.findParticipantBySessionId(answeringPlayerSessionId);

        if (answeringPlayer == null || !answeringPlayer.sessionId().equals(lobby.getCurrentPlayerSessionId())) {
            return Optional.empty();
        }

        AnswerProcessingResult result = gameServiceInstance.processPlayerAnswer(answerText);
        lobby.processAnswer(answeringPlayer, result, gameServiceInstance, false);

        if (lobby.getStatus() == LobbyStatus.BUSY && lobby.getCurrentQuestionText() == null && lobby.getCurrentRoundNumber() <= lobby.getTotalRounds()) {
            gameServiceInstance.loadQuestion();
            if (gameServiceInstance.getCurrentQuestion() == null || gameServiceInstance.getAllAnswersForCurrentQuestion().isEmpty()) {
                lobby.setCurrentQuestionText("Koniec gry! Brak więcej pytań.");
                lobby.setStatus(LobbyStatus.AVAILABLE);
                lobby.stopAnswerTimer();
            } else {
                loadNewQuestionIntoLobby(lobby, gameServiceInstance);

                List<PlayerInfo> startingTeamPlayers = lobby.isTeam1Turn() ?
                        lobby.getTeams().get(lobby.getGameSettings().teamBlueName()) :
                        lobby.getTeams().get(lobby.getGameSettings().teamRedName());
                if (startingTeamPlayers != null && !startingTeamPlayers.isEmpty()) {
                    lobby.setCurrentPlayerSessionId(startingTeamPlayers.get(0).sessionId());
                } else {
                    lobby.setCurrentPlayerSessionId(null);
                }
                lobby.startAnswerTimer(timerScheduler);
            }
        } else if (lobby.getStatus() == LobbyStatus.BUSY && lobby.getCurrentPlayerSessionId() != null && lobby.getCurrentQuestionText() != null && !lobby.getCurrentQuestionText().startsWith("Koniec gry!")) {
            lobby.startAnswerTimer(timerScheduler);
        } else if (lobby.getCurrentQuestionText() != null && lobby.getCurrentQuestionText().startsWith("Koniec gry!")) {
            lobby.stopAnswerTimer();
        }
        return Optional.of(lobby);
    }
}