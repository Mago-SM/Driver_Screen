-- Apply once to the existing team MySQL database. Does not change legacy reservations/users.
CREATE TABLE IF NOT EXISTS mobile_reservations (
  user_id VARCHAR(128) NOT NULL,
  reservation_id VARCHAR(128) NOT NULL,
  service_date DATE NOT NULL,
  boarding_status VARCHAR(16) NOT NULL,
  payload JSON NOT NULL,
  updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (user_id, reservation_id),
  INDEX mobile_service_state (service_date, boarding_status)
) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;
