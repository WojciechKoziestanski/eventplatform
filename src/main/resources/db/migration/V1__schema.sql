CREATE TABLE users (
                       id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                       first_name    VARCHAR(100) NOT NULL,
                       last_name     VARCHAR(100) NOT NULL,
                       email         VARCHAR(255) NOT NULL UNIQUE,
                       phone         VARCHAR(30),
                       city          VARCHAR(100),
                       birth_date    DATE,
                       registered_at TIMESTAMP    NOT NULL DEFAULT now()
);

CREATE TABLE organizers (
                            id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                            name       VARCHAR(200) NOT NULL,
                            email      VARCHAR(255),
                            phone      VARCHAR(30),
                            website    VARCHAR(255),
                            created_at TIMESTAMP    NOT NULL DEFAULT now()
);

CREATE TABLE categories (
                            id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                            name        VARCHAR(100) NOT NULL UNIQUE,
                            description TEXT
);

CREATE TABLE locations (
                           id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                           name        VARCHAR(200) NOT NULL,
                           address     VARCHAR(255),
                           city        VARCHAR(100),
                           postal_code VARCHAR(10),
                           capacity    INT CHECK (capacity > 0),
                           latitude    NUMERIC(9,6),
                           longitude   NUMERIC(9,6)
);

CREATE TABLE events (
                        id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                        title        VARCHAR(255)  NOT NULL,
                        description  TEXT,
                        category_id  BIGINT REFERENCES categories(id),
                        organizer_id BIGINT        NOT NULL REFERENCES organizers(id),
                        location_id  BIGINT        NOT NULL REFERENCES locations(id),
                        start_at     TIMESTAMP     NOT NULL,
                        end_at       TIMESTAMP,
                        ticket_price NUMERIC(10,2) NOT NULL CHECK (ticket_price >= 0),
                        total_seats  INT           NOT NULL CHECK (total_seats > 0),
                        status       VARCHAR(20)   NOT NULL,
                        created_at   TIMESTAMP     NOT NULL DEFAULT now(),
                        CHECK (end_at IS NULL OR end_at > start_at)
);

CREATE TABLE reservations (
                              id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                              user_id      BIGINT        NOT NULL REFERENCES users(id),
                              event_id     BIGINT        NOT NULL REFERENCES events(id),
                              seats_count  INT           NOT NULL,
                              unit_price   NUMERIC(10,2) NOT NULL,
                              total_amount NUMERIC(10,2) NOT NULL,
                              status       VARCHAR(20)   NOT NULL,
                              reserved_at  TIMESTAMP     NOT NULL,
                              updated_at   TIMESTAMP
);

CREATE TABLE payments (
                          id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                          reservation_id BIGINT        NOT NULL REFERENCES reservations(id),
                          amount         NUMERIC(10,2) NOT NULL,
                          method         VARCHAR(20)   NOT NULL,
                          status         VARCHAR(20)   NOT NULL,
                          created_at     TIMESTAMP     NOT NULL,
                          paid_at        TIMESTAMP
);

CREATE TABLE reservation_status_history (
                                            id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                                            reservation_id BIGINT      NOT NULL REFERENCES reservations(id),
                                            old_status     VARCHAR(20),
                                            new_status     VARCHAR(20) NOT NULL,
                                            changed_at     TIMESTAMP   NOT NULL
);

-- Indeksy na kluczach obcych (PostgreSQL nie tworzy ich automatycznie)
CREATE INDEX idx_events_category       ON events(category_id);
CREATE INDEX idx_events_organizer      ON events(organizer_id);
CREATE INDEX idx_events_location       ON events(location_id);
CREATE INDEX idx_reservations_user     ON reservations(user_id);
CREATE INDEX idx_reservations_event    ON reservations(event_id);
CREATE INDEX idx_payments_reservation  ON payments(reservation_id);
CREATE INDEX idx_status_history_resv   ON reservation_status_history(reservation_id);