package io.github.wojciechkoziestanski.eventplatform.generator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;


@Component
public class DataQualityCorruptor {

    private static final Logger log = LoggerFactory.getLogger(DataQualityCorruptor.class);

    private static final Map<String, String> CITY_ABBREVIATIONS = Map.of(
            "Warszawa", "W-wa", "Kraków", "Krakow", "Łódź", "Lodz",
            "Wrocław", "Wroclaw", "Gdańsk", "Gdansk", "Poznań", "Poznan");

    private final JdbcTemplate jdbc;
    private final Random random = new Random(7);

    public DataQualityCorruptor(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void corrupt() {
        log.info("Wprowadzam celowe problemy jakości danych:");
        log.info("  różne zapisy miast (users): {}", messUpCities("users", 0.06));
        log.info("  różne zapisy miast (locations): {}", messUpCities("locations", 0.15));
        log.info("  brak miasta (users): {}", setNull("users", "city", 0.03));
        log.info("  brak kategorii (events): {}", setNull("events", "category_id", 0.03));
        log.info("  brak kodu pocztowego (locations): {}", setNull("locations", "postal_code", 0.05));
        log.info("  zduplikowani użytkownicy: {}", duplicateUsers(25));
        log.info("  błędne statusy (reservations): {}", invalidStatuses("reservations", 0.02,
                "cancelled", "CANCELED", "Confirmed", "done", "PENDNG", "COMPLETED "));
        log.info("  błędne statusy (payments): {}", invalidStatuses("payments", 0.01,
                "paid", "SUCCESS", "Paid ", "refund"));
        log.info("  zawyżone kwoty płatności x10: {}", wrongPaymentAmounts(0.01));
        log.info("  błędna liczba miejsc (0 lub -1): {}", badSeatCounts(10));
    }

    private int messUpCities(String table, double fraction) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, city FROM " + table + " WHERE city IS NOT NULL ORDER BY id");
        Collections.shuffle(rows, random);
        int n = (int) (rows.size() * fraction);
        for (Map<String, Object> row : rows.subList(0, n)) {
            jdbc.update("UPDATE " + table + " SET city = ? WHERE id = ?",
                    cityVariant((String) row.get("city")), row.get("id"));
        }
        return n;
    }

    private String cityVariant(String city) {
        return switch (random.nextInt(5)) {
            case 0 -> city.toLowerCase();                                   // "warszawa"
            case 1 -> city.toUpperCase();                                   // "WARSZAWA"
            case 2 -> city + "  ";                                          // spacje na końcu
            case 3 -> {                                                     // "Lodz"
                String stripped = DataGenerator.stripDiacritics(city);
                yield stripped.equals(city) ? " " + city : stripped;        // brak ogonków -> spacja na początku
            }
            default -> CITY_ABBREVIATIONS.getOrDefault(city, city.toUpperCase()); // "W-wa"
        };
    }

    private int setNull(String table, String column, double fraction) {
        List<Long> ids = randomIds("SELECT id FROM " + table + " WHERE " + column + " IS NOT NULL ORDER BY id", fraction);
        jdbc.batchUpdate("UPDATE " + table + " SET " + column + " = NULL WHERE id = ?", asParams(ids));
        return ids.size();
    }

    private int duplicateUsers(int count) {
        List<Map<String, Object>> users = jdbc.queryForList("""
                SELECT id, first_name, last_name, email, phone, city, birth_date, registered_at
                FROM users ORDER BY id""");
        Collections.shuffle(users, random);

        for (Map<String, Object> u : users.subList(0, count)) {
            String phone = (String) u.get("phone");
            LocalDateTime registered = ((Timestamp) u.get("registered_at")).toLocalDateTime()
                    .plusHours(1 + random.nextInt(48));

            Long duplicateId = jdbc.queryForObject("""
                    INSERT INTO users (first_name, last_name, email, phone, city, birth_date, registered_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id""", Long.class,
                    u.get("first_name"), u.get("last_name"),
                    emailVariant((String) u.get("email")),
                    phone == null ? null : phone.replace(" ", ""),   // "+48 600 123 456" -> "+48600123456"
                    u.get("city"), u.get("birth_date"), registered);

            // duplikat "przejmuje" najnowszą rezerwację oryginału - jak w życiu, gdy ktoś założył drugie konto
            jdbc.update("""
                    UPDATE reservations SET user_id = ?
                    WHERE id = (SELECT id FROM reservations WHERE user_id = ? ORDER BY reserved_at DESC LIMIT 1)""",
                    duplicateId, u.get("id"));
        }
        return count;
    }

    private String emailVariant(String email) {
        return switch (random.nextInt(3)) {
            case 0 -> email.toUpperCase();                                          // JAN.KOWALSKI@WP.PL
            case 1 -> Character.toUpperCase(email.charAt(0)) + email.substring(1);  // Jan.kowalski@wp.pl
            default -> email + " ";                                                 // spacja na końcu
        };
    }

    private int invalidStatuses(String table, double fraction, String... badValues) {
        List<Long> ids = randomIds("SELECT id FROM " + table + " ORDER BY id", fraction);
        List<Object[]> params = new ArrayList<>();
        for (Long id : ids) {
            params.add(new Object[]{badValues[random.nextInt(badValues.length)], id});
        }
        jdbc.batchUpdate("UPDATE " + table + " SET status = ? WHERE id = ?", params);
        return ids.size();
    }

    private int wrongPaymentAmounts(double fraction) {
        List<Long> ids = randomIds("SELECT id FROM payments WHERE status = 'PAID' ORDER BY id", fraction);
        jdbc.batchUpdate("UPDATE payments SET amount = amount * 10 WHERE id = ?", asParams(ids));
        return ids.size();
    }

    private int badSeatCounts(int count) {
        List<Long> all = jdbc.queryForList("SELECT id FROM reservations ORDER BY id", Long.class);
        Collections.shuffle(all, random);
        List<Object[]> params = new ArrayList<>();
        for (Long id : all.subList(0, count)) {
            params.add(new Object[]{random.nextBoolean() ? 0 : -1, id});
        }
        jdbc.batchUpdate("UPDATE reservations SET seats_count = ? WHERE id = ?", params);
        return count;
    }

    private List<Long> randomIds(String sql, double fraction) {
        List<Long> ids = jdbc.queryForList(sql, Long.class);
        Collections.shuffle(ids, random);
        return ids.subList(0, (int) (ids.size() * fraction));
    }

    private static List<Object[]> asParams(List<Long> ids) {
        return ids.stream().map(id -> new Object[]{id}).toList();
    }
}