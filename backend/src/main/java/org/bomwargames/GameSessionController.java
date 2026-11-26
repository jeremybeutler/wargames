package org.bomwargames;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import org.bomwargames.model.Campaign;
import org.bomwargames.model.GameSession;
import org.bomwargames.model.Place;
import org.bomwargames.model.TerrainRegion;
import org.bomwargames.repository.CampaignRepository;
import org.bomwargames.repository.GameSessionRepository;
import org.bomwargames.repository.PlaceRepository;
import org.bomwargames.repository.TerrainRegionRepository;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/games")
public class GameSessionController {
    private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int CODE_LENGTH = 6;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final List<Party> ROSTER = List.of(
        new Party("frodo", "Frodo", "Free Peoples", "Hobbits", 1, "Hobbiton", true, 14),
        new Party("theoden", "Théoden", "Free Peoples", "Riders of Rohan", 2, "Edoras", false, 12),
        new Party("aragorn", "Aragorn", "Free Peoples", "Fellowship", 3, "Rivendell", false, 12),
        new Party("khamul", "Khamûl", "Sauron's Forces", "Easterlings", 1, "Rhûn", false, 12),
        new Party("saruman", "Saruman", "Sauron's Forces", "Uruk-hai", 2, "Isengard", false, 12),
        new Party("sauron", "Sauron", "Sauron's Forces", "Orcs", 3, "Barad-dûr", false, 12)
    );
    private static final List<Objective> OBJECTIVES = List.of(
        new Objective("shire", "The Shire", "Hobbiton", "Free Peoples"),
        new Objective("rohan", "Rohan", "Edoras", "Free Peoples"),
        new Objective("gondor", "Gondor", "Minas Tirith", "Free Peoples"),
        new Objective("rhun", "Rhûn", "Rhûn", "Sauron's Forces"),
        new Objective("mordor", "Mordor", "Barad-dûr", "Sauron's Forces"),
        new Objective("isengard", "Isengard", "Isengard", "Sauron's Forces")
    );
    private final GameSessionRepository games;
    private final CampaignRepository campaigns;
    private final PlaceRepository places;
    private final TerrainRegionRepository terrainRegions;
    private final ObjectMapper objectMapper;

    public GameSessionController(GameSessionRepository games, CampaignRepository campaigns,
                                 PlaceRepository places, TerrainRegionRepository terrainRegions,
                                 ObjectMapper objectMapper) {
        this.games = games;
        this.campaigns = campaigns;
        this.places = places;
        this.terrainRegions = terrainRegions;
        this.objectMapper = objectMapper;
    }

    @PostMapping
    @Transactional
    public GameView createGame(@RequestBody CreateGameRequest request, HttpServletRequest http,
                               HttpServletResponse response) {
        String playerName = cleanName(request.playerName());
        double mapWidthMiles = request.mapWidthMiles() == null ? 1000 : request.mapWidthMiles();
        if (mapWidthMiles < 300 || mapWidthMiles > 3000) {
            throw badRequest("Map width must be between 300 and 3,000 miles.");
        }

        Campaign campaign = campaigns.findBySlug("middle-earth")
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                "The Middle-earth map is not available."));
        List<Place> mapPlaces = places.findByCampaign_Id(campaign.getId()).stream()
            .filter(place -> "middle-earth".equals(place.getMapLayer()))
            .toList();

        String code = generateCode();
        Room room = new Room();
        room.code = code;
        room.status = "lobby";
        String hostSecret = SeatSessions.newSecret();
        room.hostTokenHash = SeatSessions.hash(hostSecret);
        room.hostName = playerName;
        room.mapWidthMiles = mapWidthMiles;
        room.ringHolder = "frodo";
        room.createdAt = Instant.now().toString();
        for (Party template : ROSTER) {
            Place place = findPlace(mapPlaces, template.startingPlace);
            Party party = template.copyAt(place.getX(), place.getY());
            room.parties.add(party);
        }
        for (Objective template : OBJECTIVES) {
            Place place = findPlace(mapPlaces, template.placeName);
            room.objectives.add(template.copyAt(place.getX(), place.getY()));
        }
        Place mountDoom = findPlace(mapPlaces, "Mount Doom");
        room.mountDoomX = mountDoom.getX();
        room.mountDoomY = mountDoom.getY();
        room.events.add("The lobby is open. Six parties await their commanders.");
        save(room);
        SeatSessions.issue(http, response, room.code, SeatSessions.HOST_SEAT, hostSecret);
        return view(room, SeatSessions.HOST_SEAT);
    }

    @GetMapping("/{code}")
    @Transactional(readOnly = true)
    public GameView getGame(@PathVariable String code, HttpServletRequest http) {
        Room room = loadRoom(code);
        String seat = authenticate(room, http);
        if (seat == null && !"lobby".equals(room.status)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "A seat on this device is required.");
        }
        return view(room, seat);
    }

    /**
     * Converts a seat code saved by an older client (in localStorage) into this device's
     * HttpOnly cookie. The client deletes its stored copy afterwards.
     */
    @PostMapping("/{code}/session")
    @Transactional
    public GameView claimLegacySession(@PathVariable String code,
                                       @RequestHeader(name = "X-Game-Token", required = false) String legacyToken,
                                       HttpServletRequest http, HttpServletResponse response) {
        Room room = loadRoom(code);
        String seat = null;
        if (SeatSessions.matches(legacyToken, room.hostTokenHash)) seat = SeatSessions.HOST_SEAT;
        for (Party party : room.parties) {
            if (SeatSessions.matches(legacyToken, party.playerTokenHash)) seat = party.id;
        }
        if (seat == null) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "That saved seat is no longer valid.");
        save(room);
        SeatSessions.issue(http, response, room.code, seat, legacyToken);
        return view(room, seat);
    }

    /** Forgets this device's seat cookie. The seat itself stays assigned in the game. */
    @DeleteMapping("/{code}/session")
    public void leaveSession(@PathVariable String code, HttpServletRequest http, HttpServletResponse response) {
        String seat = SeatSessions.requestedSeat(http);
        if (seat == null || seat.isEmpty()) throw badRequest("Name the seat to forget.");
        SeatSessions.clear(http, response, code.toUpperCase(Locale.ROOT), seat);
    }

    @PostMapping("/{code}/join")
    @Transactional
    public GameView joinGame(@PathVariable String code, @RequestBody JoinGameRequest request,
                             HttpServletRequest http, HttpServletResponse response) {
        Room room = loadRoom(code);
        if (!"lobby".equals(room.status)) throw conflict("This campaign has already started.");
        Party party = party(room, request.characterId());
        if (party.playerTokenHash != null) throw conflict("That character's seat is already taken.");
        party.playerName = cleanName(request.playerName());
        String secret = SeatSessions.newSecret();
        party.playerTokenHash = SeatSessions.hash(secret);
        room.events.add(party.playerName + " joined as " + party.name + ".");
        save(room);
        SeatSessions.issue(http, response, room.code, party.id, secret);
        return view(room, party.id);
    }

    @PostMapping("/{code}/start")
    @Transactional
    public GameView startGame(@PathVariable String code, HttpServletRequest http) {
        Room room = loadRoom(code);
        String seat = authenticate(room, http);
        requireHost(seat);
        if (!"lobby".equals(room.status)) throw conflict("The campaign has already started.");
        if (room.parties.stream().anyMatch(party -> party.playerTokenHash == null)) {
            throw conflict("All six character seats must be filled before starting.");
        }
        room.status = "campaign";
        room.round = 1;
        room.freePeoplesFirst = true;
        room.turnOrderIds = room.parties.stream().map(party -> party.id).collect(
            java.util.stream.Collectors.toCollection(ArrayList::new));
        Collections.shuffle(room.turnOrderIds, RANDOM);
        room.turnIndex = 0;
        room.events.add("Turn order: " + String.join(", ", room.turnOrderIds.stream()
            .map(id -> party(room, id).name).toList()) + ".");
        room.events.add("Campaign begins — all six parties and armies are at their starting locations.");
        save(room);
        return view(room, seat);
    }

    @PostMapping("/{code}/movement-preview")
    @Transactional(readOnly = true)
    public MovementPreviewView movementPreview(@PathVariable String code,
                                               HttpServletRequest http,
                                               @RequestBody MoveRequest request) {
        Room room = requireActiveGame(code);
        String seat = authenticate(room, http);
        Party mover = activeParty(room, seat, request.characterId());
        validateDestination(request.x(), request.y());
        MovementPathCalculator.Result result = calculateMove(room, mover, request.x(), request.y());
        return new MovementPreviewView(result.miles(), result.movementPoints(),
            mover.movementRemaining - result.movementPoints(), result.riverCrossings(),
            result.destinationTerrain(), result.segments().stream()
                .map(segment -> new MovementSegmentView(segment.terrain(), segment.miles(),
                    segment.movementPoints())).toList());
    }

    @PostMapping("/{code}/move")
    @Transactional
    public GameView move(@PathVariable String code, HttpServletRequest http,
                         @RequestBody MoveRequest request) {
        Room room = requireActiveGame(code);
        String seat = authenticate(room, http);
        Party mover = activeParty(room, seat, request.characterId());
        if (mover.moved) throw conflict("This party has already moved this turn.");
        if (mover.attacked) throw conflict("A party cannot move after attacking.");
        validateDestination(request.x(), request.y());
        MovementPathCalculator.Result movement = calculateMove(room, mover, request.x(), request.y());
        if (movement.movementPoints() > mover.movementRemaining + 0.0001) {
            throw conflict(String.format(Locale.ROOT,
                "That move costs %.1f movement points; only %.1f remain.",
                movement.movementPoints(), mover.movementRemaining));
        }
        mover.x = request.x();
        mover.y = request.y();
        mover.moved = true;
        mover.movementRemaining = Math.max(0, mover.movementRemaining - movement.movementPoints());
        mover.terrain = movement.destinationTerrain();
        mover.hidden = concealedIn(mover.terrain, RANDOM::nextBoolean);
        Party encounter = room.parties.stream()
            .filter(party -> party.alive && !party.id.equals(mover.id) && !party.team.equals(mover.team)
                && sameLocation(mover.x, mover.y, party.x, party.y))
            .findFirst().orElse(null);
        if (encounter != null) {
            mover.hidden = false;
            mover.visibleUntilRound = room.round;
            encounter.hidden = false;
            encounter.visibleUntilRound = room.round;
            room.events.add(mover.name + " and " + encounter.name + " made contact.");
        } else if (!mover.hidden) {
            room.events.add(String.format(Locale.ROOT,
                "%s moved %.1f map miles, spending %.1f movement points%s.",
                mover.name, movement.miles(), movement.movementPoints(),
                movement.riverCrossings() == 0 ? "" : " including " + movement.riverCrossings()
                    + (movement.riverCrossings() == 1 ? " river crossing" : " river crossings")));
        }
        advanceIfNoActions(room);
        save(room);
        return view(room, seat);
    }

    @PostMapping("/{code}/attack")
    @Transactional
    public GameView attack(@PathVariable String code, HttpServletRequest http,
                           @RequestBody AttackRequest request) {
        Room room = requireActiveGame(code);
        String seat = authenticate(room, http);
        Party attacker = activeParty(room, seat, request.characterId());
        if (attacker.attacked) throw conflict("This party has already attacked this turn.");
        Party defender = room.parties.stream()
            .filter(party -> party.alive && !party.team.equals(attacker.team)
                && sameLocation(attacker.x, attacker.y, party.x, party.y))
            .findFirst().orElse(null);
        if (defender != null) {
            if (!defender.id.equals(request.targetId())) {
                throw conflict("Defeat the defending party before attacking a stronghold.");
            }
            int roll = rollAttack() + attacker.attackBoost;
            int armor = partyArmor(defender);
            boolean hit = roll > armor;
            attacker.attacked = true;
            attacker.hidden = false;
            attacker.visibleUntilRound = room.round;
            defender.hidden = false;
            defender.visibleUntilRound = room.round;
            if (hit) applyHit(room, defender);
            room.events.add(hit
                ? String.format(Locale.ROOT, "%s hit %s (%d vs. %d).", attacker.name, defender.name, roll, armor)
                : String.format(Locale.ROOT, "%s attacked %s but missed (%d vs. %d).",
                    attacker.name, defender.name, roll, armor));
            checkVictory(room);
            if (room.winner == null) advanceIfNoActions(room);
            save(room);
            return view(room, seat);
        }

        Objective objective = room.objectives.stream()
            .filter(city -> sameLocation(attacker.x, attacker.y, city.x, city.y))
            .findFirst().orElse(null);
        if (objective == null || !objective.id.equals(request.targetId())) {
            throw conflict("Attack requires direct contact with an enemy party or stronghold.");
        }
        if (objective.owner.equals(attacker.team)) throw conflict("Your team already controls this stronghold.");
        int roll = rollAttack() + attacker.attackBoost;
        boolean hit = roll > objective.armorClass;
        attacker.attacked = true;
        attacker.hidden = false;
        attacker.visibleUntilRound = room.round;
        if (hit) objective.owner = attacker.team;
        room.events.add(hit
            ? String.format(Locale.ROOT, "%s captured %s (%d vs. %d).",
                attacker.name, objective.name, roll, objective.armorClass)
            : String.format(Locale.ROOT, "%s attacked %s but missed (%d vs. %d).",
                attacker.name, objective.name, roll, objective.armorClass));
        checkVictory(room);
        if (room.winner == null) advanceIfNoActions(room);
        save(room);
        return view(room, seat);
    }

    @PostMapping("/{code}/end-turn")
    @Transactional
    public GameView endTurn(@PathVariable String code, HttpServletRequest http,
                            @RequestBody CharacterRequest request) {
        Room room = requireActiveGame(code);
        String seat = authenticate(room, http);
        activeParty(room, seat, request.characterId());
        advanceTurn(room);
        save(room);
        return view(room, seat);
    }

    @PostMapping("/{code}/destroy-ring")
    @Transactional
    public GameView destroyRing(@PathVariable String code, HttpServletRequest http,
                                @RequestBody CharacterRequest request) {
        Room room = requireActiveGame(code);
        String seat = authenticate(room, http);
        Party frodo = activeParty(room, seat, request.characterId());
        if (!"frodo".equals(frodo.id) || !"frodo".equals(room.ringHolder)) {
            throw conflict("Only the Ring-bearer can destroy the Ring.");
        }
        if (!sameLocation(frodo.x, frodo.y, room.mountDoomX, room.mountDoomY)) {
            throw conflict("The Ring can only be destroyed at Mount Doom.");
        }
        resolveRingDestruction(room, frodo, GameSessionController::rollAttack);
        save(room);
        return view(room, seat);
    }

    /**
     * Every living opposing party at Mount Doom makes one normal attack on Frodo, in turn
     * order, before the Ring can be destroyed. The first hit captures the Ring.
     */
    static void resolveRingDestruction(Room room, Party frodo, IntSupplier dice) {
        List<Party> interceptors = turnOrder(room).stream()
            .filter(party -> party.alive && !party.team.equals(frodo.team)
                && sameLocation(frodo.x, frodo.y, party.x, party.y))
            .toList();
        if (!interceptors.isEmpty()) {
            reveal(room, frodo);
            interceptors.forEach(party -> reveal(room, party));
            room.events.add(String.join(", ", interceptors.stream().map(party -> party.name).toList())
                + (interceptors.size() == 1 ? " intercepts" : " intercept")
                + " Frodo at Mount Doom.");
        }
        for (Party attacker : interceptors) {
            int roll = dice.getAsInt() + attacker.attackBoost;
            int armor = partyArmor(frodo);
            if (roll > armor) {
                applyHit(room, frodo);
                room.events.add(String.format(Locale.ROOT,
                    "%s hit Frodo at Mount Doom and captured the Ring (%d vs. %d).",
                    attacker.name, roll, armor));
                checkVictory(room);
                return;
            }
            room.events.add(String.format(Locale.ROOT,
                "%s attacked Frodo at Mount Doom but missed (%d vs. %d).", attacker.name, roll, armor));
        }
        room.ringHolder = "destroyed";
        room.winner = "Free Peoples";
        room.events.add("The Ring is destroyed in Mount Doom. The Free Peoples win!");
    }

    private static void reveal(Room room, Party party) {
        party.hidden = false;
        party.visibleUntilRound = room.round;
    }

    private static int partyArmor(Party defender) {
        return defender.armorClass
            + ("volcanic".equals(defender.terrain) || "mount-doom".equals(defender.terrain) ? -1 : 0);
    }

    /** One successful hit: remove an army unit, or defeat the character once its army is gone. */
    private static void applyHit(Room room, Party defender) {
        int defenderIndex = turnOrder(room).indexOf(defender);
        if (defender.units > 0) defender.units -= 1;
        else defender.alive = false;
        if (!defender.alive && defenderIndex >= 0 && defenderIndex < room.turnIndex) {
            room.turnIndex -= 1;
        }
        if ("frodo".equals(defender.id) && "frodo".equals(room.ringHolder)) {
            room.ringHolder = "sauron";
        }
    }

    private MovementPathCalculator.Result calculateMove(Room room, Party mover, double x, double y) {
        List<TerrainRegion> regions = terrainRegions.findByMapKeyAndMapLayerOrderByIdAsc(
            "middle-earth", "middle-earth");
        return MovementPathCalculator.calculate(mover.x, mover.y, x, y, room.mapWidthMiles,
            room.mountDoomX, room.mountDoomY, regions);
    }

    private static void validateDestination(double x, double y) {
        if (x < 0 || x > 100 || y < 0 || y > 100) {
            throw badRequest("The destination must be on the map.");
        }
    }

    private static boolean isMountainTerrain(String terrain) {
        // "mountains" is the pre-fix key; rooms saved before then may still hold it.
        return "mountain".equals(terrain) || "mountains".equals(terrain);
    }

    /** Mountains and volcanic ground always conceal; forest and Mount Doom use a coin flip. */
    static boolean concealedIn(String terrain, BooleanSupplier coin) {
        if (isMountainTerrain(terrain) || "volcanic".equals(terrain)) return true;
        return isForestTerrain(terrain) && coin.getAsBoolean();
    }

    private static boolean isForestTerrain(String terrain) {
        return "forest".equals(terrain) || "mount-doom".equals(terrain);
    }

    static void advanceIfNoActions(Room room) {
        if (room.winner != null) return;
        Party active = currentTurnParty(room);
        boolean canMove = !active.moved && !active.attacked && active.movementRemaining > 0.0001;
        boolean canAttack = !active.attacked && hasAttackTarget(room, active);
        boolean canDestroyRing = "frodo".equals(active.id) && "frodo".equals(room.ringHolder)
            && sameLocation(active.x, active.y, room.mountDoomX, room.mountDoomY);
        if (!canMove && !canAttack && !canDestroyRing) advanceTurn(room);
    }

    private static boolean hasAttackTarget(Room room, Party party) {
        boolean enemyPartyAtLocation = room.parties.stream().anyMatch(target ->
            target.alive && !target.team.equals(party.team)
                && sameLocation(party.x, party.y, target.x, target.y));
        if (enemyPartyAtLocation) return true;
        return room.objectives.stream().anyMatch(objective ->
            !objective.owner.equals(party.team)
                && sameLocation(party.x, party.y, objective.x, objective.y));
    }

    static void advanceTurn(Room room) {
        List<Party> order = turnOrder(room);
        if (room.turnIndex + 1 < order.size()) {
            room.turnIndex += 1;
            return;
        }
        room.round += 1;
        room.turnIndex = 0;
        if (room.turnOrderIds == null || room.turnOrderIds.isEmpty()) {
            room.freePeoplesFirst = !room.freePeoplesFirst;
        }
        for (Party party : room.parties) {
            party.moved = false;
            party.attacked = false;
            party.movementRemaining = party.movementMax;
            if (party.visibleUntilRound < room.round) {
                party.hidden = concealedIn(party.terrain, RANDOM::nextBoolean);
            }
        }
        room.events.add("Round " + room.round + " begins. " + currentTurnParty(room).name + " acts first.");
    }

    private Room requireActiveGame(String code) {
        Room room = loadRoom(code);
        if (!"campaign".equals(room.status)) throw conflict("The campaign has not started.");
        if (room.winner != null) throw conflict("The campaign is over. " + room.winner + " won.");
        return room;
    }

    private Party activeParty(Room room, String seat, String characterId) {
        Party party = room.parties.stream()
            .filter(item -> item.id.equals(seat))
            .findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN,
                "This device does not hold a character seat in this game."));
        if (!party.alive) throw conflict("This character has been defeated.");
        Party active = currentTurnParty(room);
        if (!active.id.equals(party.id)) throw conflict("It is not your party's turn.");
        if (characterId == null || !party.id.equals(characterId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You can only command your assigned character.");
        }
        return party;
    }

    private Room loadRoom(String code) {
        String normalized = code.toUpperCase(Locale.ROOT);
        GameSession session = games.findById(normalized)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Game code not found."));
        try {
            Room room = objectMapper.readValue(session.getState(), Room.class);
            hashLegacySecrets(room);
            return room;
        } catch (JsonProcessingException error) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                "Could not read the saved game session.", error);
        }
    }

    /** Rooms saved before secrets were hashed hold them in plain text; hash them on load. */
    static void hashLegacySecrets(Room room) {
        if (room.hostToken != null) {
            if (room.hostTokenHash == null) room.hostTokenHash = SeatSessions.hash(room.hostToken);
            room.hostToken = null;
        }
        for (Party party : room.parties) {
            if (party.playerToken != null) {
                if (party.playerTokenHash == null) party.playerTokenHash = SeatSessions.hash(party.playerToken);
                party.playerToken = null;
            }
        }
    }

    private void save(Room room) {
        try {
            games.save(new GameSession(room.code, objectMapper.writeValueAsString(room)));
        } catch (JsonProcessingException error) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                "Could not save the game session.", error);
        }
    }

    static GameView view(Room room, String seat) {
        boolean host = SeatSessions.HOST_SEAT.equals(seat);
        Party ownParty = room.parties.stream()
            .filter(party -> party.id.equals(seat))
            .findFirst().orElse(null);
        if (!host && ownParty == null && !"lobby".equals(room.status)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "A seat on this device is required to view this campaign.");
        }
        String ownTeam = host ? null : ownParty == null ? null : ownParty.team;
        List<PartyView> partyViews = "lobby".equals(room.status) && !host ? List.of() : room.parties.stream()
            .map(party -> {
                // Terrain and this turn's action state would hint at a hidden party's position
                // or activity, so they are withheld along with its coordinates.
                boolean hidden = party.hidden && (host || ownTeam == null || !ownTeam.equals(party.team));
                return new PartyView(
                    party.id, party.name, party.team, party.army, hidden ? null : party.units,
                    party.initialUnits, hidden ? null : party.x, hidden ? null : party.y,
                    hidden ? null : party.movementRemaining, party.movementMax, party.attackBoost,
                    party.armorClass, party.ring && "frodo".equals(room.ringHolder), party.alive,
                    party.hidden, party.playerName, hidden ? null : party.moved,
                    hidden ? null : party.attacked, hidden ? null : party.terrain
                );
            }).toList();
        List<SeatView> seatViews = room.parties.stream()
            .map(party -> new SeatView(party.id, party.name, party.team, party.army,
                party.initialUnits, party.playerName, party.playerTokenHash != null))
            .toList();
        Party active = "campaign".equals(room.status) ? currentTurnParty(room) : null;
        String activeCharacter = active == null ? null : active.id;
        String team = active == null ? null : active.team;
        List<ObjectiveView> objectiveViews = room.objectives.stream()
            .map(city -> new ObjectiveView(city.id, city.name, city.x, city.y, city.owner, city.armorClass))
            .toList();
        return new GameView(room.code, room.status, room.mapWidthMiles, room.round, team,
            activeCharacter, room.ringHolder, room.winner, partyViews, objectiveViews,
            List.copyOf(room.events), seatViews, host, ownParty == null ? null : ownParty.id,
            room.hostName, room.createdAt, room.mountDoomX, room.mountDoomY);
    }

    /**
     * The seat this request acts as: "host", a character id, or null when it names none.
     * A named seat must be backed by that seat's cookie on this device.
     */
    static String authenticate(Room room, HttpServletRequest http) {
        String seat = SeatSessions.requestedSeat(http);
        if (seat == null) return null;
        String expectedHash = SeatSessions.HOST_SEAT.equals(seat) ? room.hostTokenHash
            : room.parties.stream().filter(party -> party.id.equals(seat))
                .map(party -> party.playerTokenHash).findFirst().orElse(null);
        if (!SeatSessions.matches(SeatSessions.cookieSecret(http, seat), expectedHash)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "This device does not hold that seat. Rejoin from the device you joined with.");
        }
        return seat;
    }

    private static void requireHost(String seat) {
        if (!SeatSessions.HOST_SEAT.equals(seat)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the host can start the campaign.");
        }
    }

    static List<Party> turnOrder(Room room) {
        if (room.turnOrderIds != null && !room.turnOrderIds.isEmpty()) {
            List<Party> order = new ArrayList<>();
            for (String id : room.turnOrderIds) {
                Party party = room.parties.stream().filter(item -> item.id.equals(id)).findFirst().orElse(null);
                if (party != null && party.alive) order.add(party);
            }
            return order;
        }
        List<Party> order = new ArrayList<>();
        String first = room.freePeoplesFirst ? "Free Peoples" : "Sauron's Forces";
        String second = room.freePeoplesFirst ? "Sauron's Forces" : "Free Peoples";
        room.parties.stream().filter(party -> party.alive && first.equals(party.team)).forEach(order::add);
        room.parties.stream().filter(party -> party.alive && second.equals(party.team)).forEach(order::add);
        return order;
    }

    private static Party currentTurnParty(Room room) {
        return turnOrder(room).get(room.turnIndex);
    }

    private static String activeTeam(Room room) {
        return currentTurnParty(room).team;
    }

    private static void checkVictory(Room room) {
        if ("sauron".equals(room.ringHolder)) room.winner = "Sauron's Forces";
        else if (room.objectives.stream().allMatch(city -> "Sauron's Forces".equals(city.owner))) {
            room.winner = "Sauron's Forces";
        }
    }

    private static int rollAttack() {
        return RANDOM.nextInt(6) + RANDOM.nextInt(6) + RANDOM.nextInt(6) + 3;
    }

    private static boolean sameLocation(double x1, double y1, double x2, double y2) {
        return Math.hypot(x1 - x2, y1 - y2) < 0.5;
    }

    private static Party party(Room room, String characterId) {
        return room.parties.stream().filter(item -> item.id.equals(characterId)).findFirst()
            .orElseThrow(() -> badRequest("Choose a valid character seat."));
    }

    private static Place findPlace(List<Place> mapPlaces, String name) {
        return mapPlaces.stream().filter(place -> name.equals(place.getName())).findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                "The map is missing the seeded location " + name + "."));
    }

    private String generateCode() {
        for (int attempt = 0; attempt < 20; attempt++) {
            StringBuilder code = new StringBuilder(CODE_LENGTH);
            for (int index = 0; index < CODE_LENGTH; index++) {
                code.append(CODE_ALPHABET.charAt(RANDOM.nextInt(CODE_ALPHABET.length())));
            }
            if (!games.existsById(code.toString())) return code.toString();
        }
        throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
            "Could not generate an available game code.");
    }

    private static String cleanName(String name) {
        if (name == null || name.isBlank()) throw badRequest("Enter a player name.");
        String clean = name.trim();
        if (clean.length() > 30) throw badRequest("Player names must be 30 characters or fewer.");
        return clean;
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    private record CreateGameRequest(String playerName, Double mapWidthMiles) {}
    private record JoinGameRequest(String playerName, String characterId) {}
    private record CharacterRequest(String characterId) {}
    private record MoveRequest(String characterId, double x, double y) {}
    private record MovementPreviewView(double miles, double movementPoints, double remaining,
                                       int riverCrossings, String destinationTerrain,
                                       List<MovementSegmentView> segments) {}
    private record MovementSegmentView(String terrain, double miles, double movementPoints) {}
    private record AttackRequest(String characterId, String targetId) {}
    record SeatView(String id, String name, String team, String army, int initialUnits,
                           String playerName, boolean connected) {}
    record PartyView(String id, String name, String team, String army, Integer units,
                             int initialUnits, Double x, Double y, Double movementRemaining,
                             double movementMax, int attackBoost, int armorClass, boolean ring,
                             boolean alive, boolean hidden, String playerName,
                             Boolean moved, Boolean attacked, String terrain) {}
    record ObjectiveView(String id, String name, double x, double y, String owner,
                                 int armorClass) {}
    record GameView(String code, String status, double mapWidthMiles, int round,
                            String activeTeam, String activeCharacter, String ringHolder,
                            String winner, List<PartyView> parties, List<ObjectiveView> objectives,
                            List<String> events, List<SeatView> seats, boolean host,
                            String ownCharacterId, String hostName, String createdAt,
                            double mountDoomX, double mountDoomY) {}

    // ignoreUnknown keeps rooms saved with the retired ringInterceptionDefenderId field loadable.
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Room {
        public String code;
        public String status;
        /** SHA-256 of the host's seat secret. */
        public String hostTokenHash;
        /** Plain-text secret from older saves; hashed and cleared on load. */
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public String hostToken;
        public String hostName;
        public String createdAt;
        public double mapWidthMiles;
        public double mountDoomX;
        public double mountDoomY;
        public int round;
        public int turnIndex;
        public boolean freePeoplesFirst;
        public List<String> turnOrderIds;
        public String ringHolder;
        public String winner;
        public List<Party> parties = new ArrayList<>();
        public List<Objective> objectives = new ArrayList<>();
        public List<String> events = new ArrayList<>();
    }

    public static class Party {
        public String id;
        public String name;
        public String team;
        public String army;
        public int units;
        public int initialUnits;
        public String startingPlace;
        public double x;
        public double y;
        public boolean ring;
        public int movementMax;
        public double movementRemaining;
        public int attackBoost;
        public int armorClass = 10;
        public String playerName;
        /** SHA-256 of this seat's secret; null while the seat is open. */
        public String playerTokenHash;
        /** Plain-text secret from older saves; hashed and cleared on load. */
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public String playerToken;
        public boolean alive = true;
        public boolean hidden;
        public boolean moved;
        public boolean attacked;
        public String terrain = "plains";
        public int visibleUntilRound = -1;

        public Party() {}

        Party(String id, String name, String team, String army, int units,
              String startingPlace, boolean ring, int movementMax) {
            this.id = id;
            this.name = name;
            this.team = team;
            this.army = army;
            this.units = units;
            this.initialUnits = units;
            this.startingPlace = startingPlace;
            this.ring = ring;
            this.movementMax = movementMax;
            this.movementRemaining = movementMax;
        }

        Party copyAt(double x, double y) {
            Party copy = new Party(id, name, team, army, initialUnits, startingPlace, ring, movementMax);
            copy.x = x;
            copy.y = y;
            return copy;
        }
    }

    public static class Objective {
        public String id;
        public String name;
        public String placeName;
        public double x;
        public double y;
        public String owner;
        public int armorClass = 12;

        public Objective() {}

        Objective(String id, String name, String placeName, String owner) {
            this.id = id;
            this.name = name;
            this.placeName = placeName;
            this.owner = owner;
        }

        Objective copyAt(double x, double y) {
            Objective copy = new Objective(id, name, placeName, owner);
            copy.x = x;
            copy.y = y;
            return copy;
        }
    }

}
