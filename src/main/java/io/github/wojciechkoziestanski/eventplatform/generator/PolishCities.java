package io.github.wojciechkoziestanski.eventplatform.generator;

import java.util.List;
import java.util.Random;

public final class PolishCities {

    public record City(String name, double lat, double lon, int weight) {}

    public static final List<City> ALL = List.of(
            new City("Warszawa",  52.2297, 21.0122, 30),
            new City("Kraków",    50.0647, 19.9450, 15),
            new City("Wrocław",   51.1079, 17.0385, 12),
            new City("Łódź",      51.7592, 19.4560, 10),
            new City("Poznań",    52.4064, 16.9252, 10),
            new City("Gdańsk",    54.3520, 18.6466,  9),
            new City("Katowice",  50.2649, 19.0238,  7),
            new City("Szczecin",  53.4285, 14.5528,  6),
            new City("Lublin",    51.2465, 22.5684,  6),
            new City("Białystok", 53.1325, 23.1688,  4),
            new City("Rzeszów",   50.0412, 21.9991,  4),
            new City("Toruń",     53.0138, 18.5984,  3)
    );

    private static final int TOTAL_WEIGHT = ALL.stream().mapToInt(City::weight).sum();

    private PolishCities() {}

    public static City random(Random random) {
        int x = random.nextInt(TOTAL_WEIGHT);
        for (City city : ALL) {
            x -= city.weight();
            if (x < 0) return city;
        }
        throw new IllegalStateException("Nie wylosowano miasta - błąd w wagach");
    }
}