package org.quizpans.quizpans_server.online.model;

import org.quizpans.quizpans_server.game.GameService;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class Lobby {
    private final String id;
    private String name;
    private LobbyStatus status;
    private GameSettings gameSettings;
    private String password;
    private String hostSessionId;
    private String hostPanelSessionId;
    private PlayerInfo quizMaster;

    private final int maxParticipants = 13;
    private final Map<String, PlayerInfo> participants = new ConcurrentHashMap<>();

    private String currentQuestionText;
    private int currentQuestionId = -1;
    private int currentRoundNumber;
    private int totalRounds;
    private String currentPlayerSessionId;
    private boolean isTeam1Turn;
    private int team1Score;
    private int team2Score;
    private int team1Errors;
    private int team2Errors;
    private List<Map<String, Object>> revealedAnswersData;
    private int currentRoundPoints;
    private int revealedAnswersCountInRound;

    private static final int MAX_ERRORS_PER_TEAM_IN_ROUND = 3;
    private boolean stealAttemptInProgress = false;
    private boolean originalTurnTeam1ForSteal;

    private boolean initialControlPhaseActive = true;
    private GameService.AnswerProcessingResult firstPlayerAnswerInControlPhase = null;
    private boolean firstTeamAttemptedInControlPhase = false;

    private transient ScheduledFuture<?> answerTimerTask;
    private int currentAnswerTimeRemaining;
    private transient Consumer<Lobby> onTimerTickOrTimeoutCallback;
    private transient ScheduledExecutorService timerSchedulerInstance;

    public Lobby(String id, String name) {
        this.id = id;
        this.name = name;
        this.status = LobbyStatus.AVAILABLE;
        this.gameSettings = new GameSettings();
        this.revealedAnswersData = new ArrayList<>();
        this.currentAnswerTimeRemaining = 0;
    }

    public void setTimerCallback(Consumer<Lobby> callback) {
        this.onTimerTickOrTimeoutCallback = callback;
    }

    public synchronized void startAnswerTimer(ScheduledExecutorService scheduler) {
        if (scheduler != null) {
            this.timerSchedulerInstance = scheduler;
        }
        stopAnswerTimer();

        if (this.timerSchedulerInstance == null || gameSettings == null || gameSettings.answerTime() <= 0 || status != LobbyStatus.BUSY || currentPlayerSessionId == null || currentQuestionText == null || currentQuestionText.startsWith("Koniec gry!")) {
            this.currentAnswerTimeRemaining = 0;
            if (onTimerTickOrTimeoutCallback != null) {
                onTimerTickOrTimeoutCallback.accept(this);
            }
            return;
        }

        this.currentAnswerTimeRemaining = gameSettings.answerTime();
        if (onTimerTickOrTimeoutCallback != null) {
            onTimerTickOrTimeoutCallback.accept(this);
        }

        answerTimerTask = this.timerSchedulerInstance.scheduleAtFixedRate(() -> {
            synchronized (this) {
                if (status != LobbyStatus.BUSY) {
                    stopAnswerTimer();
                    return;
                }
                currentAnswerTimeRemaining--;

                if (onTimerTickOrTimeoutCallback != null) {
                    onTimerTickOrTimeoutCallback.accept(this);
                }

                if (currentAnswerTimeRemaining <= 0) {
                    stopAnswerTimer();
                    PlayerInfo timedOutPlayer = findParticipantBySessionId(currentPlayerSessionId);
                    if (timedOutPlayer != null) {
                        GameService.AnswerProcessingResult timeoutResult = new GameService.AnswerProcessingResult(false, 0, null, -1, null);
                        if (hostPanelSessionId != null) {
                            processValidatedAnswer(timedOutPlayer, timeoutResult, null, true);
                        } else {
                            processAnswer(timedOutPlayer, timeoutResult, null, true);
                        }
                    }
                    if (onTimerTickOrTimeoutCallback != null) {
                        onTimerTickOrTimeoutCallback.accept(this);
                    }
                }
            }
        }, 1, 1, TimeUnit.SECONDS);
    }

    public synchronized void stopAnswerTimer() {
        if (answerTimerTask != null && !answerTimerTask.isDone()) {
            answerTimerTask.cancel(false);
        }
        answerTimerTask = null;
    }

    private void handleGameLogic(PlayerInfo answeringPlayer, GameService.AnswerProcessingResult result, GameService gameServiceInstance) {
        if (initialControlPhaseActive) {
            if (!firstTeamAttemptedInControlPhase) {
                firstTeamAttemptedInControlPhase = true;
                if (result.isCorrect && !isAnswerAlreadyRevealed(result.originalAnswerText)) {
                    firstPlayerAnswerInControlPhase = result;
                    revealAnswerData(result.originalAnswerText);
                    currentRoundPoints += result.pointsAwarded;
                    revealedAnswersCountInRound++;
                    if (result.answerIndex == 0) {
                        gainControlAndContinue(gameServiceInstance);
                    } else {
                        switchTeamForControlAttempt(gameServiceInstance);
                    }
                } else {
                    firstPlayerAnswerInControlPhase = null;
                    switchTeamForControlAttempt(gameServiceInstance);
                }
            } else {
                if (result.isCorrect && !isAnswerAlreadyRevealed(result.originalAnswerText)) {
                    revealAnswerData(result.originalAnswerText);
                    currentRoundPoints += result.pointsAwarded;
                    revealedAnswersCountInRound++;
                    if (firstPlayerAnswerInControlPhase == null || result.answerIndex < firstPlayerAnswerInControlPhase.answerIndex) {
                        gainControlAndContinue(gameServiceInstance);
                    } else {
                        isTeam1Turn = !isTeam1Turn;
                        gainControlAndContinue(gameServiceInstance);
                    }
                } else {
                    if (firstPlayerAnswerInControlPhase != null) {
                        isTeam1Turn = !isTeam1Turn;
                    }
                    gainControlAndContinue(gameServiceInstance);
                }
            }
        } else if (stealAttemptInProgress) {
            if (result.isCorrect && !isAnswerAlreadyRevealed(result.originalAnswerText)) {
                currentRoundPoints += result.pointsAwarded;
                revealAnswerData(result.originalAnswerText);
                revealedAnswersCountInRound++;
                if (isTeam1Turn) team1Score += currentRoundPoints; else team2Score += currentRoundPoints;
            } else {
                if (originalTurnTeam1ForSteal) team1Score += currentRoundPoints; else team2Score += currentRoundPoints;
            }
            finalizeRound(gameServiceInstance, true);
        } else {
            if (result.isCorrect && !isAnswerAlreadyRevealed(result.originalAnswerText)) {
                this.currentRoundPoints += result.pointsAwarded;
                revealedAnswersCountInRound++;
                revealAnswerData(result.originalAnswerText);
                if (isTeam1Turn) team1Errors = 0; else team2Errors = 0;

                if (gameServiceInstance != null && revealedAnswersCountInRound < gameServiceInstance.getTotalAnswersCount()) {
                    moveToNextPlayerInTeam();
                    if (currentPlayerSessionId != null && this.timerSchedulerInstance != null) startAnswerTimer(this.timerSchedulerInstance);
                } else {
                    if (isTeam1Turn) team1Score += currentRoundPoints; else team2Score += currentRoundPoints;
                    finalizeRound(gameServiceInstance, false);
                }
            } else {
                incrementErrorForCurrentTeam();
                if (getCurrentTeamErrors() >= MAX_ERRORS_PER_TEAM_IN_ROUND) {
                    initiateStealAttempt(gameServiceInstance);
                } else {
                    moveToNextPlayerInTeam();
                    if (currentPlayerSessionId != null && this.timerSchedulerInstance != null) startAnswerTimer(this.timerSchedulerInstance);
                }
            }
        }
    }

    public synchronized void processValidatedAnswer(PlayerInfo answeringPlayer, GameService.AnswerProcessingResult result, GameService gameServiceInstance, boolean isTimeout) {
        if (!isTimeout) stopAnswerTimer();
        this.status = LobbyStatus.BUSY;
        handleGameLogic(answeringPlayer, result, gameServiceInstance);
    }

    public synchronized void processAnswer(PlayerInfo answeringPlayer, GameService.AnswerProcessingResult result, GameService gameServiceInstance, boolean isTimeout) {
        if (status != LobbyStatus.BUSY || currentQuestionText == null || currentQuestionText.startsWith("Koniec gry!")) {
            return;
        }
        if (!isTimeout && (answeringPlayer == null || !answeringPlayer.sessionId().equals(this.currentPlayerSessionId))) {
            return;
        }
        if (!isTimeout) {
            stopAnswerTimer();
        }
        handleGameLogic(answeringPlayer, result, gameServiceInstance);
    }

    private void gainControlAndContinue(GameService gameServiceInstance) {
        initialControlPhaseActive = false;
        firstPlayerAnswerInControlPhase = null;
        firstTeamAttemptedInControlPhase = false;
        if (isTeam1Turn) team1Errors = 0; else team2Errors = 0;
        moveToNextPlayerInTeam();
        if (currentPlayerSessionId != null && this.timerSchedulerInstance != null) startAnswerTimer(this.timerSchedulerInstance);
    }

    private void switchTeamForControlAttempt(GameService gameServiceInstance) {
        isTeam1Turn = !isTeam1Turn;
        List<PlayerInfo> nextTeamPlayers = getCurrentTeamPlayers();
        if (nextTeamPlayers != null && !nextTeamPlayers.isEmpty()) {
            currentPlayerSessionId = nextTeamPlayers.get(0).sessionId();
            if (this.timerSchedulerInstance != null) startAnswerTimer(this.timerSchedulerInstance);
        } else {
            isTeam1Turn = !isTeam1Turn;
            gainControlAndContinue(gameServiceInstance);
        }
    }

    private boolean isAnswerAlreadyRevealed(String answerText) {
        if (answerText == null) return true;
        return revealedAnswersData.stream()
                .filter(answerMap -> (Boolean) answerMap.get("isRevealed"))
                .anyMatch(answerMap -> {
                    Object text = answerMap.get("text");
                    return text != null && text.toString().equalsIgnoreCase(answerText);
                });
    }

    public void revealAnswerData(String answerText) {
        if (answerText == null) return;
        for (Map<String, Object> answerMap : revealedAnswersData) {
            if (answerMap.get("text") != null && answerMap.get("text").toString().equalsIgnoreCase(answerText)) {
                answerMap.put("isRevealed", true);
                break;
            }
        }
    }

    private void initiateStealAttempt(GameService gameServiceInstance) {
        stopAnswerTimer();
        initialControlPhaseActive = false;
        stealAttemptInProgress = true;
        originalTurnTeam1ForSteal = isTeam1Turn;
        isTeam1Turn = !isTeam1Turn;

        List<PlayerInfo> stealingTeamPlayers = getCurrentTeamPlayers();
        if (stealingTeamPlayers != null && !stealingTeamPlayers.isEmpty()) {
            this.currentPlayerSessionId = stealingTeamPlayers.get(0).sessionId();
            if (this.timerSchedulerInstance != null) startAnswerTimer(this.timerSchedulerInstance);
        } else {
            if (originalTurnTeam1ForSteal) team1Score += currentRoundPoints; else team2Score += currentRoundPoints;
            finalizeRound(gameServiceInstance, true);
        }
    }

    public void finalizeRound(GameService gameServiceInstance, boolean stealJustResolved) {
        stopAnswerTimer();
        stealAttemptInProgress = false;
        initialControlPhaseActive = true;
        firstPlayerAnswerInControlPhase = null;
        firstTeamAttemptedInControlPhase = false;

        if (currentRoundNumber >= totalRounds) {
            this.currentQuestionText = "Koniec gry! Wynik " + getTeam1Name() + ": " + team1Score + ", " + getTeam2Name() + ": " + team2Score;
            this.currentPlayerSessionId = null;
            this.status = LobbyStatus.AVAILABLE;
        } else {
            currentRoundNumber++;
            currentRoundPoints = 0;
            team1Errors = 0;
            team2Errors = 0;
            revealedAnswersCountInRound = 0;

            if (stealJustResolved) {
                isTeam1Turn = originalTurnTeam1ForSteal;
            }
            isTeam1Turn = !isTeam1Turn;

            this.currentQuestionText = null;
            this.revealedAnswersData.clear();

            List<PlayerInfo> startingTeamPlayers = getCurrentTeamPlayers();
            if (startingTeamPlayers != null && !startingTeamPlayers.isEmpty()) {
                this.currentPlayerSessionId = startingTeamPlayers.get(0).sessionId();
            } else {
                this.currentPlayerSessionId = null;
            }
        }
    }

    public String getTeam1Name() {
        return (this.gameSettings != null && this.gameSettings.teamBlueName() != null && !this.gameSettings.teamBlueName().isEmpty()) ? this.gameSettings.teamBlueName() : "Niebiescy";
    }

    public String getTeam2Name() {
        return (this.gameSettings != null && this.gameSettings.teamRedName() != null && !this.gameSettings.teamRedName().isEmpty()) ? this.gameSettings.teamRedName() : "Czerwoni";
    }

    public void incrementErrorForCurrentTeam() {
        if (isTeam1Turn) {
            team1Errors++;
        } else {
            team2Errors++;
        }
    }

    public int getCurrentTeamErrors() {
        return isTeam1Turn ? team1Errors : team2Errors;
    }

    public void moveToNextPlayerInTeam() {
        List<PlayerInfo> currentTeamPlayers = getCurrentTeamPlayers();
        if (currentTeamPlayers == null || currentTeamPlayers.isEmpty()) {
            if (!stealAttemptInProgress && !initialControlPhaseActive) {
                switchTurnAndTeam();
            } else if (stealAttemptInProgress) {
                if (originalTurnTeam1ForSteal) team1Score += currentRoundPoints; else team2Score += currentRoundPoints;
                finalizeRound(null, true);
            } else {
                isTeam1Turn = !isTeam1Turn;
                gainControlAndContinue(null);
            }
            return;
        }

        int currentPlayerIdx = -1;
        for (int i = 0; i < currentTeamPlayers.size(); i++) {
            if (currentTeamPlayers.get(i).sessionId().equals(currentPlayerSessionId)) {
                currentPlayerIdx = i;
                break;
            }
        }

        if (currentPlayerIdx != -1) {
            this.currentPlayerSessionId = currentTeamPlayers.get((currentPlayerIdx + 1) % currentTeamPlayers.size()).sessionId();
        } else if (!currentTeamPlayers.isEmpty()) {
            this.currentPlayerSessionId = currentTeamPlayers.get(0).sessionId();
        } else {
            this.currentPlayerSessionId = null;
        }
    }

    private void switchTurnAndTeam() {
        isTeam1Turn = !isTeam1Turn;
        List<PlayerInfo> nextTeamPlayers = getCurrentTeamPlayers();
        if (nextTeamPlayers != null && !nextTeamPlayers.isEmpty()) {
            this.currentPlayerSessionId = nextTeamPlayers.get(0).sessionId();
        } else {
            this.currentPlayerSessionId = null;
        }
    }

    public List<PlayerInfo> getCurrentTeamPlayers() {
        String currentTeamNameKey = isTeam1Turn ? getTeam1Name() : getTeam2Name();
        return participants.values().stream()
                .filter(p -> p.getRole() == ParticipantRole.PLAYER && currentTeamNameKey.equals(p.teamName()))
                .collect(Collectors.toList());
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public LobbyStatus getStatus() { return status; }
    public synchronized void setStatus(LobbyStatus status) { this.status = status; }
    public GameSettings getGameSettings() { return gameSettings; }

    public synchronized void setGameSettings(GameSettings newGameSettings) {
        if (newGameSettings == null) {
            return;
        }
        this.gameSettings = newGameSettings;
    }

    public String getPassword() { return password; }
    public synchronized void setPassword(String password) { this.password = password; }
    public String getHostSessionId() { return hostSessionId; }
    public String getHostPanelSessionId() { return hostPanelSessionId; }
    public void setHostPanelSessionId(String hostPanelSessionId) { this.hostPanelSessionId = hostPanelSessionId; }

    public synchronized void setHostSessionId(String hostSessionId) {
        this.hostSessionId = hostSessionId;
    }
    public PlayerInfo getQuizMaster() { return quizMaster; }

    public synchronized void setQuizMaster(PlayerInfo participant) {
        if (this.quizMaster != null && !this.quizMaster.equals(participant)) {
            PlayerInfo oldQm = participants.get(this.quizMaster.sessionId());
            if (oldQm != null) {
                oldQm.setRole(ParticipantRole.PLAYER);
                oldQm.setTeamName(null);
            }
        }

        this.quizMaster = participant;

        if (participant != null) {
            PlayerInfo participantInMap = participants.get(participant.sessionId());
            if (participantInMap != null) {
                participantInMap.setRole(ParticipantRole.QUIZ_MASTER);
                participantInMap.setTeamName(null);
                this.quizMaster = participantInMap;
            }
        }
    }

    public List<PlayerInfo> getWaitingPlayers() {
        return participants.values().stream()
                .filter(p -> p.getRole() == ParticipantRole.PLAYER && p.teamName() == null)
                .collect(Collectors.toList());
    }

    public int getTotalParticipantCount() {
        return participants.size();
    }

    public int getMaxParticipants() { return maxParticipants; }

    public Map<String, List<PlayerInfo>> getTeams() {
        return participants.values().stream()
                .filter(p -> p.getRole() == ParticipantRole.PLAYER && p.teamName() != null)
                .collect(Collectors.groupingBy(PlayerInfo::teamName));
    }

    public synchronized boolean addPlayer(PlayerInfo player) {
        if (player == null || player.sessionId() == null) return false;
        if (participants.containsKey(player.sessionId())) return false;
        if (participants.size() >= maxParticipants) return false;
        participants.put(player.sessionId(), player);
        return true;
    }

    public synchronized boolean assignPlayerToTeam(String sessionId, String targetTeamName) {
        PlayerInfo player = participants.get(sessionId);
        if (player == null || targetTeamName == null) return false;

        long teamSize = participants.values().stream()
                .filter(p -> targetTeamName.equals(p.teamName()))
                .count();

        if (gameSettings != null && teamSize >= gameSettings.maxPlayersPerTeam()) {
            return false;
        }

        if (quizMaster != null && quizMaster.sessionId().equals(sessionId)) {
            setQuizMaster(null);
        }

        player.setTeamName(targetTeamName);
        player.setRole(ParticipantRole.PLAYER);
        return true;
    }

    public synchronized boolean removePlayer(String sessionId) {
        PlayerInfo removedPlayer = participants.remove(sessionId);
        if (removedPlayer != null) {
            if (quizMaster != null && quizMaster.sessionId().equals(sessionId)) {
                quizMaster = null;
            }
            if (hostPanelSessionId != null && hostPanelSessionId.equals(sessionId)) {
                hostPanelSessionId = null;
            }
            if (hostSessionId != null && hostSessionId.equals(sessionId)) {
                this.hostSessionId = null;
                if (participants.isEmpty()) {
                    resetToAvailable();
                }
            }
            return true;
        }
        return false;
    }

    public synchronized boolean unassignPlayer(String sessionId) {
        PlayerInfo player = participants.get(sessionId);
        if (player != null) {
            if (quizMaster != null && quizMaster.sessionId().equals(sessionId)) {
                quizMaster = null;
            }
            player.setRole(ParticipantRole.PLAYER);
            player.setTeamName(null);
            return true;
        }
        return false;
    }

    public PlayerInfo findParticipantBySessionId(String sessionId) {
        return participants.get(sessionId);
    }

    public synchronized void resetToAvailable() {
        stopAnswerTimer();
        this.status = LobbyStatus.AVAILABLE;
        this.gameSettings = new GameSettings();
        this.password = null;
        this.hostSessionId = null;
        this.hostPanelSessionId = null;
        this.quizMaster = null;
        this.participants.clear();
        this.currentQuestionText = null;
        this.currentQuestionId = -1;
        this.currentRoundNumber = 0;
        this.totalRounds = 0;
        this.currentPlayerSessionId = null;
        this.isTeam1Turn = true;
        this.team1Score = 0;
        this.team2Score = 0;
        this.team1Errors = 0;
        this.team2Errors = 0;
        this.revealedAnswersData = new ArrayList<>();
        this.currentRoundPoints = 0;
        this.revealedAnswersCountInRound = 0;
        this.currentAnswerTimeRemaining = 0;
        this.stealAttemptInProgress = false;
        this.timerSchedulerInstance = null;
        this.initialControlPhaseActive = true;
        this.firstPlayerAnswerInControlPhase = null;
        this.firstTeamAttemptedInControlPhase = false;
    }

    public String getCurrentQuestionText() { return currentQuestionText; }
    public void setCurrentQuestionText(String currentQuestionText) { this.currentQuestionText = currentQuestionText; }
    public int getCurrentQuestionId() { return currentQuestionId; }
    public void setCurrentQuestionId(int id) { this.currentQuestionId = id; }
    public int getCurrentRoundNumber() { return currentRoundNumber; }
    public void setCurrentRoundNumber(int currentRoundNumber) { this.currentRoundNumber = currentRoundNumber; }
    public int getTotalRounds() { return totalRounds; }
    public void setTotalRounds(int totalRounds) { this.totalRounds = totalRounds; }
    public String getCurrentPlayerSessionId() { return currentPlayerSessionId; }
    public void setCurrentPlayerSessionId(String currentPlayerSessionId) { this.currentPlayerSessionId = currentPlayerSessionId; }
    public boolean isTeam1Turn() { return isTeam1Turn; }
    public void setTeam1Turn(boolean team1Turn) { this.isTeam1Turn = team1Turn; }
    public int getTeam1Score() { return team1Score; }
    public void setTeam1Score(int team1Score) { this.team1Score = team1Score; }
    public int getTeam2Score() { return team2Score; }
    public void setTeam2Score(int team2Score) { this.team2Score = team2Score; }
    public int getTeam1Errors() { return team1Errors; }
    public void setTeam1Errors(int team1Errors) { this.team1Errors = team1Errors; }
    public int getTeam2Errors() { return team2Errors; }
    public void setTeam2Errors(int team2Errors) { this.team2Errors = team2Errors; }
    public List<Map<String, Object>> getRevealedAnswersData() { return revealedAnswersData; }
    public void setRevealedAnswersData(List<Map<String, Object>> revealedAnswersData) { this.revealedAnswersData = revealedAnswersData; }
    public int getCurrentRoundPoints() { return currentRoundPoints; }
    public void setCurrentRoundPoints(int currentRoundPoints) { this.currentRoundPoints = currentRoundPoints; }
    public int getRevealedAnswersCountInRound() { return revealedAnswersCountInRound; }
    public void setRevealedAnswersCountInRound(int count) { this.revealedAnswersCountInRound = count; }

    public boolean isStealAttemptInProgress() { return stealAttemptInProgress; }
    public void setStealAttemptInProgress(boolean stealAttemptInProgress) { this.stealAttemptInProgress = stealAttemptInProgress; }

    public boolean isInitialControlPhaseActive() { return initialControlPhaseActive; }
    public void setInitialControlPhaseActive(boolean initialControlPhaseActive) { this.initialControlPhaseActive = initialControlPhaseActive; }

    public int getCurrentAnswerTimeRemaining() { return currentAnswerTimeRemaining; }
}