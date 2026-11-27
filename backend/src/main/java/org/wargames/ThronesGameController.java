package org.wargames;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.wargames.ThronesGame.RuleException;
import org.wargames.ThronesState.BattleLog;
import org.wargames.ThronesState.Castle;
import org.wargames.ThronesState.House;
import org.wargames.ThronesState.Room;
import org.wargames.model.GameSession;
import org.wargames.repository.GameSessionRepository;

/** The Iron Throne: a Game of Thrones campaign. Seats work as in War of the Ring (SeatSessions). */
@RestController
@RequestMapping("/api/thrones")
public class ThronesGameController {
    static final String API_PATH = "/api/thrones/";
    private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final GameSessionRepository games;
    private final ObjectMapper objectMapper;
    private final ThronesMap map;
    private final ThronesGame game;
    private final BotScheduler bots;
    private final TransactionTemplate transactions;

    public ThronesGameController(GameSessionRepository games, ObjectMapper objectMapper, ThronesMap map,
                                 BotScheduler bots, PlatformTransactionManager transactionManager) {
        this.games = games;
        this.objectMapper = objectMapper;
        this.map = map;
        this.game = new ThronesGame(map, RANDOM);
        this.game.inlineAi = false;   // the Dead's turns are paced by BotScheduler
        this.bots = bots;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @PostMapping
    @Transactional
    public ThronesView create(@RequestBody CreateRequest request, HttpServletRequest http, HttpServletResponse response) {
        String name = cleanName(request.playerName());
        double miles = request.mapWidthMiles() == null ? 2500 : request.mapWidthMiles();
        if (miles < 1000 || miles > 6000) throw badRequest("Map width must be between 1,000 and 6,000 miles.");
        String secret = SeatSessions.newSecret();
        // There is no round limit any more, and the server always plays the Dead.
        Room room = game.create(generateCode(), name, SeatSessions.hash(secret), miles, 0, true, Instant.now().toString());
        save(room);
        SeatSessions.issue(http, response, API_PATH, room.code, SeatSessions.HOST_SEAT, secret);
        return view(room, SeatSessions.HOST_SEAT);
    }

    @GetMapping("/{code}")
    @Transactional(readOnly = true)
    public ThronesView get(@PathVariable String code, HttpServletRequest http) {
        Room room = load(code);
        String seat = authenticate(room, http);
        if (seat == null && !"lobby".equals(room.status)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "A seat on this device is required.");
        }
        scheduleBots(room);
        return view(room, seat);
    }

    @DeleteMapping("/{code}/session")
    public void leave(@PathVariable String code, HttpServletRequest http, HttpServletResponse response) {
        String seat = SeatSessions.requestedSeat(http);
        if (seat == null || seat.isEmpty()) throw badRequest("Name the seat to forget.");
        SeatSessions.clear(http, response, API_PATH, code.toUpperCase(Locale.ROOT), seat);
    }

    @PostMapping("/{code}/join")
    @Transactional
    public ThronesView join(@PathVariable String code, @RequestBody JoinRequest request,
                            HttpServletRequest http, HttpServletResponse response) {
        Room room = load(code);
        String secret = SeatSessions.newSecret();
        String name = cleanName(request.playerName());
        rules(() -> game.join(room, request.houseId(), name, request.heir(), SeatSessions.hash(secret)));
        save(room);
        SeatSessions.issue(http, response, API_PATH, room.code, request.houseId(), secret);
        return view(room, request.houseId());
    }

    /**
     * Host only, in the lobby: a bot claims {@code houseId}, or every unclaimed house (but the
     * Dead) when none is named. {@code difficulty} is "easy" or "hard".
     */
    @PostMapping("/{code}/bots")
    @Transactional
    public ThronesView addBots(@PathVariable String code, @RequestBody BotRequest request, HttpServletRequest http) {
        Room room = load(code);
        String seat = requireHostInLobby(room, http);
        String level = request.difficulty() == null || ThronesBot.EASY.equalsIgnoreCase(request.difficulty()) ? ThronesBot.EASY
            : ThronesBot.HARD.equalsIgnoreCase(request.difficulty()) ? ThronesBot.HARD : null;
        if (level == null) throw badRequest("Bot difficulty must be easy or hard.");
        List<House> targets = request.houseId() == null || request.houseId().isBlank()
            ? room.houses.stream().filter(house -> !house.claimed && !ThronesRules.isDead(house.id)).toList()
            : List.of(rulesHouse(room, request.houseId()));
        if (targets.isEmpty()) throw conflict("Every house is already claimed.");
        for (House house : targets) {
            if (ThronesRules.isDead(house.id)) {
                throw badRequest("Leave the Dead unclaimed and let the server play them instead.");
            }
            if (house.claimed) throw conflict(house.name + " is already claimed.");
            if (targets.size() == 1 && request.heir() != null && !request.heir().isBlank()) {
                String heir = request.heir().trim();
                if (!house.heirs.contains(heir)) throw badRequest(heir + " is not of " + house.name + ".");
                house.heirs.remove(heir);
                house.heirs.add(0, heir);
                house.leader = heir;
            }
            house.claimed = true;
            house.bot = level;
            house.playerName = ThronesBot.HARD.equals(level) ? "Bot (hard)" : "Bot (easy)";
            room.events.add((ThronesBot.EASY.equals(level) ? "An easy" : "A hard") + " bot claimed " + house.name + ".");
        }
        save(room);
        return view(room, seat);
    }

    /** Host only, in the lobby: frees a bot's house for a player. */
    @DeleteMapping("/{code}/bots/{houseId}")
    @Transactional
    public ThronesView removeBot(@PathVariable String code, @PathVariable String houseId, HttpServletRequest http) {
        Room room = load(code);
        String seat = requireHostInLobby(room, http);
        House house = rulesHouse(room, houseId);
        if (house.bot == null) throw conflict(house.name + " is not played by a bot.");
        house.bot = null;
        house.claimed = false;
        house.playerName = null;
        room.events.add(house.name + "'s bot left; the house is open.");
        save(room);
        return view(room, seat);
    }

    private String requireHostInLobby(Room room, HttpServletRequest http) {
        String seat = authenticate(room, http);
        if (!SeatSessions.HOST_SEAT.equals(seat)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the host can add or remove bots.");
        }
        if (!"lobby".equals(room.status)) throw conflict("Bots can only be changed before the game starts.");
        return seat;
    }

    private static House rulesHouse(Room room, String houseId) {
        try {
            return ThronesGame.house(room, houseId);
        } catch (RuleException unknown) {
            throw badRequest(unknown.getMessage());
        }
    }

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
            room = load(code);
        } catch (ResponseStatusException gone) {
            return;
        }
        if (!"campaign".equals(room.status) || !room.winners.isEmpty()) return;
        House bot = game.current(room);
        if (bot.bot == null || !bot.alive) return;
        ThronesBot.playTurn(game, map, room, bot, RANDOM);
        save(room);
    }

    /** Queues the next bot turn if a bot house is to act. */
    private void scheduleBots(Room room) {
        if (!"campaign".equals(room.status) || !room.winners.isEmpty()) return;
        if (game.turnOrder(room).isEmpty()) return;
        if (game.current(room).bot != null) bots.schedule(room.code, this::playBotTurn, room.lastBattle == null ? null : room.lastBattle.id);
    }

    @PostMapping("/{code}/start")
    @Transactional
    public ThronesView start(@PathVariable String code, HttpServletRequest http) {
        Room room = load(code);
        String seat = authenticate(room, http);
        if (!SeatSessions.HOST_SEAT.equals(seat)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the host can start the game.");
        }
        rules(() -> game.start(room));
        save(room);
        return view(room, seat);
    }

    @PostMapping("/{code}/movement-preview")
    @Transactional(readOnly = true)
    public PreviewView preview(@PathVariable String code, @RequestBody MoveRequest request, HttpServletRequest http) {
        Room room = load(code);
        House house = seatHouse(room, authenticate(room, http));
        ThronesGame.MovePlan plan;
        try {
            game.requireTurn(room, house);
            plan = game.plan(room, house, request.x(), request.y());
        } catch (RuleException refused) {
            return new PreviewView(request.x(), request.y(), 0, 0, house.movementRemaining, 0, null, "", List.of(),
                refused.getMessage());
        }
        return new PreviewView(plan.x(), plan.y(), plan.miles(), plan.movementPoints(),
            house.movementRemaining - plan.movementPoints(), plan.riverCrossings(), plan.destinationTerrain(),
            plan.mode(), plan.segments().stream().map(segment -> new SegmentView(segment.terrain(), segment.miles(),
                segment.movementPoints())).toList(), null);
    }

    @PostMapping("/{code}/move")
    @Transactional
    public ThronesView move(@PathVariable String code, @RequestBody MoveRequest request, HttpServletRequest http) {
        return act(code, http, (room, house) -> game.move(room, house, request.x(), request.y()));
    }

    @PostMapping("/{code}/attack")
    @Transactional
    public ThronesView attack(@PathVariable String code, @RequestBody TargetRequest request, HttpServletRequest http) {
        return act(code, http, (room, house) -> game.attack(room, house, request.targetId()));
    }

    @PostMapping("/{code}/end-turn")
    @Transactional
    public ThronesView endTurn(@PathVariable String code, HttpServletRequest http) {
        return act(code, http, (room, house) -> game.endTurn(room, house));
    }

    @PostMapping("/{code}/recruit")
    @Transactional
    public ThronesView recruit(@PathVariable String code, @RequestBody CountRequest request, HttpServletRequest http) {
        return act(code, http, (room, house) -> game.recruit(room, house, request.count() == null ? 1 : request.count()));
    }

    @PostMapping("/{code}/power")
    @Transactional
    public ThronesView power(@PathVariable String code, @RequestBody TargetRequest request, HttpServletRequest http) {
        return act(code, http, (room, house) -> game.usePower(room, house, request.targetId()));
    }

    // Invitations can be sent and answered at any time, even while a bot is taking its turn, so
    // they run under that game's lock (not one transaction): the load and the save can't
    // interleave with a bot's.
    @PostMapping("/{code}/coalitions/invite")
    public ThronesView invite(@PathVariable String code, @RequestBody CoalitionRequest request, HttpServletRequest http) {
        return BotScheduler.withLock(code.toUpperCase(Locale.ROOT), () -> act(code, http, (room, house) ->
            game.invite(room, house, request.houseId(), Boolean.TRUE.equals(request.royal())), false));
    }

    @PostMapping("/{code}/coalitions/respond")
    public ThronesView respond(@PathVariable String code, @RequestBody CoalitionRequest request, HttpServletRequest http) {
        return BotScheduler.withLock(code.toUpperCase(Locale.ROOT), () -> act(code, http, (room, house) ->
            game.respond(room, house, request.houseId(), Boolean.TRUE.equals(request.accept())), false));
    }

    @PostMapping("/{code}/coalitions/leave")
    @Transactional
    public ThronesView leave(@PathVariable String code, HttpServletRequest http) {
        return act(code, http, (room, house) -> game.leave(room, house));
    }

    private ThronesView act(String code, HttpServletRequest http, BiConsumer<Room, House> action) {
        return act(code, http, action, true);
    }

    /** Runs a player's action. Pact offers and answers may happen outside one's own turn. */
    private ThronesView act(String code, HttpServletRequest http, BiConsumer<Room, House> action, boolean ownTurn) {
        Room room = load(code);
        if (!"campaign".equals(room.status)) {
            throw conflict(room.winnerText != null ? "The game is over. " + room.winnerText : "The game has not started.");
        }
        String seat = authenticate(room, http);
        House house = seatHouse(room, seat);
        rules(() -> {
            if (ownTurn) game.requireTurn(room, house);
            action.accept(room, house);
            game.afterAction(room, house.id);
        });
        save(room);
        return view(room, seat);
    }

    private static void rules(Runnable action) {
        try {
            action.run();
        } catch (RuleException refused) {
            throw conflict(refused.getMessage());
        }
    }

    private House seatHouse(Room room, String seat) {
        if (seat == null || SeatSessions.HOST_SEAT.equals(seat)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This device does not hold a house in this game.");
        }
        return room.houses.stream().filter(house -> house.id.equals(seat)).findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "This device does not hold a house in this game."));
    }

    static String authenticate(Room room, HttpServletRequest http) {
        String seat = SeatSessions.requestedSeat(http);
        if (seat == null) return null;
        String expected = SeatSessions.HOST_SEAT.equals(seat) ? room.hostTokenHash
            : room.houses.stream().filter(house -> house.id.equals(seat)).findFirst()
                .map(house -> house.playerTokenHash).orElse(null);
        if (!SeatSessions.matches(SeatSessions.cookieSecret(http, seat), expected)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "This device does not hold that seat. Rejoin from the device you joined with.");
        }
        return seat;
    }

    private Room load(String code) {
        GameSession session = games.findById(code.toUpperCase(Locale.ROOT))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Game code not found."));
        if (!GameSession.IRON_THRONE.equals(session.getGameType())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "That code is for a game of War of the Ring.");
        }
        try {
            return objectMapper.readValue(session.getState(), Room.class);
        } catch (JsonProcessingException error) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not read the saved game.", error);
        }
    }

    private void save(Room room) {
        if (room.events.size() > 400) room.events = new java.util.ArrayList<>(room.events.subList(room.events.size() - 300, room.events.size()));
        try {
            games.save(new GameSession(room.code, objectMapper.writeValueAsString(room), GameSession.IRON_THRONE));
        } catch (JsonProcessingException error) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not save the game.", error);
        }
        scheduleBots(room);
    }

    // ------------------------------------------------------------------ view

    ThronesView view(Room room, String seat) {
        boolean host = SeatSessions.HOST_SEAT.equals(seat);
        House own = room.houses.stream().filter(house -> house.id.equals(seat)).findFirst().orElse(null);
        boolean started = !"lobby".equals(room.status);
        House active = "campaign".equals(room.status) ? game.current(room) : null;
        List<HouseView> houses = room.houses.stream().map(house -> {
            boolean seen = !house.hidden || (own != null && game.friendly(room, own.id, house.id));
            boolean show = started && house.alive && seen;
            return new HouseView(house.id, house.name, house.seat, house.leader, house.heirs, house.heirIndex,
                show ? house.units : null, show ? house.x : null, show ? house.y : null, house.gold, house.playerName,
                house.claimed, house.ai, house.alive, house.hidden, show ? house.terrain : null,
                show ? house.moved : null, show ? house.attacked : null, house.attacksLeft, house.movementRemaining,
                house.movementMax, house.powerUsed, house.oathbreakerUntil, house.lockedUntil, house.dragons,
                started ? game.income(room, house) : 0, castlesHeld(room, house.id),
                ThronesRules.house(house.id).power(), house.recruitedThisTurn, house.screamers, house.bot,
                ThronesRules.isDead(house.id), house.forged, started && house.alive && game.sworn(room, house));
        }).toList();
        List<CastleView> castles = room.castles.stream().map(castle -> new CastleView(castle.id, castle.name, castle.kind,
            castle.region, castle.x, castle.y, castle.owner, castle.units, castle.initialUnits, castle.great, castle.port))
            .toList();
        ThronesRules.Fate fate = room.fate == null ? null : ThronesRules.fate(room.fate);
        List<String> events = room.events.size() > 120 ? room.events.subList(room.events.size() - 120, room.events.size())
            : room.events;
        return new ThronesView(room.code, room.status, room.round, game.season(room), room.winterRound, room.longNight,
            room.snowLine, room.mapWidthMiles, host, own == null ? null : own.id, room.hostName,
            active == null ? null : active.id, houses, castles,
            room.coalitions.stream().map(coalition -> new CoalitionView(coalition.id, List.copyOf(coalition.members),
                coalition.sinceRound)).toList(),
            room.invites.stream().map(invite -> new InviteView(invite.from, invite.to, invite.royal)).toList(),
            fate == null ? null : new FateView(fate.id(), fate.title(), fate.text(), room.fateRound, room.fateCount),
            room.castles.size(), room.wallBreached, room.iceDragon, room.winners, room.winnerText,
            List.copyOf(events), room.lastBattle, map.wall(), started ? List.copyOf(room.turnOrderIds) : List.of(),
            own == null || !started ? 0 : game.recruitCost(room, own),
            map.places().stream().map(place -> new PlaceView(place.name(), place.kind(), place.x(), place.y(),
                place.region())).toList());
    }

    private static long castlesHeld(Room room, String houseId) {
        return room.castles.stream().filter(castle -> castle.owner.equals(houseId)).count();
    }

    private String generateCode() {
        for (int attempt = 0; attempt < 20; attempt++) {
            StringBuilder code = new StringBuilder();
            for (int index = 0; index < 6; index++) code.append(CODE_ALPHABET.charAt(RANDOM.nextInt(CODE_ALPHABET.length())));
            if (!games.existsById(code.toString())) return code.toString();
        }
        throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not generate a game code.");
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

    record CreateRequest(String playerName, Double mapWidthMiles, Integer roundLimit, Boolean computerDead) {}
    record JoinRequest(String playerName, String houseId, String heir) {}
    record MoveRequest(double x, double y) {}
    record TargetRequest(String targetId) {}
    record CountRequest(Integer count) {}
    record CoalitionRequest(String houseId, Boolean royal, Boolean accept) {}
    record BotRequest(String houseId, String difficulty, String heir) {}
    record SegmentView(String terrain, double miles, double movementPoints) {}
    record PreviewView(double x, double y, double miles, double movementPoints, double remaining, int riverCrossings,
                       String destinationTerrain, String mode, List<SegmentView> segments, String error) {}
    record HouseView(String id, String name, String seat, String leader, List<String> heirs, int heirIndex,
                     Integer units, Double x, Double y, int gold, String playerName, boolean claimed, boolean ai,
                     boolean alive, boolean hidden, String terrain, Boolean moved, Boolean attacked, int attacksLeft,
                     double movementRemaining, double movementMax, boolean powerUsed, int oathbreakerUntil, int lockedUntil,
                     List<String> dragons, int income, long castles, String power, int recruitedThisTurn,
                     boolean screamers, String bot, boolean dead, boolean forged, boolean sworn) {}
    record CastleView(String id, String name, String kind, String region, double x, double y, String owner, int units,
                      int initialUnits, boolean great, boolean port) {}
    record CoalitionView(String id, List<String> members, int sinceRound) {}
    record InviteView(String from, String to, boolean royal) {}
    record FateView(String id, String title, String text, int round, int count) {}
    record ThronesView(String code, String status, int round, String season, int winterRound, boolean longNight,
                       double snowLine, double mapWidthMiles, boolean host, String ownHouseId, String hostName,
                       String activeHouse, List<HouseView> houses, List<CastleView> castles,
                       List<CoalitionView> coalitions, List<InviteView> invites, FateView fate, int totalCastles,
                       boolean wallBreached, boolean iceDragon, List<String> winners,
                       String winnerText, List<String> events, BattleLog lastBattle, List<double[]> wall,
                       List<String> turnOrder, int recruitCost, List<PlaceView> places) {}
    record PlaceView(String name, String kind, double x, double y, String region) {}
}
