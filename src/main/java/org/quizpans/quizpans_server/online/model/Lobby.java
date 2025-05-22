package org.quizpans.quizpans_server.online.model;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
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

    public Lobby(String id, String name) {
        this.id = id;
        this.name = name;
        this.status = LobbyStatus.AVAILABLE;
        this.gameSettings = new GameSettings();
        this.playersInTeams = new ArrayList<>();
        this.waitingPlayers = new ArrayList<>();
        this.teams = new ConcurrentHashMap<>();
        this.quizMaster = null;
        initializeTeamsBasedOnSettings();
    }

    private void initializeTeamsBasedOnSettings() {
        this.teams.clear();
        if (this.gameSettings != null) {
            if (this.gameSettings.teamBlueName() != null && !this.gameSettings.teamBlueName().isEmpty()) {
                this.teams.put(this.gameSettings.teamBlueName(), new ArrayList<>());
            }
            if (this.gameSettings.teamRedName() != null && !this.gameSettings.teamRedName().isEmpty()) {
                this.teams.put(this.gameSettings.teamRedName(), new ArrayList<>());
            }
        }
        if (this.teams.isEmpty()) {
            this.teams.put("Niebiescy", new ArrayList<>());
            this.teams.put("Czerwoni", new ArrayList<>());
        }
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public LobbyStatus getStatus() { return status; }
    public synchronized void setStatus(LobbyStatus status) { this.status = status; }
    public GameSettings getGameSettings() { return gameSettings; }

    public synchronized void setGameSettings(GameSettings gameSettings) {
        if (gameSettings == null) {
            this.gameSettings = new GameSettings();
        } else {
            this.gameSettings = gameSettings;
        }
        initializeTeamsBasedOnSettings();
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
                this.status = LobbyStatus.AVAILABLE;
            }
        }
    }

    public PlayerInfo getQuizMaster() {
        return quizMaster;
    }

    public synchronized void setQuizMaster(PlayerInfo newQuizMaster) {
        if (this.quizMaster != null && newQuizMaster != null && this.quizMaster.sessionId().equals(newQuizMaster.sessionId())) {
            return;
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

    public List<PlayerInfo> getPlayersInTeams() { return new ArrayList<>(playersInTeams); }
    public List<PlayerInfo> getWaitingPlayers() { return new ArrayList<>(waitingPlayers); }
    public int getCurrentPlayerInTeamsCount() { return playersInTeams.size(); }

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
    public Map<String, List<PlayerInfo>> getTeams() { return new ConcurrentHashMap<>(teams); }

    public synchronized boolean addWaitingPlayer(PlayerInfo player) {
        if (player == null || player.sessionId() == null) return false;
        if (waitingPlayers.stream().anyMatch(p -> p.sessionId().equals(player.sessionId())) ||
                playersInTeams.stream().anyMatch(p -> p.sessionId().equals(player.sessionId())) ||
                (quizMaster != null && quizMaster.sessionId().equals(player.sessionId()))) {
            return false;
        }
        if (getTotalParticipantCount() >= maxParticipants) {
            return false;
        }
        waitingPlayers.add(player);
        return true;
    }

    public synchronized boolean assignWaitingPlayerToTeam(String playerSessionId, String targetTeamName) {
        PlayerInfo playerToAssign = waitingPlayers.stream()
                .filter(p -> p.sessionId().equals(playerSessionId))
                .findFirst()
                .orElse(null);
        if (playerToAssign == null) return false;
        if (!teams.containsKey(targetTeamName)) return false;
        List<PlayerInfo> team = teams.get(targetTeamName);
        if (team.size() >= gameSettings.maxPlayersPerTeam()) return false;

        waitingPlayers.remove(playerToAssign);
        PlayerInfo assignedPlayer = new PlayerInfo(playerToAssign.sessionId(), playerToAssign.nickname(), targetTeamName);
        team.add(assignedPlayer);
        playersInTeams.add(assignedPlayer);
        return true;
    }

    public synchronized boolean removePlayer(String sessionId) {
        boolean removedFromWaiting = waitingPlayers.removeIf(p -> p.sessionId().equals(sessionId));
        boolean removedFromPlayersInTeams = playersInTeams.removeIf(p -> p.sessionId().equals(sessionId));
        if (removedFromPlayersInTeams) {
            removePlayerFromAnyTeam(sessionId);
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
        boolean participantActuallyLeft = removedFromWaiting || removedFromPlayersInTeams || wasQuizMaster || wasHost;
        if (this.hostSessionId == null && this.status == LobbyStatus.BUSY && !isGameEffectivelyInProgressLogic()) {
            if (playersInTeams.isEmpty() && waitingPlayers.isEmpty() && quizMaster == null) {
                resetToAvailable();
            }
        }
        return participantActuallyLeft;
    }

    public synchronized boolean unassignAndMoveToWaiting(String sessionId) {
        PlayerInfo playerInfoToMoveToWaiting = null;
        boolean modified = false;

        for (Map.Entry<String, List<PlayerInfo>> entry : teams.entrySet()) {
            Optional<PlayerInfo> playerOpt = entry.getValue().stream()
                    .filter(p -> p.sessionId().equals(sessionId))
                    .findFirst();
            if (playerOpt.isPresent()) {
                playerInfoToMoveToWaiting = playerOpt.get();
                entry.getValue().remove(playerInfoToMoveToWaiting);
                playersInTeams.removeIf(p -> p.sessionId().equals(sessionId));
                modified = true;
                break;
            }
        }

        if (this.quizMaster != null && this.quizMaster.sessionId().equals(sessionId)) {
            if (playerInfoToMoveToWaiting == null) playerInfoToMoveToWaiting = this.quizMaster;
            this.quizMaster = null;
            modified = true;
        }

        if (playerInfoToMoveToWaiting != null) {
            PlayerInfo playerForWaiting = new PlayerInfo(playerInfoToMoveToWaiting.sessionId(), playerInfoToMoveToWaiting.nickname(), null);
            if (waitingPlayers.stream().noneMatch(p -> p.sessionId().equals(sessionId))) {
                if (addWaitingPlayer(playerForWaiting)) {
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

        Optional<PlayerInfo> playerOpt = playersInTeams.stream().filter(p -> p.sessionId().equals(sessionId)).findFirst();
        if (playerOpt.isPresent()) return playerOpt.get();

        playerOpt = waitingPlayers.stream().filter(p -> p.sessionId().equals(sessionId)).findFirst();
        return playerOpt.orElse(null);
    }

    public synchronized void resetToAvailable() {
        this.status = LobbyStatus.AVAILABLE;
        this.gameSettings = new GameSettings();
        this.password = null;
        this.hostSessionId = null;
        this.quizMaster = null;
        this.playersInTeams.clear();
        this.waitingPlayers.clear();
        initializeTeamsBasedOnSettings();
    }

    private boolean isGameEffectivelyInProgressLogic(){
        return this.status == LobbyStatus.BUSY && this.hostSessionId != null && (this.playersInTeams.size() > 0 || this.quizMaster != null);
    }
}