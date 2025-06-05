package org.quizpans.quizpans_server.online.model;

import org.quizpans.quizpans_server.game.GameService;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
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
    private PlayerInfo quizMaster;

    private final int maxParticipants = 13;
    private final List<PlayerInfo> playersInTeams;
    private final List<PlayerInfo> waitingPlayers;
    private final Map<String, List<PlayerInfo>> teams;

    private String currentQuestionText;
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
        this.playersInTeams = new ArrayList<>();
        this.waitingPlayers = new ArrayList<>();
        this.teams = new ConcurrentHashMap<>();
        this.quizMaster = null;
        this.revealedAnswersData = new ArrayList<>();
        this.currentAnswerTimeRemaining = 0;
        initializeTeamsBasedOnSettings();
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
                if (status != LobbyStatus.BUSY || currentPlayerSessionId == null || (currentQuestionText != null && currentQuestionText.startsWith("Koniec gry!"))) {
                    stopAnswerTimer();
                    if (onTimerTickOrTimeoutCallback != null) onTimerTickOrTimeoutCallback.accept(this);
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
                        processAnswer(timedOutPlayer, new GameService.AnswerProcessingResult(false, 0, null, -1, null), null, true);
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

    public int getCurrentAnswerTimeRemaining() {
        return currentAnswerTimeRemaining;
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
            // No players in other team, original team effectively wins control by default
            isTeam1Turn = !isTeam1Turn; // Switch back
            gainControlAndContinue(gameServiceInstance);
        }
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

                if (gameServiceInstance != null && revealedAnswersCountInRound < gameServiceInstance.getAllAnswersForCurrentQuestion().size()) {
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

    private boolean isAnswerAlreadyRevealed(String answerText) {
        if (answerText == null) return true;
        return revealedAnswersData.stream()
                .filter(answerMap -> (Boolean) answerMap.get("isRevealed"))
                .anyMatch(answerMap -> {
                    Object text = answerMap.get("text");
                    return text != null && text.toString().equalsIgnoreCase(answerText);
                });
    }

    private void revealAnswerData(String answerText) {
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

    private void finalizeRound(GameService gameServiceInstance, boolean stealJustResolved) {
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

    private void initializeTeamsBasedOnSettings() {
        this.teams.clear();
        String blueName = getTeam1Name();
        String redName = getTeam2Name();
        this.teams.put(blueName, new ArrayList<>());
        this.teams.put(redName, new ArrayList<>());
    }

    public String getTeam1Name() {
        return (this.gameSettings != null && this.gameSettings.teamBlueName() != null && !this.gameSettings.teamBlueName().isEmpty()) ? this.gameSettings.teamBlueName() : "Niebiescy";
    }

    public String getTeam2Name() {
        return (this.gameSettings != null && this.gameSettings.teamRedName() != null && !this.gameSettings.teamRedName().isEmpty()) ? this.gameSettings.teamRedName() : "Czerwoni";
    }

    private void incrementErrorForCurrentTeam() {
        if (isTeam1Turn) {
            team1Errors++;
        } else {
            team2Errors++;
        }
    }

    private int getCurrentTeamErrors() {
        return isTeam1Turn ? team1Errors : team2Errors;
    }

    private void moveToNextPlayerInTeam() {
        List<PlayerInfo> currentTeamPlayers = getCurrentTeamPlayers();
        if (currentTeamPlayers == null || currentTeamPlayers.isEmpty()) {
            if (!stealAttemptInProgress && !initialControlPhaseActive) {
                switchTurnAndTeam();
            } else if (stealAttemptInProgress) {
                if (originalTurnTeam1ForSteal) team1Score += currentRoundPoints; else team2Score += currentRoundPoints;
                finalizeRound(null, true);
            } else { // initialControlPhaseActive and current team empty
                isTeam1Turn = !isTeam1Turn; // Switch to other team
                gainControlAndContinue(null); // Other team gets control by default
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

    private List<PlayerInfo> getCurrentTeamPlayers() {
        String currentTeamNameKey = isTeam1Turn ? getTeam1Name() : getTeam2Name();
        return teams.get(currentTeamNameKey);
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public LobbyStatus getStatus() { return status; }
    public synchronized void setStatus(LobbyStatus status) { this.status = status; }
    public GameSettings getGameSettings() { return gameSettings; }

    public synchronized void setGameSettings(GameSettings newGameSettings) {
        GameSettings oldSettings = this.gameSettings;
        String oldBlueName = (oldSettings != null && oldSettings.teamBlueName() != null && !oldSettings.teamBlueName().isEmpty()) ? oldSettings.teamBlueName() : "Niebiescy";
        String oldRedName = (oldSettings != null && oldSettings.teamRedName() != null && !oldSettings.teamRedName().isEmpty()) ? oldSettings.teamRedName() : "Czerwoni";

        List<PlayerInfo> tempWaitingPlayers = new ArrayList<>(this.waitingPlayers);
        Map<String, List<PlayerInfo>> tempTeams = new HashMap<>();
        if (this.teams.containsKey(oldBlueName)) tempTeams.put(oldBlueName, new ArrayList<>(this.teams.get(oldBlueName)));
        if (this.teams.containsKey(oldRedName)) tempTeams.put(oldRedName, new ArrayList<>(this.teams.get(oldRedName)));
        PlayerInfo tempQm = this.quizMaster;

        this.gameSettings = (newGameSettings == null) ? new GameSettings() : newGameSettings;

        String newBlueTeamName = getTeam1Name();
        String newRedTeamName = getTeam2Name();

        this.teams.clear();
        this.teams.put(newBlueTeamName, new ArrayList<>());
        this.teams.put(newRedTeamName, new ArrayList<>());

        this.playersInTeams.clear();
        this.waitingPlayers.clear();
        this.quizMaster = null;

        if (tempQm != null) {
            setQuizMaster(new PlayerInfo(tempQm.sessionId(), tempQm.nickname(), null));
        }

        List<PlayerInfo> playersFromOldBlue = tempTeams.getOrDefault(oldBlueName, new ArrayList<>());
        for (PlayerInfo p : playersFromOldBlue) {
            if (this.quizMaster != null && this.quizMaster.sessionId().equals(p.sessionId())) continue;
            PlayerInfo updatedP = new PlayerInfo(p.sessionId(), p.nickname(), newBlueTeamName);
            this.teams.get(newBlueTeamName).add(updatedP);
            this.playersInTeams.add(updatedP);
        }
        List<PlayerInfo> playersFromOldRed = tempTeams.getOrDefault(oldRedName, new ArrayList<>());
        for (PlayerInfo p : playersFromOldRed) {
            if (this.quizMaster != null && this.quizMaster.sessionId().equals(p.sessionId())) continue;
            PlayerInfo updatedP = new PlayerInfo(p.sessionId(), p.nickname(), newRedTeamName);
            this.teams.get(newRedTeamName).add(updatedP);
            this.playersInTeams.add(updatedP);
        }

        for (PlayerInfo p : tempWaitingPlayers) {
            if (this.quizMaster != null && this.quizMaster.sessionId().equals(p.sessionId())) continue;
            boolean alreadyInTeamCheck = this.playersInTeams.stream().anyMatch(tp -> tp.sessionId().equals(p.sessionId()));
            if (!alreadyInTeamCheck) {
                addWaitingPlayer(new PlayerInfo(p.sessionId(), p.nickname(), null));
            }
        }
    }

    public String getPassword() { return password; }
    public synchronized void setPassword(String password) { this.password = password; }
    public String getHostSessionId() { return hostSessionId; }

    public synchronized void setHostSessionId(String hostSessionId) {
        this.hostSessionId = hostSessionId;
        if (hostSessionId != null && this.status == LobbyStatus.AVAILABLE) {
            this.status = LobbyStatus.BUSY;
        } else if (hostSessionId == null && this.status == LobbyStatus.BUSY && !isGameEffectivelyInProgressLogic()) {
            if(this.playersInTeams.isEmpty() && this.waitingPlayers.isEmpty() && this.quizMaster == null){
                resetToAvailable();
            }
        }
    }
    public PlayerInfo getQuizMaster() { return quizMaster; }
    public synchronized void setQuizMaster(PlayerInfo newQuizMaster) {
        if (this.quizMaster != null && newQuizMaster != null && this.quizMaster.sessionId().equals(newQuizMaster.sessionId())) {
            return;
        }
        if (this.quizMaster != null) {
            if (newQuizMaster == null || !this.quizMaster.sessionId().equals(newQuizMaster.sessionId())) {
                boolean alreadyWaiting = waitingPlayers.stream().anyMatch(p -> p.sessionId().equals(this.quizMaster.sessionId()));
                boolean inTeam = playersInTeams.stream().anyMatch(p -> p.sessionId().equals(this.quizMaster.sessionId()));
                if(!alreadyWaiting && !inTeam) {
                    addWaitingPlayer(new PlayerInfo(this.quizMaster.sessionId(), this.quizMaster.nickname(), null));
                }
            }
        }
        this.quizMaster = newQuizMaster;
        if (newQuizMaster != null) {
            waitingPlayers.removeIf(p -> p.sessionId().equals(newQuizMaster.sessionId()));
            removePlayerFromAnyTeam(newQuizMaster.sessionId());
            playersInTeams.removeIf(p -> p.sessionId().equals(newQuizMaster.sessionId()));
        }
    }
    private synchronized void removePlayerFromAnyTeam(String sessionId) {
        for (List<PlayerInfo> teamList : teams.values()) {
            teamList.removeIf(pInfo -> pInfo.sessionId().equals(sessionId));
        }
    }
    public List<PlayerInfo> getPlayersInTeams() { return new ArrayList<>(this.playersInTeams); }
    public List<PlayerInfo> getWaitingPlayers() { return new ArrayList<>(this.waitingPlayers); }
    public int getTotalParticipantCount() {
        int count = playersInTeams.size() + waitingPlayers.size();
        if (quizMaster != null) {
            boolean qmIsPlayerInTeam = playersInTeams.stream().anyMatch(p -> p.sessionId().equals(quizMaster.sessionId()));
            boolean qmIsWaiting = waitingPlayers.stream().anyMatch(p -> p.sessionId().equals(quizMaster.sessionId()));
            if(!qmIsPlayerInTeam && !qmIsWaiting) {
                count++;
            }
        }
        return count;
    }
    public int getMaxParticipants() { return maxParticipants; }
    public Map<String, List<PlayerInfo>> getTeams() { return new ConcurrentHashMap<>(this.teams); }
    public synchronized boolean addWaitingPlayer(PlayerInfo player) {
        if (player == null || player.sessionId() == null) return false;
        if (findParticipantBySessionId(player.sessionId()) != null) return false;
        if (getTotalParticipantCount() >= maxParticipants) return false;
        waitingPlayers.add(player);
        return true;
    }
    public synchronized boolean assignWaitingPlayerToTeam(String playerSessionId, String targetTeamName) {
        PlayerInfo playerToAssign = waitingPlayers.stream()
                .filter(p -> p.sessionId().equals(playerSessionId))
                .findFirst()
                .orElse(null);
        if (playerToAssign == null) return false;

        Map<String, List<PlayerInfo>> currentTeamsMap = getTeams();
        if (!currentTeamsMap.containsKey(targetTeamName)) return false;

        List<PlayerInfo> teamList = teams.get(targetTeamName);
        if (gameSettings != null && teamList.size() >= gameSettings.maxPlayersPerTeam()) return false;

        waitingPlayers.remove(playerToAssign);
        PlayerInfo assignedPlayer = new PlayerInfo(playerToAssign.sessionId(), playerToAssign.nickname(), targetTeamName);
        teamList.add(assignedPlayer);
        playersInTeams.add(assignedPlayer);
        return true;
    }
    public synchronized boolean removePlayer(String sessionId) {
        boolean removedFromWaiting = waitingPlayers.removeIf(p -> p.sessionId().equals(sessionId));
        boolean removedFromPlayersInTeamsList = playersInTeams.removeIf(p -> p.sessionId().equals(sessionId));
        boolean removedFromActualTeamStructure = false;
        for (List<PlayerInfo> teamList : teams.values()) {
            if(teamList.removeIf(pInfo -> pInfo.sessionId().equals(sessionId))) {
                removedFromActualTeamStructure = true;
            }
        }
        boolean wasQuizMaster = false;
        if (this.quizMaster != null && sessionId.equals(this.quizMaster.sessionId())) {
            this.quizMaster = null;
            wasQuizMaster = true;
        }
        boolean wasHost = false;
        if (sessionId.equals(this.hostSessionId)) {
            this.hostSessionId = null;
            wasHost = true;
        }
        boolean participantActuallyLeft = removedFromWaiting || removedFromPlayersInTeamsList || removedFromActualTeamStructure || wasQuizMaster || wasHost;
        if (this.hostSessionId == null && this.status == LobbyStatus.BUSY && !isGameEffectivelyInProgressLogic()) {
            if (playersInTeams.isEmpty() && waitingPlayers.isEmpty() && quizMaster == null) {
                resetToAvailable();
            }
        }
        return participantActuallyLeft;
    }
    public synchronized boolean unassignAndMoveToWaiting(String sessionId) {
        PlayerInfo playerInfoToMoveToWaiting = null;
        String originalNickname = null;
        boolean modified = false;

        for (Map.Entry<String, List<PlayerInfo>> entry : teams.entrySet()) {
            Optional<PlayerInfo> playerOpt = entry.getValue().stream()
                    .filter(p -> p.sessionId().equals(sessionId))
                    .findFirst();
            if (playerOpt.isPresent()) {
                playerInfoToMoveToWaiting = playerOpt.get();
                originalNickname = playerInfoToMoveToWaiting.nickname();
                entry.getValue().remove(playerInfoToMoveToWaiting);
                playersInTeams.removeIf(p -> p.sessionId().equals(sessionId));
                modified = true;
                break;
            }
        }
        if (this.quizMaster != null && this.quizMaster.sessionId().equals(sessionId)) {
            if (playerInfoToMoveToWaiting == null) originalNickname = this.quizMaster.nickname();
            else if (originalNickname == null) originalNickname = playerInfoToMoveToWaiting.nickname();
            this.quizMaster = null;
            modified = true;
        }

        if (originalNickname != null) {
            PlayerInfo playerForWaiting = new PlayerInfo(sessionId, originalNickname, null);
            if (waitingPlayers.stream().noneMatch(p -> p.sessionId().equals(sessionId))) {
                if(addWaitingPlayer(playerForWaiting)){
                    modified = true;
                }
            } else {
                modified = true;
            }
        }
        return modified;
    }
    public PlayerInfo findParticipantBySessionId(String sessionId) {
        if (sessionId == null) return null;
        if (this.quizMaster != null && this.quizMaster.sessionId().equals(sessionId)) {
            return this.quizMaster;
        }
        for(List<PlayerInfo> team : teams.values()){
            Optional<PlayerInfo> playerOpt = team.stream().filter(p -> p.sessionId() != null && p.sessionId().equals(sessionId)).findFirst();
            if(playerOpt.isPresent()) return playerOpt.get();
        }
        Optional<PlayerInfo> playerOpt = waitingPlayers.stream().filter(p -> p.sessionId() != null && p.sessionId().equals(sessionId)).findFirst();
        return playerOpt.orElse(null);
    }
    public synchronized void resetToAvailable() {
        stopAnswerTimer();
        this.status = LobbyStatus.AVAILABLE;
        this.gameSettings = new GameSettings();
        this.password = null;
        this.hostSessionId = null;
        this.quizMaster = null;
        this.playersInTeams.clear();
        this.waitingPlayers.clear();
        initializeTeamsBasedOnSettings();
        this.currentQuestionText = null;
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
    private boolean isGameEffectivelyInProgressLogic(){
        return this.status == LobbyStatus.BUSY && this.hostSessionId != null && (this.playersInTeams.size() > 0 || this.quizMaster != null || (this.currentQuestionText != null && !this.currentQuestionText.startsWith("Koniec gry!")));
    }
    public String getCurrentQuestionText() { return currentQuestionText; }
    public void setCurrentQuestionText(String currentQuestionText) { this.currentQuestionText = currentQuestionText; }
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
}