package org.wargames;

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
import java.util.Map;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import org.wargames.model.Campaign;
import org.wargames.model.GameSession;
import org.wargames.model.Place;
import org.wargames.model.TerrainRegion;
import org.wargames.repository.CampaignRepository;
import org.wargames.repository.GameSessionRepository;
import org.wargames.repository.PlaceRepository;
import org.wargames.repository.TerrainRegionRepository;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
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
    /** The optional 8-player expansion: one more party, and its home stronghold, per side. */
    private static final List<Party> EXPANSION_ROSTER = List.of(
        new Party("elves", "Galadriel", "Free Peoples", "Elves", 2, "Lothlórien", false, 12),
        new Party("witchking", "Witch-king", "Sauron's Forces", "Ringwraiths", 2, "Minas Morgul", false, 12)
    );
    private static final List<Objective> OBJECTIVES = List.of(
        new Objective("shire", "The Shire", "Hobbiton", "Free Peoples", 1),
        new Objective("rohan", "Rohan", "Edoras", "Free Peoples", 2),
        new Objective("gondor", "Gondor", "Minas Tirith", "Free Peoples", 3),
        new Objective("rhun", "Rhûn", "Rhûn", "Sauron's Forces", 1),
        new Objective("mordor", "Mordor", "Barad-dûr", "Sauron's Forces", 3),
        new Objective("isengard", "Isengard", "Isengard", "Sauron's Forces", 2)
    );
    private static final List<Objective> EXPANSION_OBJECTIVES = List.of(
        new Objective("lorien", "Lothlórien", "Lothlórien", "Free Peoples", 2),
        new Objective("morgul", "Minas Morgul", "Minas Morgul", "Sauron's Forces", 2)
    );
    /** Leaders a player may choose to represent their party; the first is the default. */
    private static final Map<String, List<String>> REPRESENTATIVES = Map.of(
        "frodo", List.of("Frodo", "Sam", "Merry", "Pippin"),
        "theoden", List.of("Théoden", "Éowyn", "Éomer"),
        "aragorn", List.of("Aragorn", "Gandalf", "Gimli"),
        "elves", List.of("Galadriel", "Legolas", "Arwen")
    );
    private final GameSessionRepository games;
    private final CampaignRepository campaigns;
    private final PlaceRepository places;
    private final TerrainRegionRepository terrainRegions;
    private final ObjectMapper objectMapper;
    private final BotScheduler bots;
    private final TransactionTemplate transactions;

    public GameSessionController(GameSessionRepository games, CampaignRepository campaigns,
                                 PlaceRepository places, TerrainRegionRepository terrainRegions,
                                 ObjectMapper objectMapper, BotScheduler bots,
                                 PlatformTransactionManager transactionManager) {
        this.games = games;
        this.campaigns = campaigns;
        this.places = places;
        this.terrainRegions = terrainRegions;
        this.objectMapper = objectMapper;
        this.bots = bots;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @PostMapping
    @Transactional
    public GameView createGame(@RequestBody CreateGameRequest request, HttpServletRequest http,
                               HttpServletResponse response) {
        String playerName = cleanName(request.playerName());
        double mapWidthMiles = request.mapWidthMiles() == null ? 1000 : request.mapWidthMiles();
        int players = request.players() == null ? 6 : request.players();
        if (players != 6 && players != 8) throw badRequest("Choose 6 or 8 players.");
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
        for (Party template : roster(players)) {
            Place place = findPlace(mapPlaces, template.startingPlace);
            Party party = template.copyAt(place.getX(), place.getY());
            room.parties.add(party);
        }
        for (Objective template : objectives(players)) {
            Place place = findPlace(mapPlaces, template.placeName);
            room.objectives.add(template.copyAt(place.getX(), place.getY()));
        }
        Place mountDoom = findPlace(mapPlaces, "Mount Doom");
        room.mountDoomX = mountDoom.getX();
        room.mountDoomY = mountDoom.getY();
        room.events.add("The lobby is open. " + players + " parties await their commanders.");
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
        scheduleBots(room);
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
        if (seatTaken(party)) throw conflict("That character's seat is already taken.");
        String playerName = cleanName(request.playerName());
        chooseRepresentative(party, request.representative());
        party.playerName = playerName;
        String secret = SeatSessions.newSecret();
        party.playerTokenHash = SeatSessions.hash(secret);
        room.events.add(party.playerName + " joined as " + party.name + ".");
        save(room);
        SeatSessions.issue(http, response, room.code, party.id, secret);
        return view(room, party.id);
    }

    /**
     * Host only, in the lobby: a bot takes a seat ({@code characterId}), or every open seat when
     * none is named. {@code difficulty} is "easy" or "hard".
     */
    @PostMapping("/{code}/bots")
    @Transactional
    public GameView addBots(@PathVariable String code, HttpServletRequest http, @RequestBody BotRequest request) {
        Room room = loadRoom(code);
        String seat = authenticate(room, http);
        if (!SeatSessions.HOST_SEAT.equals(seat)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the host can add bots.");
        }
        if (!"lobby".equals(room.status)) throw conflict("Bots can only join before the campaign starts.");
        String level = botLevel(request.difficulty());
        List<Party> targets = request.characterId() == null || request.characterId().isBlank()
            ? room.parties.stream().filter(party -> !seatTaken(party)).toList()
            : List.of(party(room, request.characterId()));
        if (targets.isEmpty()) throw conflict("Every seat is already taken.");
        for (Party party : targets) {
            if (seatTaken(party)) throw conflict(party.name + "'s seat is already taken.");
            if (targets.size() == 1) chooseRepresentative(party, request.representative());
            party.bot = level;
            party.playerName = WarBot.HARD.equals(level) ? "Bot (hard)" : "Bot (easy)";
            room.events.add((WarBot.EASY.equals(level) ? "An easy" : "A hard") + " bot joined as " + party.name + ".");
        }
        save(room);
        return view(room, seat);
    }

    /** Host only, in the lobby: frees a bot's seat for a player. */
    @DeleteMapping("/{code}/bots/{characterId}")
    @Transactional
    public GameView removeBot(@PathVariable String code, @PathVariable String characterId, HttpServletRequest http) {
        Room room = loadRoom(code);
        String seat = authenticate(room, http);
        if (!SeatSessions.HOST_SEAT.equals(seat)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the host can remove bots.");
        }
        if (!"lobby".equals(room.status)) throw conflict("Bots can only leave before the campaign starts.");
        Party party = party(room, characterId);
        if (party.bot == null) throw conflict(party.name + " is not a bot.");
        party.bot = null;
        party.playerName = null;
        room.events.add(party.name + "'s bot left; the seat is open.");
        save(room);
        return view(room, seat);
    }

    static boolean seatTaken(Party party) {
        return party.playerTokenHash != null || party.bot != null;
    }

    private static String botLevel(String difficulty) {
        if (difficulty == null || WarBot.EASY.equalsIgnoreCase(difficulty)) return WarBot.EASY;
        if (WarBot.HARD.equalsIgnoreCase(difficulty)) return WarBot.HARD;
        throw badRequest("Bot difficulty must be easy or hard.");
    }

    @PostMapping("/{code}/start")
    @Transactional
    public GameView startGame(@PathVariable String code, HttpServletRequest http) {
        Room room = loadRoom(code);
        String seat = authenticate(room, http);
        requireHost(seat);
        if (!"lobby".equals(room.status)) throw conflict("The campaign has already started.");
        if (room.parties.stream().anyMatch(party -> !seatTaken(party))) {
            throw conflict("Every character seat must be filled before starting.");
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
        room.events.add("Campaign begins — all " + room.parties.size()
            + " parties and armies are at their starting locations.");
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
        double[] target = snapToMountDoom(room, request.x(), request.y());
        MovementPathCalculator.Result result = calculateMove(room, mover, target[0], target[1]);
        return new MovementPreviewView(target[0], target[1], result.miles(), result.movementPoints(),
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
        validateDestination(request.x(), request.y());
        applyMove(room, mover, request.x(), request.y());
        save(room);
        return view(room, seat);
    }

    /** Moves the active party (a player's or a bot's), checking the rules. */
    private void applyMove(Room room, Party mover, double x, double y) {
        if (mover.moved) throw conflict("This party has already moved this turn.");
        if (mover.attacked) throw conflict("A party cannot move after attacking.");
        double[] target = snapToMountDoom(room, x, y);
        MovementPathCalculator.Result movement = calculateMove(room, mover, target[0], target[1]);
        if (movement.movementPoints() > mover.movementRemaining + 0.0001) {
            throw conflict(String.format(Locale.ROOT,
                "That move costs %.1f movement points; only %.1f remain.",
                movement.movementPoints(), mover.movementRemaining));
        }
        mover.x = target[0];
        mover.y = target[1];
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
    }

    @PostMapping("/{code}/attack")
    @Transactional
    public GameView attack(@PathVariable String code, HttpServletRequest http,
                           @RequestBody AttackRequest request) {
        Room room = requireActiveGame(code);
        String seat = authenticate(room, http);
        Party attacker = activeParty(room, seat, request.characterId());
        applyAttack(room, attacker, request.targetId());
        save(room);
        return view(room, seat);
    }

    /** The active party (a player's or a bot's) attacks a party or stronghold it stands on. */
    static void applyAttack(Room room, Party attacker, String targetId) {
        if (attacker.attacked) throw conflict("This party has already attacked this turn.");
        Party defender = room.parties.stream()
            .filter(party -> party.alive && !party.team.equals(attacker.team)
                && sameLocation(attacker.x, attacker.y, party.x, party.y))
            .findFirst().orElse(null);
        if (defender != null) {
            if (!defender.id.equals(targetId)) {
                throw conflict("Defeat the defending party before attacking a stronghold.");
            }
            attacker.attacked = true;
            fight(room, attacker, defender, GameSessionController::rollDie);
            finishAction(room);
            return;
        }

        Objective objective = room.objectives.stream()
            .filter(city -> sameLocation(attacker.x, attacker.y, city.x, city.y))
            .findFirst().orElse(null);
        if (objective == null || !objective.id.equals(targetId)) {
            throw conflict("Attack requires direct contact with an enemy party or stronghold.");
        }
        if (objective.owner.equals(attacker.team)) throw conflict("Your team already controls this stronghold.");
        attacker.attacked = true;
        siege(room, attacker, objective, GameSessionController::rollDie);
        finishAction(room);
    }

    // ------------------------------------------------------------------ bots

    /**
     * Plays one bot turn (called by the BotScheduler, under this game's lock) in its own
     * transaction, which commits before the lock is released.
     */
    void playBotTurn(String code) {
        transactions.executeWithoutResult(status -> playBotTurnNow(code));
    }

    private void playBotTurnNow(String code) {
        Room room;
        try {
            room = loadRoom(code);
        } catch (ResponseStatusException gone) {
            return;
        }
        if (!"campaign".equals(room.status) || room.winner != null) return;
        Party bot = currentTurnParty(room);
        if (bot.bot == null || !bot.alive) return;
        List<TerrainRegion> regions = terrainRegions.findByMapKeyAndMapLayerOrderByIdAsc("middle-earth", "middle-earth");
        WarBot.playTurn(room, bot, new WarBot.Actions() {
            @Override
            public double cost(Room r, Party party, double x, double y) {
                double[] target = snapToMountDoom(r, x, y);
                return MovementPathCalculator.calculate(party.x, party.y, target[0], target[1], r.mapWidthMiles,
                    r.mountDoomX, r.mountDoomY, regions).movementPoints();
            }

            @Override
            public void move(Room r, Party party, double x, double y) {
                applyMove(r, party, Math.max(0, Math.min(100, x)), Math.max(0, Math.min(100, y)));
            }

            @Override
            public void attack(Room r, Party party, String targetId) {
                applyAttack(r, party, targetId);
            }

            @Override
            public void destroyRing(Room r, Party ringBearer) {
                resolveRingDestruction(r, ringBearer, GameSessionController::rollDie);
            }

            @Override
            public void endTurn(Room r) {
                advanceTurn(r);
            }
        }, RANDOM);
        save(room);
    }

    /** Queues the next bot turn if a bot is to act. */
    private void scheduleBots(Room room) {
        if (!"campaign".equals(room.status) || room.winner != null) return;
        List<Party> order = turnOrder(room);
        if (order.isEmpty() || room.turnIndex >= order.size()) return;
        if (order.get(room.turnIndex).bot != null) bots.schedule(room.code, this::playBotTurn, room.lastBattle == null ? null : room.lastBattle.id);
    }

    /** One battle between two parties: one die per unit, the higher total wins, the loser loses one unit. */
    static Battle.Result fight(Room room, Party attacker, Party defender, IntSupplier d6) {
        Battle.Result result = Battle.roll(Battle.dice(attacker), attacker.attackBoost,
            Battle.dice(defender), defenseModifier(defender), d6);
        reveal(room, attacker);
        reveal(room, defender);
        Party loser = result.attackerWon() ? defender : attacker;
        room.events.add(String.format(Locale.ROOT, "%s attacked %s: %s vs. %s. %s",
            attacker.name, defender.name, dice(result.attackerDice(), result.attackerTotal()),
            dice(result.defenderDice(), result.defenderTotal()),
            loser.units > 0 ? loser.name + " lost 1 unit." : loser.name + " was defeated."));
        applyLoss(room, loser);
        room.lastBattle = BattleLog.of(room, attacker, defender.id, defender.name, defender.units,
            defender.initialUnits, !defender.alive, result, null);
        checkVictory(room);
        return result;
    }

    /**
     * Attacking a stronghold works like attacking a party: its garrison rolls one die per unit
     * (bare walls roll one), the higher total wins and the loser loses one unit. Beating a
     * stronghold with no garrison left captures it.
     */
    static Battle.Result siege(Room room, Party attacker, Objective objective, IntSupplier d6) {
        Battle.Result result = Battle.roll(Battle.dice(attacker), attacker.attackBoost,
            Battle.dice(objective), 0, d6);
        boolean captured = result.attackerWon() && objective.units == 0;
        reveal(room, attacker);
        String outcome;
        if (!result.attackerWon()) {
            outcome = objective.name + " held; " + (attacker.units > 0
                ? attacker.name + " lost 1 unit." : attacker.name + " was defeated.");
            applyLoss(room, attacker);
        } else if (captured) {
            outcome = attacker.name + " captured " + objective.name + ".";
            objective.owner = attacker.team;
        } else {
            objective.units -= 1;
            outcome = objective.name + "'s garrison lost 1 unit.";
        }
        room.events.add(String.format(Locale.ROOT, "%s attacked %s: %s vs. %s. %s",
            attacker.name, objective.name, dice(result.attackerDice(), result.attackerTotal()),
            dice(result.defenderDice(), result.defenderTotal()), outcome));
        room.lastBattle = BattleLog.of(room, attacker, objective.id, objective.name, objective.units,
            objective.initialUnits, false, result, captured);
        checkVictory(room);
        return result;
    }

    /** After an attack: keep the turn valid if the attacker fell, else end it when nothing is left to do. */
    static void finishAction(Room room) {
        if (room.winner != null) return;
        if (room.turnIndex >= turnOrder(room).size()) {
            room.turnIndex = turnOrder(room).size() - 1;
            advanceTurn(room);
            return;
        }
        advanceIfNoActions(room);
    }

    private static String dice(List<Integer> dice, int total) {
        return dice.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining("-"))
            + (dice.size() > 1 ? " (" + total + ")" : "");
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
        if (!atMountDoom(room, frodo)) {
            throw conflict("The Ring can only be destroyed at Mount Doom.");
        }
        resolveRingDestruction(room, frodo, GameSessionController::rollDie);
        save(room);
        return view(room, seat);
    }

    /**
     * Every living opposing party at Mount Doom fights Frodo once, in turn order, before the
     * Ring can be destroyed. If Frodo falls the Ring is captured; if he survives them all, the
     * Ring is destroyed.
     */
    static void resolveRingDestruction(Room room, Party frodo, IntSupplier d6) {
        List<Party> interceptors = turnOrder(room).stream()
            .filter(party -> party.alive && !party.team.equals(frodo.team) && atMountDoom(room, party))
            .toList();
        if (!interceptors.isEmpty()) {
            reveal(room, frodo);
            interceptors.forEach(party -> reveal(room, party));
            room.events.add(String.join(", ", interceptors.stream().map(party -> party.name).toList())
                + (interceptors.size() == 1 ? " intercepts" : " intercept")
                + " " + frodo.name + " at Mount Doom.");
        }
        for (Party attacker : interceptors) {
            if (!attacker.alive) continue;
            fight(room, attacker, frodo, d6);
            if (!"frodo".equals(room.ringHolder)) return;
        }
        room.ringHolder = "destroyed";
        room.winner = "Free Peoples";
        room.events.add("The Ring is destroyed in Mount Doom. The Free Peoples win!");
    }

    private static void reveal(Room room, Party party) {
        party.hidden = false;
        party.visibleUntilRound = room.round;
    }

    /** Defending on volcanic ground (Mordor, Mount Doom) costs one pip on every defense die. */
    private static int defenseModifier(Party defender) {
        return "volcanic".equals(defender.terrain) || "mount-doom".equals(defender.terrain) ? -1 : 0;
    }

    /**
     * One lost battle: remove an army unit, or defeat the character once its army is gone.
     * Frodo falling hands the Ring to Sauron.
     */
    static void applyLoss(Room room, Party party) {
        if (!party.alive) return;
        if (party.units > 0) {
            party.units -= 1;
            return;
        }
        int index = turnOrder(room).indexOf(party);
        party.alive = false;
        if (index >= 0 && index < room.turnIndex) room.turnIndex -= 1;
        room.events.add(party.name + " has fallen.");
        if ("frodo".equals(party.id) && "frodo".equals(room.ringHolder)) {
            room.ringHolder = "sauron";
            room.events.add("Sauron's Forces seized the Ring.");
        }
    }

    /**
     * Mount Doom is a small area, not a single point: anywhere within this distance counts.
     * Measured in % of map width (2.5 = 25 miles on a 1,000-mile map), in both directions.
     */
    static final double MOUNT_DOOM_REACH = 2.5;

    /** Distance in % of map width; y is % of height, and the map is half as tall as wide. */
    private static double mapDistance(double x1, double y1, double x2, double y2) {
        return Math.hypot(x1 - x2, (y1 - y2) * 0.5);
    }

    static boolean atMountDoom(Room room, Party party) {
        return mapDistance(party.x, party.y, room.mountDoomX, room.mountDoomY) <= MOUNT_DOOM_REACH;
    }

    /** Destinations on Mount Doom's slopes land on the summit itself, so parties meet there. */
    static double[] snapToMountDoom(Room room, double x, double y) {
        return mapDistance(x, y, room.mountDoomX, room.mountDoomY) <= MOUNT_DOOM_REACH
            ? new double[] {room.mountDoomX, room.mountDoomY} : new double[] {x, y};
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
            && atMountDoom(room, active);
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
        if (GameSession.IRON_THRONE.equals(session.getGameType())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "That code is for a game of The Iron Throne.");
        }
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
        scheduleBots(room);
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
                    hidden ? null : party.attacked, hidden ? null : party.terrain, party.bot
                );
            }).toList();
        List<SeatView> seatViews = room.parties.stream()
            .map(party -> new SeatView(party.id, party.name, party.team, party.army,
                party.initialUnits, party.playerName, seatTaken(party),
                representatives(party), party.bot))
            .toList();
        Party active = "campaign".equals(room.status) ? currentTurnParty(room) : null;
        String activeCharacter = active == null ? null : active.id;
        String team = active == null ? null : active.team;
        List<ObjectiveView> objectiveViews = room.objectives.stream()
            .map(city -> new ObjectiveView(city.id, city.name, city.x, city.y, city.owner, city.armorClass,
                city.units, city.initialUnits))
            .toList();
        return new GameView(room.code, room.status, room.mapWidthMiles, room.round, team,
            activeCharacter, room.ringHolder, room.winner, partyViews, objectiveViews,
            List.copyOf(room.events), seatViews, host, ownParty == null ? null : ownParty.id,
            room.hostName, room.createdAt, room.mountDoomX, room.mountDoomY, MOUNT_DOOM_REACH,
            room.lastBattle);
    }

    /**
     * The seat this request acts as: "host", a character id, or null when it names none.
     * A named seat must be backed by that seat's cookie on this device.
     */
    static String authenticate(Room room, HttpServletRequest http) {
        String seat = SeatSessions.requestedSeat(http);
        if (seat == null) return null;
        String expectedHash = SeatSessions.HOST_SEAT.equals(seat) ? room.hostTokenHash
            // An unclaimed seat has no hash; findFirst() on a mapped null would throw.
            : room.parties.stream().filter(party -> party.id.equals(seat))
                .findFirst().map(party -> party.playerTokenHash).orElse(null);
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

    static List<Party> roster(int players) {
        List<Party> roster = new ArrayList<>(ROSTER);
        if (players == 8) roster.addAll(EXPANSION_ROSTER);
        return roster;
    }

    static List<Objective> objectives(int players) {
        List<Objective> objectives = new ArrayList<>(OBJECTIVES);
        if (players == 8) objectives.addAll(EXPANSION_OBJECTIVES);
        return objectives;
    }

    /** The leaders this party may be represented by; parties without alternatives keep their own. */
    static List<String> representatives(Party party) {
        return REPRESENTATIVES.getOrDefault(party.id, List.of(party.name));
    }

    /** Renames the party to its chosen representative; null keeps the current one. */
    static void chooseRepresentative(Party party, String representative) {
        if (representative == null || representative.isBlank()) return;
        String choice = representative.trim();
        if (!representatives(party).contains(choice)) {
            throw badRequest(choice + " cannot represent the " + party.army + ".");
        }
        party.name = choice;
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

    private static int rollDie() {
        return RANDOM.nextInt(6) + 1;
    }

    static boolean sameLocation(double x1, double y1, double x2, double y2) {
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

    private record CreateGameRequest(String playerName, Double mapWidthMiles, Integer players) {}
    private record JoinGameRequest(String playerName, String characterId, String representative) {}
    private record CharacterRequest(String characterId) {}
    private record MoveRequest(String characterId, double x, double y) {}
    private record MovementPreviewView(double x, double y, double miles, double movementPoints, double remaining,
                                       int riverCrossings, String destinationTerrain,
                                       List<MovementSegmentView> segments) {}
    private record MovementSegmentView(String terrain, double miles, double movementPoints) {}
    private record AttackRequest(String characterId, String targetId) {}
    private record BotRequest(String characterId, String difficulty, String representative) {}
    record SeatView(String id, String name, String team, String army, int initialUnits,
                           String playerName, boolean connected, List<String> representatives, String bot) {}
    record PartyView(String id, String name, String team, String army, Integer units,
                             int initialUnits, Double x, Double y, Double movementRemaining,
                             double movementMax, int attackBoost, int armorClass, boolean ring,
                             boolean alive, boolean hidden, String playerName,
                             Boolean moved, Boolean attacked, String terrain, String bot) {}
    record ObjectiveView(String id, String name, double x, double y, String owner,
                                 int armorClass, int units, int initialUnits) {}
    record GameView(String code, String status, double mapWidthMiles, int round,
                            String activeTeam, String activeCharacter, String ringHolder,
                            String winner, List<PartyView> parties, List<ObjectiveView> objectives,
                            List<String> events, List<SeatView> seats, boolean host,
                            String ownCharacterId, String hostName, String createdAt,
                            double mountDoomX, double mountDoomY, double mountDoomReach,
                            BattleLog lastBattle) {}

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
        /** The most recent battle's dice, shown to every player. */
        public BattleLog lastBattle;
        /** Counts battles so clients can tell a new roll from one they've already shown. */
        public int battleCount;
    }

    public static class BattleLog {
        public int id;
        public int round;
        public String attackerId;
        public String attacker;
        public int attackerUnits;
        public int attackerInitialUnits;
        public boolean attackerFell;
        public String defenderId;
        public String defender;
        public Integer defenderUnits;
        public Integer defenderInitialUnits;
        public boolean defenderFell;
        public List<Integer> attackerDice = new ArrayList<>();
        public List<Integer> defenderDice = new ArrayList<>();
        public int attackerLosses;
        public int defenderLosses;
        public int attackerTotal;
        public int defenderTotal;
        /** For stronghold attacks: whether it was captured; null for battles between parties. */
        public Boolean captured;

        public BattleLog() {}

        /** Recorded after casualties, so unit counts are the survivors. */
        static BattleLog of(Room room, Party attacker, String defenderId, String defenderName,
                            Integer defenderUnits, Integer defenderInitialUnits, boolean defenderFell,
                            Battle.Result result, Boolean captured) {
            BattleLog log = new BattleLog();
            log.id = ++room.battleCount;
            log.round = room.round;
            log.attackerId = attacker.id;
            log.attacker = attacker.name;
            log.attackerUnits = attacker.units;
            log.attackerInitialUnits = attacker.initialUnits;
            log.attackerFell = !attacker.alive;
            log.defenderId = defenderId;
            log.defender = defenderName;
            log.defenderUnits = defenderUnits;
            log.defenderInitialUnits = defenderInitialUnits;
            log.defenderFell = defenderFell;
            log.attackerDice = new ArrayList<>(result.attackerDice());
            log.defenderDice = new ArrayList<>(result.defenderDice());
            log.attackerLosses = result.attackerLosses();
            log.defenderLosses = result.defenderLosses();
            log.attackerTotal = result.attackerTotal();
            log.defenderTotal = result.defenderTotal();
            log.captured = captured;
            return log;
        }
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
        /** "easy" or "hard" when a bot plays this party; null for a person. */
        public String bot;
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
        /** The garrison: one die per unit when attacked, one lost per battle the walls lose. */
        public int units;
        public int initialUnits;

        public Objective() {}

        Objective(String id, String name, String placeName, String owner, int units) {
            this.id = id;
            this.name = name;
            this.placeName = placeName;
            this.owner = owner;
            this.units = units;
            this.initialUnits = units;
        }

        Objective copyAt(double x, double y) {
            Objective copy = new Objective(id, name, placeName, owner, initialUnits);
            copy.x = x;
            copy.y = y;
            return copy;
        }
    }

}
