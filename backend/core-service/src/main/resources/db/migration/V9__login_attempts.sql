-- Wrong-password limit (review issue 7): failed sign-ins counted per email and per IP address. Kept in the database
-- so the limit holds across restarts and across several copies of the service.
CREATE TABLE login_attempts (
    key           text PRIMARY KEY,                 -- 'email:arjun@...' or 'ip:203.0.113.7'
    failures      integer NOT NULL,
    window_start  timestamptz NOT NULL,             -- failures are counted within a 15-minute window from here
    locked_until  timestamptz
);
