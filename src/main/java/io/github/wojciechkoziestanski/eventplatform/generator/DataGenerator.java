package io.github.wojciechkoziestanski.eventplatform.generator;

import net.datafaker.Faker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Component
public class DataGenerator implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataGenerator.class);

    static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 31, 12, 0);
    private static final LocalDateTime HISTORY_START = LocalDateTime.of(2024, 11, 1, 0, 0);
    private static final LocalDateTime FUTURE_END = LocalDateTime.of(2027, 3, 31, 23, 0);

    private static final int USERS = 800;
    private static final int ORGANIZERS = 40;
    private static final int LOCATIONS = 60;
    private static final int EVENTS = 250;
    private static final int RESERVATIONS = 5000;

    private final JdbcTemplate jdbc;
    private final DataQualityCorruptor corruptor;
    private final Random random = new Random(42);
    private final Faker faker = new Faker(Locale.of("pl"), random);

    record UserRef(long id, LocalDateTime registeredAt) {}
    record LocationRef(long id, String city, int capacity) {}
    record EventRef(long id, LocalDateTime createdAt, LocalDateTime startAt, LocalDateTime endAt,
                    BigDecimal price, int seats, String status, double popularity) {}
    record NewReservation(long userId, long eventId, int seats, BigDecimal unitPrice, BigDecimal total,
                          String status, LocalDateTime reservedAt, LocalDateTime updatedAt,
                          List<Object[]> history, List<Object[]> payments) {}

    public DataGenerator(JdbcTemplate jdbc, DataQualityCorruptor corruptor) {
        this.jdbc = jdbc;
        this.corruptor = corruptor;
    }

    @Override
    @Transactional
    public void run(String... args) {
        Integer existing = jdbc.queryForObject("SELECT count(*) FROM users", Integer.class);
        if (existing != null && existing > 0) {
            log.info("Baza ma już dane ({} użytkowników) - pomijam generowanie.", existing);
            return;
        }
        log.info("Generuję dane przykładowe...");
        List<UserRef> users = generateUsers();
        List<Long> organizers = generateOrganizers();
        List<LocationRef> locations = generateLocations();
        List<EventRef> events = generateEvents(organizers, locations);
        generateReservations(users, events);
        corruptor.corrupt();
        log.info("Generowanie zakończone.");
    }

    // ---------------- UŻYTKOWNICY ----------------

    private List<UserRef> generateUsers() {
        String[] domains = {"gmail.com", "wp.pl", "onet.pl", "o2.pl", "interia.pl", "outlook.com"};
        Set<String> usedEmails = new HashSet<>();
        List<Object[]> rows = new ArrayList<>();
        List<LocalDateTime> registeredDates = new ArrayList<>();

        for (int i = 0; i < USERS; i++) {
            String first = faker.name().firstName();
            String last = faker.name().lastName();
            String base = slug(first) + "." + slug(last);
            String email = base + "@" + pick(domains);
            while (!usedEmails.add(email)) {
                email = base + random.nextInt(1000) + "@" + pick(domains);
            }
            String phone = random.nextDouble() < 0.85 ? randomPhone() : null;
            String city = PolishCities.random(random).name();
            LocalDate birthDate = LocalDate.of(1960, 1, 1).plusDays(random.nextInt(365 * 48));
            LocalDateTime registeredAt = randomBetween(HISTORY_START.minusMonths(6), NOW.minusDays(1));

            rows.add(new Object[]{first, last, email, phone, city, birthDate, registeredAt});
            registeredDates.add(registeredAt);
        }
        jdbc.batchUpdate("""
                INSERT INTO users (first_name, last_name, email, phone, city, birth_date, registered_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)""", rows);

        List<Long> ids = jdbc.queryForList("SELECT id FROM users ORDER BY id", Long.class);
        List<UserRef> refs = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            refs.add(new UserRef(ids.get(i), registeredDates.get(i)));
        }
        log.info("  users: {}", refs.size());
        return refs;
    }

    // ---------------- ORGANIZATORZY ----------------

    private List<Long> generateOrganizers() {
        Set<String> usedNames = new HashSet<>();
        List<Object[]> rows = new ArrayList<>();
        while (rows.size() < ORGANIZERS) {
            String name = faker.company().name();
            if (!usedNames.add(name)) continue;
            String domain = slug(name).replaceAll("[^a-z0-9]", "") + ".pl";
            rows.add(new Object[]{name, "kontakt@" + domain, randomPhone(), "https://www." + domain,
                    randomBetween(HISTORY_START.minusYears(1), HISTORY_START.plusMonths(3))});
        }
        jdbc.batchUpdate("INSERT INTO organizers (name, email, phone, website, created_at) VALUES (?, ?, ?, ?, ?)", rows);
        log.info("  organizers: {}", rows.size());
        return jdbc.queryForList("SELECT id FROM organizers ORDER BY id", Long.class);
    }

    // ---------------- LOKALIZACJE ----------------

    private List<LocationRef> generateLocations() {
        String[] types = {"Hala", "Arena", "Centrum Kongresowe", "Klub", "Teatr",
                "Centrum Konferencyjne", "Stadion", "Galeria", "Hotel", "Dom Kultury"};
        int[] typicalCapacity = {3000, 8000, 1500, 400, 600, 800, 20000, 200, 350, 250};

        List<Object[]> rows = new ArrayList<>();
        List<String> cities = new ArrayList<>();
        List<Integer> capacities = new ArrayList<>();

        for (int i = 0; i < LOCATIONS; i++) {
            int t = random.nextInt(types.length);
            PolishCities.City city = PolishCities.random(random);
            String name = types[t] + " " + faker.name().lastName();
            int capacity = (int) (typicalCapacity[t] * (0.6 + random.nextDouble() * 0.8));
            String postalCode = String.format("%02d-%03d", random.nextInt(100), random.nextInt(1000));
            BigDecimal lat = round6(city.lat() + (random.nextDouble() - 0.5) * 0.1);
            BigDecimal lon = round6(city.lon() + (random.nextDouble() - 0.5) * 0.1);

            rows.add(new Object[]{name, faker.address().streetAddress(), city.name(), postalCode, capacity, lat, lon});
            cities.add(city.name());
            capacities.add(capacity);
        }
        jdbc.batchUpdate("""
                INSERT INTO locations (name, address, city, postal_code, capacity, latitude, longitude)
                VALUES (?, ?, ?, ?, ?, ?, ?)""", rows);

        List<Long> ids = jdbc.queryForList("SELECT id FROM locations ORDER BY id", Long.class);
        List<LocationRef> refs = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            refs.add(new LocationRef(ids.get(i), cities.get(i), capacities.get(i)));
        }
        log.info("  locations: {}", refs.size());
        return refs;
    }

    // ---------------- WYDARZENIA ----------------

    private List<EventRef> generateEvents(List<Long> organizers, List<LocationRef> locations) {
        List<Map<String, Object>> categories = jdbc.queryForList("SELECT id, name FROM categories ORDER BY id");
        if (categories.isEmpty()) {
            throw new IllegalStateException("Brak kategorii - czy migracja V2 się wykonała?");
        }

        List<Object[]> rows = new ArrayList<>();
        List<EventRef> drafts = new ArrayList<>();

        for (int i = 0; i < EVENTS; i++) {
            Map<String, Object> category = pick(categories);
            long categoryId = ((Number) category.get("id")).longValue();
            String categoryName = (String) category.get("name");
            LocationRef location = pick(locations);
            long organizerId = pick(organizers);

            LocalDateTime start = randomBetween(HISTORY_START, FUTURE_END)
                    .truncatedTo(ChronoUnit.DAYS).withHour(10 + random.nextInt(11));
            LocalDateTime end = categoryName.equals("Festiwal")
                    ? start.plusDays(1 + random.nextInt(3))
                    : start.plusHours(2 + random.nextInt(6));
            LocalDateTime createdAt = start.minusDays(30 + random.nextInt(120));
            BigDecimal price = ticketPrice(categoryName);
            int seats = Math.max(20, (int) (location.capacity() * (0.5 + random.nextDouble() * 0.5)));

            String status;
            if (end.isBefore(NOW)) status = random.nextDouble() < 0.06 ? "CANCELLED" : "FINISHED";
            else status = random.nextDouble() < 0.04 ? "CANCELLED" : "SCHEDULED";

            String title = categoryName + " " + location.city() + " " + start.getYear() + " #" + (i + 1);
            double popularity = Math.pow(random.nextDouble(), 3) * 10 + 0.3;

            rows.add(new Object[]{categoryId, organizerId, location.id(), title, faker.lorem().sentence(12),
                    start, end, price, seats, status, createdAt});
            drafts.add(new EventRef(0, createdAt, start, end, price, seats, status, popularity));
        }
        jdbc.batchUpdate("""
                INSERT INTO events (category_id, organizer_id, location_id, title, description,
                                    start_at, end_at, ticket_price, total_seats, status, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", rows);

        List<Long> ids = jdbc.queryForList("SELECT id FROM events ORDER BY id", Long.class);
        List<EventRef> refs = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            EventRef d = drafts.get(i);
            refs.add(new EventRef(ids.get(i), d.createdAt(), d.startAt(), d.endAt(),
                    d.price(), d.seats(), d.status(), d.popularity()));
        }
        log.info("  events: {}", refs.size());
        return refs;
    }

    private BigDecimal ticketPrice(String category) {
        int price = switch (category) {
            case "Meetup" -> random.nextDouble() < 0.6 ? 0 : randomStep(20, 60);
            case "Konferencja", "Szkolenie" -> randomStep(300, 1500);
            case "Koncert", "Festiwal" -> randomStep(80, 450);
            case "Wydarzenie sportowe" -> randomStep(40, 250);
            default -> randomStep(40, 200);
        };
        return BigDecimal.valueOf(price).setScale(2, RoundingMode.HALF_UP);
    }

    // ---------------- REZERWACJE + HISTORIA + PŁATNOŚCI ----------------

    private void generateReservations(List<UserRef> users, List<EventRef> events) {
        double[] cumulative = new double[events.size()];
        double sum = 0;
        for (int i = 0; i < events.size(); i++) {
            sum += events.get(i).popularity();
            cumulative[i] = sum;
        }
        int[] soldSeats = new int[events.size()];
        List<NewReservation> reservations = new ArrayList<>();

        int attempts = 0;
        while (reservations.size() < RESERVATIONS && attempts++ < RESERVATIONS * 5) {
            int e = pickWeighted(cumulative);
            EventRef event = events.get(e);
            UserRef user = users.get((int) (users.size() * Math.pow(random.nextDouble(), 1.5)));

            LocalDateTime from = max(event.createdAt(), user.registeredAt());
            LocalDateTime to = min(event.startAt(), NOW);
            if (!from.isBefore(to)) continue;

            int seats = randomSeats();
            if (soldSeats[e] + seats > event.seats()) continue;
            soldSeats[e] += seats;

            LocalDateTime reservedAt = randomBetween(from, to);
            BigDecimal unitPrice = random.nextDouble() < 0.15
                    ? event.price().multiply(new BigDecimal("0.80")).setScale(2, RoundingMode.HALF_UP)
                    : event.price();
            BigDecimal total = unitPrice.multiply(BigDecimal.valueOf(seats));
            reservations.add(buildReservation(user.id(), event, seats, unitPrice, total, reservedAt));
        }

        List<Object[]> rows = new ArrayList<>();
        for (NewReservation r : reservations) {
            rows.add(new Object[]{r.userId(), r.eventId(), r.seats(), r.unitPrice(), r.total(),
                    r.status(), r.reservedAt(), r.updatedAt()});
        }
        jdbc.batchUpdate("""
                INSERT INTO reservations (user_id, event_id, seats_count, unit_price, total_amount,
                                          status, reserved_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)""", rows);

        List<Long> ids = jdbc.queryForList("SELECT id FROM reservations ORDER BY id", Long.class);
        List<Object[]> historyRows = new ArrayList<>();
        List<Object[]> paymentRows = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            long id = ids.get(i);
            for (Object[] h : reservations.get(i).history()) {
                historyRows.add(new Object[]{id, h[0], h[1], h[2]});
            }
            for (Object[] p : reservations.get(i).payments()) {
                paymentRows.add(new Object[]{id, p[0], p[1], p[2], p[3], p[4]});
            }
        }
        jdbc.batchUpdate("""
                INSERT INTO reservation_status_history (reservation_id, old_status, new_status, changed_at)
                VALUES (?, ?, ?, ?)""", historyRows);
        jdbc.batchUpdate("""
                INSERT INTO payments (reservation_id, amount, method, status, created_at, paid_at)
                VALUES (?, ?, ?, ?, ?, ?)""", paymentRows);

        log.info("  reservations: {}, status history: {}, payments: {}",
                ids.size(), historyRows.size(), paymentRows.size());
    }

    private NewReservation buildReservation(long userId, EventRef event, int seats,
                                            BigDecimal unitPrice, BigDecimal total, LocalDateTime reservedAt) {
        List<Object[]> history = new ArrayList<>();
        List<Object[]> payments = new ArrayList<>();
        boolean free = total.signum() == 0;

        history.add(new Object[]{null, "PENDING", reservedAt});
        LocalDateTime lastChange = reservedAt;

        double r = random.nextDouble();
        String target;
        if (event.status().equals("CANCELLED")) target = "CANCELLED";
        else if (event.status().equals("FINISHED")) target = r < 0.85 ? "COMPLETED" : "CANCELLED";
        else target = r < 0.15 ? "CANCELLED" : r < 0.35 ? "PENDING" : "CONFIRMED";

        boolean confirmed = switch (target) {
            case "CONFIRMED", "COMPLETED" -> true;
            case "CANCELLED" -> random.nextDouble() < 0.5;
            default -> false;
        };

        if (confirmed) {
            LocalDateTime confirmedAt = min(min(reservedAt.plusMinutes(1 + random.nextInt(120)), event.startAt()), NOW);
            if (!free && random.nextDouble() < 0.10) {
                payments.add(new Object[]{total, randomMethod(), "FAILED", reservedAt.plusSeconds(30), null});
            }
            history.add(new Object[]{"PENDING", "CONFIRMED", confirmedAt});
            if (!free) payments.add(new Object[]{total, randomMethod(), "PAID", confirmedAt, confirmedAt});
            lastChange = confirmedAt;
        }

        switch (target) {
            case "PENDING" -> {
                if (!free && random.nextDouble() < 0.6) {
                    payments.add(new Object[]{total, randomMethod(), "PENDING", reservedAt, null});
                }
            }
            case "COMPLETED" -> {
                LocalDateTime completedAt = min(event.endAt(), NOW);
                history.add(new Object[]{"CONFIRMED", "COMPLETED", completedAt});
                lastChange = completedAt;
            }
            case "CANCELLED" -> {
                LocalDateTime cancelTo = min(event.startAt(), NOW);
                LocalDateTime cancelledAt = lastChange.isBefore(cancelTo)
                        ? randomBetween(lastChange, cancelTo) : lastChange.plusMinutes(5);
                history.add(new Object[]{confirmed ? "CONFIRMED" : "PENDING", "CANCELLED", cancelledAt});
                if (confirmed && !free) {
                    payments.getLast()[2] = "REFUNDED";
                } else if (!free && random.nextDouble() < 0.5) {
                    payments.add(new Object[]{total, randomMethod(), "FAILED", reservedAt.plusMinutes(1), null});
                }
                lastChange = cancelledAt;
            }
            default -> { }
        }
        return new NewReservation(userId, event.id(), seats, unitPrice, total, target,
                reservedAt, lastChange, history, payments);
    }

    // ---------------- POMOCNICZE ----------------

    private int randomSeats() {
        double r = random.nextDouble();
        if (r < 0.55) return 1;
        if (r < 0.85) return 2;
        return 3 + random.nextInt(4);
    }

    private String randomMethod() {
        double r = random.nextDouble();
        return r < 0.45 ? "BLIK" : r < 0.85 ? "CARD" : "TRANSFER";
    }

    private String randomPhone() {
        return String.format("+48 %d %03d %03d", 500 + random.nextInt(400), random.nextInt(1000), random.nextInt(1000));
    }

    private int randomStep(int min, int max) {
        return min + random.nextInt((max - min) / 5 + 1) * 5;
    }

    private LocalDateTime randomBetween(LocalDateTime from, LocalDateTime to) {
        long seconds = ChronoUnit.SECONDS.between(from, to);
        return from.plusSeconds((long) (random.nextDouble() * seconds));
    }

    private int pickWeighted(double[] cumulative) {
        double x = random.nextDouble() * cumulative[cumulative.length - 1];
        int idx = Arrays.binarySearch(cumulative, x);
        if (idx < 0) idx = -idx - 1;
        return Math.min(idx, cumulative.length - 1);
    }

    private <T> T pick(List<T> list) { return list.get(random.nextInt(list.size())); }
    private String pick(String[] array) { return array[random.nextInt(array.length)]; }

    private static LocalDateTime max(LocalDateTime a, LocalDateTime b) { return a.isAfter(b) ? a : b; }
    private static LocalDateTime min(LocalDateTime a, LocalDateTime b) { return a.isBefore(b) ? a : b; }

    private static BigDecimal round6(double value) {
        return BigDecimal.valueOf(value).setScale(6, RoundingMode.HALF_UP);
    }

    static String stripDiacritics(String s) {
        String n = s.replace("ł", "l").replace("Ł", "L");
        return Normalizer.normalize(n, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    }

    static String slug(String s) {
        return stripDiacritics(s).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9.]+", "");
    }
}