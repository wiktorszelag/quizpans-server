package org.quizpans.quizpans_server.online.service;

import org.quizpans.quizpans_server.online.model.Lobby;
import org.quizpans.quizpans_server.online.model.LobbyStatus;
import org.quizpans.quizpans_server.online.model.GameSettings;
import org.quizpans.quizpans_server.online.model.PlayerInfo;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class LobbyService {

    private final Map<String, Lobby> lobbies = new ConcurrentHashMap<>();
    private static final List<String> LOBBY_IDS = List.of("Red", "Blue", "Green", "Yellow", "Black");

    @PostConstruct
    private void initializeLobbies() {
        for (String id : LOBBY_IDS) {
            lobbies.put(id, new Lobby(id, id));
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
            else {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    public synchronized Optional<Lobby> finalizeLobbyConfiguration(String lobbyId, String hostSessionId, GameSettings settings, String password) {
        Optional<Lobby> lobbyOpt = getLobby(lobbyId);
        if (lobbyOpt.isPresent()) {
            Lobby lobby = lobbyOpt.get();
            if (lobby.getStatus() == LobbyStatus.BUSY && lobby.getHostSessionId() != null && lobby.getHostSessionId().equals(hostSessionId)) {
                lobby.setGameSettings(settings);
                lobby.setPassword(password);
                return Optional.of(lobby);
            } else {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    public synchronized Optional<Lobby> addWaitingPlayer(String lobbyId, PlayerInfo player) {
        Optional<Lobby> lobbyOpt = getLobby(lobbyId);
        if (lobbyOpt.isPresent()) {
            Lobby lobby = lobbyOpt.get();
            if (lobby.getStatus() == LobbyStatus.BUSY && lobby.getHostSessionId() != null) {
                if (lobby.addWaitingPlayer(player)) {
                    return Optional.of(lobby);
                } else {
                    return Optional.empty();
                }
            } else {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    public synchronized Optional<Lobby> assignParticipantRole(String lobbyId, String hostSessionId, String participantSessionId, String role, String targetTeamName) {
        Optional<Lobby> lobbyOpt = getLobby(lobbyId);
        if (lobbyOpt.isEmpty()) {
            return Optional.empty();
        }
        Lobby lobby = lobbyOpt.get();

        if (lobby.getHostSessionId() == null || !lobby.getHostSessionId().equals(hostSessionId)) {
            return Optional.empty();
        }

        PlayerInfo participantToAssign = lobby.findParticipantBySessionId(participantSessionId);
        if (participantToAssign == null) {
            participantToAssign = lobby.getWaitingPlayers().stream()
                    .filter(p -> p.sessionId().equals(participantSessionId))
                    .findFirst().orElse(null);
            if (participantToAssign == null) {
                return Optional.empty();
            }
        }

        boolean success = false;
        if ("QUIZ_MASTER".equalsIgnoreCase(role)) {
            lobby.setQuizMaster(participantToAssign);
            success = true;
        } else if ("PLAYER".equalsIgnoreCase(role)) {
            if (targetTeamName == null || targetTeamName.trim().isEmpty()) {
                return Optional.empty();
            }
            if (!lobby.getTeams().containsKey(targetTeamName)) {
                return Optional.empty();
            }
            lobby.unassignAndMoveToWaiting(participantSessionId);
            success = lobby.assignWaitingPlayerToTeam(participantSessionId, targetTeamName);
        } else {
            return Optional.empty();
        }

        if (success) {
            return Optional.of(lobby);
        } else {
            return Optional.empty();
        }
    }

    public synchronized Optional<Lobby> unassignParticipant(String lobbyId, String hostSessionId, String participantToUnassignSessionId) {
        Optional<Lobby> lobbyOpt = getLobby(lobbyId);
        if (lobbyOpt.isEmpty()) {
            return Optional.empty();
        }
        Lobby lobby = lobbyOpt.get();

        if (lobby.getHostSessionId() == null || !lobby.getHostSessionId().equals(hostSessionId)) {
            return Optional.empty();
        }

        if (lobby.unassignAndMoveToWaiting(participantToUnassignSessionId)) {
            return Optional.of(lobby);
        } else {
            return Optional.empty();
        }
    }

    public synchronized Optional<Lobby> resetLobbyDueToHostDisconnect(String lobbyId) {
        Optional<Lobby> lobbyOpt = getLobby(lobbyId);
        if (lobbyOpt.isPresent()) {
            Lobby lobby = lobbyOpt.get();
            lobby.setHostSessionId(null);
            lobby.resetToAvailable();
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
                if (wasHost && lobby.getHostSessionId() == null) {
                    if (lobby.getPlayersInTeams().isEmpty() &&
                            lobby.getWaitingPlayers().isEmpty() &&
                            lobby.getQuizMaster() == null) {
                        lobby.resetToAvailable();
                    }
                }
                return Optional.of(lobby);
            }
        }
        return Optional.empty();
    }

    public synchronized Optional<Lobby> hostRemovesParticipant(String lobbyId, String requestingHostSessionId, String participantToRemoveSessionId) {
        Optional<Lobby> lobbyOpt = getLobby(lobbyId);
        if (lobbyOpt.isEmpty()) {
            return Optional.empty();
        }
        Lobby lobby = lobbyOpt.get();

        if (lobby.getHostSessionId() == null || !lobby.getHostSessionId().equals(requestingHostSessionId)) {
            return Optional.empty();
        }

        if (participantToRemoveSessionId.equals(requestingHostSessionId)) {
            return Optional.empty();
        }

        if (lobby.removePlayer(participantToRemoveSessionId)) {
            return Optional.of(lobby);
        } else {
            return Optional.empty();
        }
    }


    public synchronized Optional<Lobby> setLobbyGameStatus(String lobbyId, String hostSessionId, boolean gameInProgress) {
        Optional<Lobby> lobbyOpt = getLobby(lobbyId);
        if (lobbyOpt.isPresent()) {
            Lobby lobby = lobbyOpt.get();
            if (lobby.getHostSessionId() != null && lobby.getHostSessionId().equals(hostSessionId)) {
                if (gameInProgress) {
                    if(lobby.getPlayersInTeams().size() < 2 || lobby.getQuizMaster() == null){
                        return Optional.empty();
                    }
                    lobby.setStatus(LobbyStatus.BUSY);
                } else {
                    lobby.resetToAvailable();
                }
                return Optional.of(lobby);
            } else {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }
}