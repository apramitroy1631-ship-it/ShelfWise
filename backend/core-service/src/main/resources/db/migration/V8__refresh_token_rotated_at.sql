-- Login refresh race (review issue 1): remember WHEN a refresh token was swapped for a new one (rotation), as opposed
-- to revoked by sign-out. Several requests refreshing at the same moment with the same token are then told apart from
-- a stolen token being replayed later (see AuthService.refresh).
ALTER TABLE refresh_tokens ADD COLUMN rotated_at timestamptz;
