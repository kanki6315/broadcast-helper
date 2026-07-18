-- Season-level manual adjustments to a competitor's championship total.
--
-- A steward's correction is not a session's penalty. penalty_points sits on
-- standings_session_points because a published standings JSON attributes its
-- extras to the session that earned them; an iRacing league's adjustment has no
-- session at all — the league office applies it to the season total after the
-- fact, and the API reports it only on the standings row (base_points plus
-- positive_adjustments and negative_adjustments). Folding one into the other
-- would either invent a round for it or bury a deliberate ruling inside a race
-- result.
--
-- Recording it here keeps total_points reconcilable: total_points =
-- base_points + positive_adjustments + negative_adjustments, and base_points is
-- what the per-round columns sum to. Without these the difference between a row
-- total and its columns is an unexplained gap.
--
-- Nullable because most sources report nothing of the kind: an IMSA standings
-- JSON has no adjustment concept, and an official iRacing series carries none
-- (its totals are the raw sum). NULL means "this source does not say", which is
-- different from a reported zero.
ALTER TABLE standings_row
    ADD COLUMN base_points           NUMERIC,
    ADD COLUMN positive_adjustments  NUMERIC,
    ADD COLUMN negative_adjustments  NUMERIC;
