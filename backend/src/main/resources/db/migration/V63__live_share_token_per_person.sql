-- One share link per person instead of one for everyone (V62 had a single
-- working link). Each link is named for whoever holds it, so an admin can
-- revoke one person's link without cutting off the rest, see which links are
-- used, and rate-limit each link on its own — viewers behind one network
-- address no longer share an allowance. A link made under V62 keeps working,
-- unnamed, until it is revoked.
DROP INDEX live_share_token_one_active;

ALTER TABLE live_share_token ADD COLUMN label TEXT;
